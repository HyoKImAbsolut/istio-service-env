package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.dependent.DestinationRuleDependentResource;
import com.zaeyi.serviceenv.dependent.VirtualServiceDependentResource;
import com.zaeyi.serviceenv.crd.App;
import com.zaeyi.serviceenv.crd.AppStatus;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.service.IstioConfigService;
import com.zaeyi.serviceenv.util.AppNameUtil;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.filter.OnUpdateFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * App Reconciler：使用 DependentResource 管理 VS/DR，ownerReference 由 SDK 自动添加。
 */
@Component
@ControllerConfiguration(informer = @Informer(namespaces = {Constants.WATCH_ALL_NAMESPACES}))
@Slf4j
@RequiredArgsConstructor
public class AppReconciler implements Reconciler<App> {

    private static final String NAMESPACE_ENV_INDEX = "namespace-env";
    private static final String SERVICE_INDEX = "namespace-service";

    private final IstioConfigService istioConfigService;
    private final KubernetesClient kubernetesClient;
    private final DestinationRuleDependentResource destinationRuleDependent;
    private final VirtualServiceDependentResource virtualServiceDependent;

    private InformerEventSource<Deployment, App> deploymentEventSource;
    private InformerEventSource<ServiceEnv, App> serviceEnvEventSource;

    private static String indexKey(String namespace, String env) {
        return namespace + "#" + env;
    }

    private static String serviceIndexKey(String namespace, String serviceName) {
        return namespace + "#" + serviceName;
    }

    @Override
    public List<EventSource<?, App>> prepareEventSources(EventSourceContext<App> context) {
        var depConfig = InformerEventSourceConfiguration.from(Deployment.class, App.class)
                .withSecondaryToPrimaryMapper(this::deploymentToApp)
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
                },
                SERVICE_INDEX, d -> {
                    if (d.getMetadata() == null || d.getMetadata().getNamespace() == null) return List.of();
                    String svc = AppNameUtil.getAppName(d);
                    if (svc == null || svc.isEmpty()) return List.of();
                    if (d.getMetadata().getLabels() == null
                            || !d.getMetadata().getLabels().containsKey(OperatorConstants.ENV_LABEL_KEY)) return List.of();
                    return List.of(serviceIndexKey(d.getMetadata().getNamespace(), svc));
                }));

        var seConfig = InformerEventSourceConfiguration.from(ServiceEnv.class, App.class)
                .withSecondaryToPrimaryMapper(this::serviceEnvToApps)
                .withNamespacesInheritedFromController()
                .withOnUpdateFilter((ServiceEnv oldObj, ServiceEnv newObj) -> {
                    if (oldObj == null || newObj == null) return true;
                    var oldGen = oldObj.getMetadata() != null ? oldObj.getMetadata().getGeneration() : null;
                    var newGen = newObj.getMetadata() != null ? newObj.getMetadata().getGeneration() : null;
                    return oldGen == null || newGen == null || !oldGen.equals(newGen);
                })
                .build();
        serviceEnvEventSource = new InformerEventSource<>(seConfig, context);

        var eventSources = new ArrayList<EventSource<?, App>>();
        eventSources.add(deploymentEventSource);
        eventSources.add(serviceEnvEventSource);
        // 不注册 DependentResource event source，避免创建/更新 VS/DR 时触发冗余 reconcile 导致 409 冲突
        // VS/DR 被外部删除时，下次 Deployment/ServiceEnv/App 变更会触发 reconcile 并重建

        return eventSources;
    }

    private Set<ResourceID> deploymentToApp(Deployment d) {
        if (d == null || d.getMetadata() == null) return Set.of();
        String deploymentAppName = AppNameUtil.getAppName(d);
        if (deploymentAppName == null || deploymentAppName.isEmpty()) return Set.of();
        if (d.getMetadata().getLabels() == null
                || !d.getMetadata().getLabels().containsKey(OperatorConstants.ENV_LABEL_KEY)) return Set.of();
        String namespace = d.getMetadata().getNamespace();
        return findAppsByAppName(namespace, deploymentAppName);
    }

    private Set<ResourceID> serviceEnvToApps(ServiceEnv se) {
        if (se == null || se.getMetadata() == null || se.getSpec() == null) return Set.of();
        String envName = se.getSpec().getEnvName();
        if (envName == null || envName.isEmpty()) return Set.of();
        String namespace = se.getMetadata().getNamespace();
        var deployments = deploymentEventSource.byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, envName));
        return deployments.stream()
                .map(d -> {
                    String appName = AppNameUtil.getAppName(d);
                    if (appName == null || appName.isEmpty()) return null;
                    return findAppsByAppName(namespace, appName);
                })
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    private Set<ResourceID> findAppsByAppName(String namespace, String appName) {
        var apps = kubernetesClient.resources(App.class).inNamespace(namespace).list().getItems();
        return apps.stream()
                .filter(a -> a.getSpec() != null && appName.equals(a.getSpec().getAppName()))
                .map(a -> new ResourceID(a.getMetadata().getName(), a.getMetadata().getNamespace()))
                .collect(Collectors.toSet());
    }

    @Override
    public UpdateControl<App> reconcile(App resource, Context<App> context) {
        if (resource.getMetadata() == null || resource.getSpec() == null) {
            return UpdateControl.noUpdate();
        }
        String namespace = resource.getMetadata().getNamespace();
        String appName = resource.getSpec().getAppName();
        if (namespace == null || appName == null || appName.isEmpty()) {
            log.warn("App {}/{} has empty spec.appName skipping reconcile", namespace, resource.getMetadata().getName());
            return UpdateControl.noUpdate();
        }

        try {
            destinationRuleDependent.reconcile(resource, context);
            virtualServiceDependent.reconcile(resource, context);

            Set<String> envs = deploymentEventSource.byIndex(SERVICE_INDEX, serviceIndexKey(namespace, appName))
                    .stream()
                    .filter(d -> d.getMetadata() != null && d.getMetadata().getLabels() != null)
                    .map(d -> d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY))
                    .filter(Objects::nonNull)
                    .filter(e -> !e.isEmpty())
                    .filter(env -> istioConfigService.serviceEnvExists(namespace, env))
                    .collect(Collectors.toSet());

            AppStatus status = resource.getStatus() != null ? resource.getStatus() : new AppStatus();
            status.setEnvs(new ArrayList<>(envs));
            status.setPhase("Active");
            status.setMessage("App " + appName + " with envs " + envs);
            status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            resource.setStatus(status);

            updateServiceEnvStatuses(namespace, appName, envs);

            log.debug("App reconciled {}/{} envs {}", namespace, appName, envs);
            return UpdateControl.patchStatus(resource);
        } catch (Exception e) {
            log.error("App reconcile failed {}/{}", namespace, appName, e);
            AppStatus status = resource.getStatus() != null ? resource.getStatus() : new AppStatus();
            status.setPhase("Error");
            status.setMessage("Reconciliation failed: " + e.getMessage());
            status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            resource.setStatus(status);
            return UpdateControl.patchStatus(resource);
        }
    }

    private void updateServiceEnvStatuses(String namespace, String appName, Set<String> envs) {
        var deployments = deploymentEventSource.byIndex(SERVICE_INDEX, serviceIndexKey(namespace, appName));
        for (Deployment d : deployments) {
            if (d.getMetadata() == null || d.getMetadata().getLabels() == null) continue;
            String env = d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
            if (env == null || !envs.contains(env)) continue;
            String version = getVersion(d);
            if (d.getMetadata().getDeletionTimestamp() != null) {
                istioConfigService.removeServiceFromEnvStatus(namespace, env, appName, version);
            } else {
                var info = new ServiceEnvStatus.ServiceInfo();
                info.setName(appName);
                info.setNamespace(namespace);
                info.setVersion(version);
                istioConfigService.addOrUpdateServiceInEnvStatus(namespace, env, info);
            }
        }
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
}
