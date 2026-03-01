package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.service.IstioConfigService;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ServiceEnv 的 Reconciler。
 *
 * <p>触发：Deployment(env=E) 触发 ServiceEnv(envName=E) 及 ServiceEnv(fallbackEnv=E)。
 * <p>逻辑：为本环境创建 DR+VS；若 spec.fallbackEnv 存在，为「fallback 有、本环境无」的服务创建 fallback VS。每个 ServiceEnv 可独立配置 fallback。
 */
@Component
@ControllerConfiguration
@Slf4j
@RequiredArgsConstructor
public class ServiceEnvReconciler implements Reconciler<ServiceEnv> {

    private static final String NAMESPACE_ENV_INDEX = "namespace-env";
    private static final String NAMESPACE_FALLBACK_INDEX = "namespace-fallback";

    private final IstioConfigService istioConfigService;

    private InformerEventSource<Deployment, ServiceEnv> deploymentEventSource;

    /** 索引 key：namespace#env，用于 O(1) 查询 */
    private static String indexKey(String namespace, String env) {
        return namespace + "#" + env;
    }

    /** 优先 app.kubernetes.io/name，否则用 Deployment 名 */
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

    // --- EventSource ---

    @Override
    public List<EventSource<?, ServiceEnv>> prepareEventSources(EventSourceContext<ServiceEnv> context) {
        context.getPrimaryCache().addIndexer(NAMESPACE_ENV_INDEX, se -> {
            if (se.getMetadata() == null || se.getSpec() == null || se.getSpec().getEnvName() == null
                    || se.getMetadata().getNamespace() == null) {
                return List.of();
            }
            return List.of(indexKey(se.getMetadata().getNamespace(), se.getSpec().getEnvName()));
        });
        context.getPrimaryCache().addIndexer(NAMESPACE_FALLBACK_INDEX, se -> {
            if (se.getMetadata() == null || se.getSpec() == null || se.getSpec().getFallbackEnv() == null
                    || se.getSpec().getFallbackEnv().isEmpty() || se.getMetadata().getNamespace() == null) {
                return List.of();
            }
            return List.of(indexKey(se.getMetadata().getNamespace(), se.getSpec().getFallbackEnv()));
        });
        InformerEventSourceConfiguration<Deployment> config =
                InformerEventSourceConfiguration.from(Deployment.class, ServiceEnv.class)
                        .withSecondaryToPrimaryMapper(deployment -> {
                            if (deployment.getMetadata() == null || deployment.getMetadata().getLabels() == null) {
                                return Set.of();
                            }
                            String env = deployment.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
                            if (env == null || env.isEmpty()) {
                                return Set.of();
                            }
                            String namespace = deployment.getMetadata().getNamespace();
                            Set<ResourceID> targets = new LinkedHashSet<>();
                            targets.addAll(context.getPrimaryCache()
                                    .byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, env))
                                    .stream().map(ResourceID::fromResource).toList());
                            targets.addAll(context.getPrimaryCache()
                                    .byIndex(NAMESPACE_FALLBACK_INDEX, indexKey(namespace, env))
                                    .stream().map(ResourceID::fromResource).toList());
                            return targets;
                        })
                        .withNamespacesInheritedFromController()
                        .build();

        deploymentEventSource = new InformerEventSource<>(config, context);
        deploymentEventSource.addIndexers(Map.of(NAMESPACE_ENV_INDEX, d -> {
            if (d.getMetadata() == null || d.getMetadata().getLabels() == null
                    || d.getMetadata().getNamespace() == null) {
                return List.of();
            }
            String env = d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
            if (env == null || env.isEmpty()) {
                return List.of();
            }
            return List.of(indexKey(d.getMetadata().getNamespace(), env));
        }));

        return List.of(deploymentEventSource);
    }

    // --- Reconcile ---

    @Override
    public UpdateControl<ServiceEnv> reconcile(ServiceEnv resource, Context<ServiceEnv> context) {
        if (resource.getMetadata() == null || resource.getSpec() == null) {
            log.warn("ServiceEnv has null metadata or spec skipping reconcile");
            return UpdateControl.noUpdate();
        }
        log.info("Reconciling ServiceEnv: {}/{}", resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());

        try {
            String envName = resource.getSpec().getEnvName();
            String namespace = resource.getMetadata().getNamespace();
            if (envName == null || envName.isEmpty()) {
                log.warn("ServiceEnv {}/{} has empty envName skipping reconcile", namespace, resource.getMetadata().getName());
                return UpdateControl.noUpdate();
            }

            List<Deployment> deploymentsFromCache = deploymentEventSource
                    .byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, envName));
            ReconcilerInput input = buildReconcilerInput(namespace, envName, deploymentsFromCache);

            ServiceEnvStatus status = resource.getStatus();
            if (status == null) {
                status = new ServiceEnvStatus();
                resource.setStatus(status);
            }
            status.setServices(input.servicesInEnv());
            status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            if (Boolean.TRUE.equals(resource.getSpec().getEnabled())) {
                istioConfigService.configureIstio(resource, input);
                configureFallbackForSelf(resource, input, context);
                status.setPhase("Active");
                status.setMessage("Environment is active with " + input.servicesInEnv().size() + " services");
                status.setIstioConfigured(true);
            } else {
                status.setPhase("Disabled");
                status.setMessage("Environment is disabled");
                status.setIstioConfigured(false);
            }

            log.info("Successfully reconciled ServiceEnv: {} services in env: {}",
                    envName, input.servicesInEnv().size());
            return UpdateControl.patchStatus(resource);

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
            if (d.getMetadata() == null || d.getMetadata().getName() == null) {
                continue;
            }
            Map<String, String> labels = d.getMetadata().getLabels();
            if (labels == null || !labels.containsKey(OperatorConstants.ENV_LABEL_KEY)) {
                continue;
            }
            String serviceName = getAppName(d);
            if (serviceName == null || serviceName.isEmpty()) {
                continue;
            }
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

    /** 为「fallback 有、本环境无」的服务创建 fallback VS；fallbackEnv 来自 spec.fallbackEnv */
    private void configureFallbackForSelf(ServiceEnv resource, ReconcilerInput myInput, Context<ServiceEnv> context) {
        String fallbackEnv = resource.getSpec().getFallbackEnv();
        if (fallbackEnv == null || fallbackEnv.isEmpty()) {
            return;
        }
        String namespace = resource.getMetadata().getNamespace();
        if (fallbackEnv.equals(resource.getSpec().getEnvName())) {
            return;
        }
        List<ServiceEnv> fallbackTargets = context.getPrimaryCache()
                .byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, fallbackEnv));
        if (fallbackTargets.isEmpty()) {
            return;
        }
        List<Deployment> fallbackDeployments = deploymentEventSource
                .byIndex(NAMESPACE_ENV_INDEX, indexKey(namespace, fallbackEnv));
        ReconcilerInput fallbackInput = buildReconcilerInput(namespace, fallbackEnv, fallbackDeployments);
        istioConfigService.configureFallbackForSelf(resource, myInput, fallbackInput, fallbackEnv);
    }

    /** 本环境服务列表及版本信息 */
    public record ReconcilerInput(
            List<ServiceEnvStatus.ServiceInfo> servicesInEnv,
            Map<String, Set<String>> serviceVersions
    ) {}
}
