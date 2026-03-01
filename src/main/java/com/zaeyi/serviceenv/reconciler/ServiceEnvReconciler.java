package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.service.IstioConfigService;
import com.zaeyi.serviceenv.util.AppNameUtil;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ServiceEnv Reconciler：仅更新 ServiceEnv status，不写 VS/DR。
 * VS/DR 由 AppReconciler 唯一写入。
 *
 * <p>触发：仅 ServiceEnv 自身变更或 operator 启动。
 * <p>逻辑：按 env 获取 deployments，全量更新 status。disabled 时更新 phase。
 */
@Component
@ControllerConfiguration(informer = @Informer(namespaces = {Constants.WATCH_ALL_NAMESPACES}))
@Slf4j
@RequiredArgsConstructor
public class ServiceEnvReconciler implements Reconciler<ServiceEnv> {

    private static final String NAMESPACE_ENV_INDEX = "namespace-env";

    private final IstioConfigService istioConfigService;

    private InformerEventSource<Deployment, ServiceEnv> deploymentEventSource;

    private static String indexKey(String namespace, String env) {
        return namespace + "#" + env;
    }

    @Override
    public List<EventSource<?, ServiceEnv>> prepareEventSources(EventSourceContext<ServiceEnv> context) {
        var depConfig = InformerEventSourceConfiguration.from(Deployment.class, ServiceEnv.class)
                .withSecondaryToPrimaryMapper(deployment -> Set.of())
                .withNamespacesInheritedFromController()
                .build();

        deploymentEventSource = new InformerEventSource<>(depConfig, context);
        deploymentEventSource.addIndexers(Map.of(
                NAMESPACE_ENV_INDEX, d -> {
                    if (d.getMetadata() == null || d.getMetadata().getLabels() == null
                            || d.getMetadata().getNamespace() == null) return List.of();
                    String env = d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
                    if (env == null || env.isEmpty()) return List.of();
                    return List.of(indexKey(d.getMetadata().getNamespace(), env));
                }));

        return List.of(deploymentEventSource);
    }

    @Override
    public UpdateControl<ServiceEnv> reconcile(ServiceEnv resource, Context<ServiceEnv> context) {
        if (resource.getMetadata() == null || resource.getSpec() == null) {
            log.warn("ServiceEnv has null metadata or spec skipping reconcile");
            return UpdateControl.noUpdate();
        }
        String namespace = resource.getMetadata().getNamespace();
        String envName = resource.getSpec().getEnvName();
        log.info("Reconciling ServiceEnv: {}/{} (status only)", namespace, resource.getMetadata().getName());

        try {
            if (envName == null || envName.isEmpty()) {
                log.warn("ServiceEnv {}/{} has empty envName skipping reconcile", namespace, resource.getMetadata().getName());
                return UpdateControl.noUpdate();
            }

            ServiceEnvStatus status = resource.getStatus();
            if (status == null) {
                status = new ServiceEnvStatus();
                resource.setStatus(status);
            }

            if (Boolean.TRUE.equals(resource.getSpec().getEnabled())) {
                List<ServiceEnvStatus.ServiceInfo> servicesInEnv = buildServicesInEnv(namespace, envName);
                istioConfigService.updateServiceEnvStatusForEnv(namespace, envName, servicesInEnv);
                log.info("ServiceEnv {}/{} status updated services: {}",
                        namespace, resource.getMetadata().getName(), servicesInEnv.size());
                return UpdateControl.noUpdate();
            } else {
                status.setPhase("Disabled");
                status.setMessage("Environment is disabled");
                status.setIstioConfigured(false);
                status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                resource.setStatus(status);
                return UpdateControl.patchStatus(resource);
            }

        } catch (Exception e) {
            log.error("Error reconciling ServiceEnv: {}", resource.getMetadata().getName(), e);
            ServiceEnvStatus status = resource.getStatus();
            if (status == null) {
                status = new ServiceEnvStatus();
                resource.setStatus(status);
            }
            status.setPhase("Error");
            status.setMessage("Reconciliation failed: " + e.getMessage());
            status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return UpdateControl.patchStatus(resource);
        }
    }

    private List<ServiceEnvStatus.ServiceInfo> buildServicesInEnv(String namespace, String envName) {
        var deployments = deploymentEventSource.byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, envName));
        List<ServiceEnvStatus.ServiceInfo> servicesInEnv = new ArrayList<>();
        for (Deployment d : deployments) {
            if (d.getMetadata() == null || d.getMetadata().getName() == null) continue;
            Map<String, String> labels = d.getMetadata().getLabels();
            if (labels == null || !labels.containsKey(OperatorConstants.ENV_LABEL_KEY)) continue;
            String serviceName = AppNameUtil.getAppName(d);
            if (serviceName == null || serviceName.isEmpty()) continue;
            Map<String, String> podLabels = d.getSpec() != null && d.getSpec().getTemplate() != null
                    && d.getSpec().getTemplate().getMetadata() != null
                    ? d.getSpec().getTemplate().getMetadata().getLabels()
                    : null;
            String version = podLabels != null ? podLabels.getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default")
                    : labels.getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default");

            ServiceEnvStatus.ServiceInfo info = new ServiceEnvStatus.ServiceInfo();
            info.setName(serviceName);
            info.setNamespace(namespace);
            info.setVersion(version);
            servicesInEnv.add(info);
        }
        return servicesInEnv;
    }
}
