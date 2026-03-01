package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.service.IstioConfigService;
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
 * ServiceEnv 的 Reconciler，全量兜底。
 *
 * <p>哲学：ServiceEnv 是 CRD 所有者。Deployment 变更走 DeploymentReconciler 增量路径，避免全量遍历。
 * <p>触发：仅 ServiceEnv 自身变更 或 operator 启动（Informer 初始 list）。Deployment 变更不触发。
 * <p>逻辑：按 env 获取 deployments，更新 status；对涉及到的 service 更新 VS/DR。需 Deployment 缓存做 per-env 查询。
 */
@Component
@ControllerConfiguration(informer = @Informer(namespaces = {Constants.WATCH_ALL_NAMESPACES}))
@Slf4j
@RequiredArgsConstructor
public class ServiceEnvReconciler implements Reconciler<ServiceEnv> {

    private static final String NAMESPACE_ENV_INDEX = "namespace-env";
    private static final String SERVICE_INDEX = "namespace-service";

    private final IstioConfigService istioConfigService;

    private InformerEventSource<Deployment, ServiceEnv> deploymentEventSource;

    private static String indexKey(String namespace, String env) {
        return namespace + "#" + env;
    }

    private static String serviceIndexKey(String namespace, String serviceName) {
        return namespace + "#" + serviceName;
    }

    private static String getAppName(Deployment d) {
        if (d.getSpec() != null && d.getSpec().getTemplate() != null
                && d.getSpec().getTemplate().getMetadata() != null
                && d.getSpec().getTemplate().getMetadata().getLabels() != null) {
            String name = d.getSpec().getTemplate().getMetadata().getLabels().get(OperatorConstants.APP_NAME_LABEL_KEY);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return d.getMetadata() != null ? d.getMetadata().getName() : null;
    }

    @Override
    public List<EventSource<?, ServiceEnv>> prepareEventSources(EventSourceContext<ServiceEnv> context) {
        InformerEventSourceConfiguration<Deployment> depConfig =
                InformerEventSourceConfiguration.from(Deployment.class, ServiceEnv.class)
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
                },
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
    public UpdateControl<ServiceEnv> reconcile(ServiceEnv resource, Context<ServiceEnv> context) {
        if (resource.getMetadata() == null || resource.getSpec() == null) {
            log.warn("ServiceEnv has null metadata or spec skipping reconcile");
            return UpdateControl.noUpdate();
        }
        String namespace = resource.getMetadata().getNamespace();
        String envName = resource.getSpec().getEnvName();
        log.info("Reconciling ServiceEnv: {}/{} (namespace-wide)", namespace, resource.getMetadata().getName());

        try {
            if (envName == null || envName.isEmpty()) {
                log.warn("ServiceEnv {}/{} has empty envName skipping reconcile", namespace, resource.getMetadata().getName());
                return UpdateControl.noUpdate();
            }

            ReconcilerInput myInput = buildReconcilerInput(namespace, envName,
                    deploymentEventSource.byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, envName)));

            ServiceEnvStatus status = resource.getStatus();
            if (status == null) {
                status = new ServiceEnvStatus();
                resource.setStatus(status);
            }

            if (Boolean.TRUE.equals(resource.getSpec().getEnabled())) {
                istioConfigService.updateServiceEnvStatusForEnv(namespace, envName, myInput.servicesInEnv());
                Set<String> previousServices = resource.getStatus() != null && resource.getStatus().getServices() != null
                        ? resource.getStatus().getServices().stream()
                                .map(ServiceEnvStatus.ServiceInfo::getName)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet())
                        : Set.of();
                configureIstioForServicesInEnv(namespace, previousServices, myInput);
                log.info("Successfully reconciled ServiceEnv: {}/{} services: {}",
                        namespace, resource.getMetadata().getName(), myInput.servicesInEnv().size());
                return UpdateControl.noUpdate();
            } else {
                status.setPhase("Disabled");
                status.setMessage("Environment is disabled");
                status.setIstioConfigured(false);
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

    private ReconcilerInput buildReconcilerInput(String namespace, String envName,
            Collection<Deployment> deployments) {
        List<ServiceEnvStatus.ServiceInfo> servicesInEnv = new ArrayList<>();
        Map<String, Set<String>> serviceVersions = new HashMap<>();

        for (Deployment d : deployments) {
            if (d.getMetadata() == null || d.getMetadata().getName() == null) continue;
            Map<String, String> labels = d.getMetadata().getLabels();
            if (labels == null || !labels.containsKey(OperatorConstants.ENV_LABEL_KEY)) continue;
            String serviceName = getAppName(d);
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
            serviceVersions.computeIfAbsent(serviceName, k -> new HashSet<>()).add(version);
        }

        return new ReconcilerInput(servicesInEnv, serviceVersions);
    }

    private void configureIstioForServicesInEnv(String namespace, Set<String> previousServices, ReconcilerInput input) {
        Set<String> currentServices = input.servicesInEnv().stream()
                .map(ServiceEnvStatus.ServiceInfo::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> serviceNames = new HashSet<>(previousServices);
        serviceNames.addAll(currentServices);
        String fallbackEnv = istioConfigService.getFallbackEnvFromNamespace(namespace);
        for (String serviceName : serviceNames) {
            Set<String> envs = deploymentEventSource.byIndex(SERVICE_INDEX, serviceIndexKey(namespace, serviceName))
                    .stream()
                    .filter(d -> d.getMetadata() != null && d.getMetadata().getLabels() != null)
                    .map(d -> d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY))
                    .filter(Objects::nonNull)
                    .filter(e -> !e.isEmpty())
                    .collect(Collectors.toSet());
            istioConfigService.configureServiceForIstio(namespace, serviceName, envs, fallbackEnv);
        }
    }

    public record ReconcilerInput(
            List<ServiceEnvStatus.ServiceInfo> servicesInEnv,
            Map<String, Set<String>> serviceVersions
    ) {}
}
