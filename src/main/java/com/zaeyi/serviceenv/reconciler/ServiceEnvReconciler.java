package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.util.AppNameUtil;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ServiceEnv 的协调器，唯一负责维护 ServiceEnv status。
 *
 * <p><b>职责：</b>
 * <ol>
 *   <li>监听 ServiceEnv 自身变更（spec 变更、enabled 开关）
 *   <li>监听 Deployment 变更，当带有 env 标签的 Deployment 增减时触发所属 ServiceEnv 重新协调
 *   <li>全量重建 ServiceEnv.status.services 列表（该 env 下所有 Deployment 对应的服务信息）
 *   <li>env 被禁用时将 status 置为 Disabled
 * </ol>
 *
 * <p><b>不负责：</b>VirtualService 和 DestinationRule 的创建，那是 AppReconciler 的职责。
 */
@Component
@ControllerConfiguration(informer = @Informer(namespaces = {Constants.WATCH_ALL_NAMESPACES}))
public class ServiceEnvReconciler implements Reconciler<ServiceEnv> {

    private static final Logger log = LoggerFactory.getLogger(ServiceEnvReconciler.class);

    /** 索引 key: "namespace#envName" → 按 env 查找该 env 下所有带 env 标签的 Deployment。 */
    private static final String NAMESPACE_ENV_INDEX = "namespace-env";

    private final KubernetesClient kubernetesClient;

    private InformerEventSource<Deployment, ServiceEnv> deploymentEventSource;

    public ServiceEnvReconciler(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    // -----------------------------------------------------------------------
    // EventSource 注册
    // -----------------------------------------------------------------------

    @Override
    public List<EventSource<?, ServiceEnv>> prepareEventSources(EventSourceContext<ServiceEnv> context) {
        var config = InformerEventSourceConfiguration.from(Deployment.class, ServiceEnv.class)
                .withSecondaryToPrimaryMapper(this::deploymentToServiceEnv)
                .withNamespacesInheritedFromController()
                .build();
        deploymentEventSource = new InformerEventSource<>(config, context);
        deploymentEventSource.addIndexers(Map.of(NAMESPACE_ENV_INDEX, this::indexDeploymentByEnv));
        return List.of(deploymentEventSource);
    }

    /** 索引 key: "namespace#envName"，只索引带 env 标签的 Deployment。 */
    private List<String> indexDeploymentByEnv(Deployment d) {
        if (d.getMetadata() == null || d.getMetadata().getLabels() == null
                || d.getMetadata().getNamespace() == null) return List.of();
        String env = d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
        if (StringUtils.isEmpty(env)) return List.of();
        return List.of(d.getMetadata().getNamespace() + "#" + env);
    }

    /**
     * Deployment 变更时，找到对应的 ServiceEnv。
     * 约定：ServiceEnv metadata.name == spec.envName，因此直接用 env 标签值构造 ResourceID。
     */
    private Set<ResourceID> deploymentToServiceEnv(Deployment d) {
        if (d.getMetadata() == null || d.getMetadata().getLabels() == null) return Set.of();
        String env = d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
        String namespace = d.getMetadata().getNamespace();
        if (StringUtils.isEmpty(env) || namespace == null) return Set.of();
        return Set.of(new ResourceID(env, namespace));
    }

    // -----------------------------------------------------------------------
    // 主协调流程
    // -----------------------------------------------------------------------

    @Override
    public UpdateControl<ServiceEnv> reconcile(ServiceEnv serviceEnv, Context<ServiceEnv> context) {
        String namespace = serviceEnv.getMetadata().getNamespace();
        String envName   = serviceEnv.getSpec() != null ? serviceEnv.getSpec().getEnvName() : null;

        if (StringUtils.isEmpty(envName)) {
            log.warn("ServiceEnv {}/{} spec.envName is empty skipping",
                    namespace, serviceEnv.getMetadata().getName());
            return UpdateControl.noUpdate();
        }

        try {
            if (!Boolean.TRUE.equals(serviceEnv.getSpec().getEnabled())) {
                return UpdateControl.patchStatus(applyDisabledStatus(serviceEnv));
            }

            List<ServiceEnvStatus.ServiceInfo> services = buildServiceList(namespace, envName);
            applyActiveStatus(namespace, envName, services);
            log.debug("ServiceEnv reconciled {}/{} services {}", namespace, envName, services.size());
            return UpdateControl.noUpdate();

        } catch (Exception e) {
            log.error("ServiceEnv reconcile failed {}/{}", namespace, envName, e);
            return UpdateControl.patchStatus(applyErrorStatus(serviceEnv, e));
        }
    }

    // -----------------------------------------------------------------------
    // 服务列表构建
    // -----------------------------------------------------------------------

    /** 从 Deployment 内存索引中全量扫描该 env 下的所有服务，构建 ServiceInfo 列表。 */
    private List<ServiceEnvStatus.ServiceInfo> buildServiceList(String namespace, String envName) {
        return deploymentEventSource.byIndex(NAMESPACE_ENV_INDEX, namespace + "#" + envName)
                .stream()
                .filter(d -> d.getMetadata() != null && d.getMetadata().getLabels() != null)
                .map(d -> toServiceInfo(d, namespace))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ServiceEnvStatus.ServiceInfo toServiceInfo(Deployment d, String namespace) {
        String appName = AppNameUtil.getAppName(d);
        if (StringUtils.isEmpty(appName)) return null;

        ServiceEnvStatus.ServiceInfo info = new ServiceEnvStatus.ServiceInfo();
        info.setName(appName);
        info.setNamespace(namespace);
        info.setVersion(resolveVersion(d));
        return info;
    }

    /** 优先读 Pod template labels 的 version 标签，fallback 到 Deployment labels，默认 "default"。 */
    private String resolveVersion(Deployment d) {
        if (d.getSpec() != null && d.getSpec().getTemplate() != null) {
            var podLabels = d.getSpec().getTemplate().getMetadata() != null
                    ? d.getSpec().getTemplate().getMetadata().getLabels() : null;
            if (podLabels != null) {
                return podLabels.getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default");
            }
        }
        if (d.getMetadata().getLabels() != null) {
            return d.getMetadata().getLabels().getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default");
        }
        return "default";
    }

    // -----------------------------------------------------------------------
    // Status 写入（带乐观锁重试）
    // -----------------------------------------------------------------------

    /**
     * 全量写入 ServiceEnv status，使用乐观锁重试应对并发冲突。
     * 每次重试都重新 get 最新版本，确保 resourceVersion 正确。
     */
    private void applyActiveStatus(String namespace, String envName,
                                   List<ServiceEnvStatus.ServiceInfo> services) {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                ServiceEnv latest = kubernetesClient.resources(ServiceEnv.class)
                        .inNamespace(namespace).withName(envName).get();
                if (latest == null) return;

                ServiceEnvStatus status = latest.getStatus() != null
                        ? latest.getStatus() : new ServiceEnvStatus();
                status.setServices(services);
                status.setPhase("Active");
                status.setMessage("Environment is active with " + services.size() + " services");
                status.setIstioConfigured(true);
                status.setLastUpdateTime(now());
                latest.setStatus(status);
                kubernetesClient.resource(latest).updateStatus();
                return;

            } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
                if (e.getCode() == HttpURLConnection.HTTP_CONFLICT && attempt < maxRetries - 1) {
                    log.debug("ServiceEnv status conflict {}/{} attempt {} retrying", namespace, envName, attempt + 1);
                } else {
                    throw e;
                }
            }
        }
    }

    private ServiceEnv applyDisabledStatus(ServiceEnv serviceEnv) {
        ServiceEnvStatus status = serviceEnv.getStatus() != null
                ? serviceEnv.getStatus() : new ServiceEnvStatus();
        status.setPhase("Disabled");
        status.setMessage("Environment is disabled");
        status.setIstioConfigured(false);
        status.setLastUpdateTime(now());
        serviceEnv.setStatus(status);
        return serviceEnv;
    }

    private ServiceEnv applyErrorStatus(ServiceEnv serviceEnv, Exception e) {
        ServiceEnvStatus status = serviceEnv.getStatus() != null
                ? serviceEnv.getStatus() : new ServiceEnvStatus();
        status.setPhase("Error");
        status.setMessage("Reconciliation failed: " + e.getMessage());
        status.setLastUpdateTime(now());
        serviceEnv.setStatus(status);
        return serviceEnv;
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
