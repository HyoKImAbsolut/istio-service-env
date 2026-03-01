package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.service.IstioConfigService;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.source.filter.OnAddFilter;
import io.javaoperatorsdk.operator.processing.event.source.filter.OnUpdateFilter;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Deployment 的 Reconciler，增量更新 Istio 配置与 ServiceEnv status。
 *
 * <p>触发：仅当 env/app/version 相关变更时触发，scale/image 等无关变更不触发。
 * <p>逻辑：仅更新该 Deployment 所属 service 的 VS/DR，以及对应 env 的 status（增量 add/remove）。
 */
@Component
@ControllerConfiguration(informer = @Informer(namespaces = {Constants.WATCH_ALL_NAMESPACES}))
@Slf4j
@RequiredArgsConstructor
public class DeploymentReconciler implements Reconciler<Deployment> {

    private static final String SERVICE_INDEX = "namespace-service";

    private final IstioConfigService istioConfigService;

    private InformerEventSource<Deployment, Deployment> deploymentEventSource;

    private static String serviceIndexKey(String namespace, String serviceName) {
        return namespace + "#" + serviceName;
    }

    private static boolean hasEnvAndApp(Deployment d) {
        if (d == null || d.getMetadata() == null || d.getMetadata().getLabels() == null) return false;
        String env = d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
        if (env == null || env.isEmpty()) return false;
        String app = getAppName(d);
        return app != null && !app.isEmpty();
    }

    private static boolean envOrAppOrVersionChanged(Deployment newD, Deployment oldD) {
        if (newD == null || oldD == null) return true;
        String oldEnv = oldD.getMetadata() != null && oldD.getMetadata().getLabels() != null
                ? oldD.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY) : null;
        String newEnv = newD.getMetadata() != null && newD.getMetadata().getLabels() != null
                ? newD.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY) : null;
        if (!Objects.equals(oldEnv, newEnv)) return true;
        if (!Objects.equals(getAppName(oldD), getAppName(newD))) return true;
        if (!Objects.equals(getVersion(oldD), getVersion(newD))) return true;
        return false;
    }

    private static String getAppName(Deployment d) {
        if (d.getSpec() != null && d.getSpec().getTemplate() != null
                && d.getSpec().getTemplate().getMetadata() != null
                && d.getSpec().getTemplate().getMetadata().getLabels() != null) {
            String name = d.getSpec().getTemplate().getMetadata().getLabels().get(OperatorConstants.APP_NAME_LABEL_KEY);
            if (name != null && !name.isEmpty()) return name;
        }
        return d.getMetadata() != null ? d.getMetadata().getName() : null;
    }

    private static String getVersion(Deployment d) {
        if (d.getSpec() != null && d.getSpec().getTemplate() != null
                && d.getSpec().getTemplate().getMetadata() != null
                && d.getSpec().getTemplate().getMetadata().getLabels() != null) {
            return d.getSpec().getTemplate().getMetadata().getLabels()
                    .getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default");
        }
        if (d.getMetadata() != null && d.getMetadata().getLabels() != null) {
            return d.getMetadata().getLabels().getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default");
        }
        return "default";
    }

    @Override
    public List<EventSource<?, Deployment>> prepareEventSources(EventSourceContext<Deployment> context) {
        deploymentEventSource = new InformerEventSource<>(
                InformerEventSourceConfiguration.from(Deployment.class, Deployment.class)
                        .withNamespacesInheritedFromController()
                        .withOnAddFilter((OnAddFilter<Deployment>) d -> hasEnvAndApp(d))
                        .withOnUpdateFilter((OnUpdateFilter<Deployment>) (newD, oldD) ->
                                envOrAppOrVersionChanged(newD, oldD))
                        .withOnDeleteFilter((d, deletedFinalStateUnknown) -> hasEnvAndApp((Deployment) d))
                        .build(),
                context);
        deploymentEventSource.addIndexers(Map.of(
                SERVICE_INDEX, d -> {
                    if (d.getMetadata() == null || d.getMetadata().getNamespace() == null) return List.of();
                    String svc = getAppName(d);
                    if (svc == null || svc.isEmpty()) return List.of();
                    if (d.getMetadata().getLabels() == null
                            || !d.getMetadata().getLabels().containsKey(OperatorConstants.ENV_LABEL_KEY)) return List.of();
                    return List.of(serviceIndexKey(d.getMetadata().getNamespace(), svc));
                }));

        return List.of(deploymentEventSource);
    }

    @Override
    public UpdateControl<Deployment> reconcile(Deployment deployment, Context<Deployment> context) {
        if (deployment.getMetadata() == null || deployment.getMetadata().getLabels() == null) {
            return UpdateControl.noUpdate();
        }
        String namespace = deployment.getMetadata().getNamespace();
        String env = deployment.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
        String serviceName = getAppName(deployment);

        if (namespace == null || env == null || env.isEmpty() || serviceName == null || serviceName.isEmpty()) {
            return UpdateControl.noUpdate();
        }

        try {
            Set<String> envs = deploymentEventSource.byIndex(SERVICE_INDEX, serviceIndexKey(namespace, serviceName))
                    .stream()
                    .filter(d -> d.getMetadata() != null && d.getMetadata().getLabels() != null)
                    .map(d -> d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY))
                    .filter(Objects::nonNull)
                    .filter(e -> !e.isEmpty())
                    .collect(Collectors.toSet());

            String fallbackEnv = istioConfigService.getFallbackEnvFromNamespace(namespace);
            istioConfigService.configureServiceForIstio(namespace, serviceName, envs, fallbackEnv);

            if (deployment.getMetadata().getDeletionTimestamp() != null) {
                istioConfigService.removeServiceFromEnvStatus(namespace, env, serviceName, getVersion(deployment));
            } else {
                ServiceEnvStatus.ServiceInfo info = new ServiceEnvStatus.ServiceInfo();
                info.setName(serviceName);
                info.setNamespace(namespace);
                info.setVersion(getVersion(deployment));
                istioConfigService.addOrUpdateServiceInEnvStatus(namespace, env, info);
            }

            log.debug("Incremental reconcile service: {}/{} envs: {}", namespace, serviceName, envs);
            return UpdateControl.noUpdate();

        } catch (Exception e) {
            log.error("Deployment reconcile failed {}/{}", namespace, deployment.getMetadata().getName(), e);
            throw new RuntimeException(e);
        }
    }

}
