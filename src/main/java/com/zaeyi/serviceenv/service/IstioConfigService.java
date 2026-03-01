package com.zaeyi.serviceenv.service;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置 Istio VirtualService、DestinationRule。
 *
 * <p>每个 service 一个 DR、一个 VS。
 * <p>VS：按 env 的路由 + 兜底 catch-all（从 namespace 注解读取 fallback env）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IstioConfigService {

    private final KubernetesClient kubernetesClient;

    /**
     * 增量：添加或更新单个 service 到 ServiceEnv status。
     * 约定：ServiceEnv metadata.name == spec.envName。
     */
    public void addOrUpdateServiceInEnvStatus(String namespace, String envName, ServiceEnvStatus.ServiceInfo serviceInfo) {
        if (serviceInfo == null || serviceInfo.getName() == null || serviceInfo.getName().isEmpty()) {
            return;
        }
        try {
            ServiceEnv se = kubernetesClient.resources(ServiceEnv.class).inNamespace(namespace).withName(envName).get();
            if (se == null || se.getSpec() == null || !envName.equals(se.getSpec().getEnvName())) {
                return;
            }
            ServiceEnvStatus status = se.getStatus() != null ? se.getStatus() : new ServiceEnvStatus();
            List<ServiceEnvStatus.ServiceInfo> list = status.getServices() != null
                    ? new ArrayList<>(status.getServices()) : new ArrayList<>();
            String version = serviceInfo.getVersion() != null ? serviceInfo.getVersion() : "default";
            list.removeIf(s -> serviceInfo.getName().equals(s.getName()) && version.equals(s.getVersion() != null ? s.getVersion() : "default"));
            ServiceEnvStatus.ServiceInfo entry = new ServiceEnvStatus.ServiceInfo();
            entry.setName(serviceInfo.getName());
            entry.setNamespace(namespace);
            entry.setVersion(version);
            list.add(entry);
            applyStatus(se, status, list);
        } catch (Exception e) {
            log.debug("AddOrUpdate ServiceEnv status failed: {}", e.getMessage());
        }
    }

    /**
     * 增量：从 ServiceEnv status 移除单个 service。
     */
    public void removeServiceFromEnvStatus(String namespace, String envName, String serviceName, String version) {
        if (serviceName == null || serviceName.isEmpty()) {
            return;
        }
        try {
            ServiceEnv se = kubernetesClient.resources(ServiceEnv.class).inNamespace(namespace).withName(envName).get();
            if (se == null || se.getSpec() == null || !envName.equals(se.getSpec().getEnvName())) {
                return;
            }
            ServiceEnvStatus status = se.getStatus() != null ? se.getStatus() : new ServiceEnvStatus();
            List<ServiceEnvStatus.ServiceInfo> list = status.getServices() != null
                    ? new ArrayList<>(status.getServices()) : new ArrayList<>();
            String v = version != null ? version : "default";
            list.removeIf(s -> serviceName.equals(s.getName()) && v.equals(s.getVersion() != null ? s.getVersion() : "default"));
            applyStatus(se, status, list);
        } catch (Exception e) {
            log.debug("Remove ServiceEnv status failed: {}", e.getMessage());
        }
    }

    /**
     * 全量：更新指定 env 的 ServiceEnv status（用于 ServiceEnvReconciler 或 resync 兜底）。
     * 约定：ServiceEnv metadata.name == spec.envName。
     */
    public void updateServiceEnvStatusForEnv(String namespace, String envName,
            List<ServiceEnvStatus.ServiceInfo> services) {
        try {
            ServiceEnv se = kubernetesClient.resources(ServiceEnv.class).inNamespace(namespace).withName(envName).get();
            if (se == null || se.getSpec() == null || !envName.equals(se.getSpec().getEnvName())) {
                return;
            }
            ServiceEnvStatus status = se.getStatus() != null ? se.getStatus() : new ServiceEnvStatus();
            applyStatus(se, status, services != null ? services : List.of());
        } catch (Exception e) {
            log.debug("Update ServiceEnv status failed: {}", e.getMessage());
        }
    }

    private void applyStatus(ServiceEnv se, ServiceEnvStatus status, List<ServiceEnvStatus.ServiceInfo> services) {
        status.setServices(services);
        status.setLastUpdateTime(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        status.setPhase("Active");
        status.setMessage("Environment is active with " + services.size() + " services");
        status.setIstioConfigured(true);
        se.setStatus(status);
        kubernetesClient.resource(se).updateStatus();
    }

    /** 约定：ServiceEnv metadata.name == spec.envName。检查对应 env 的 ServiceEnv 是否存在且启用。 */
    public boolean serviceEnvExists(String namespace, String envName) {
        if (namespace == null || envName == null || envName.isEmpty()) return false;
        try {
            ServiceEnv se = kubernetesClient.resources(ServiceEnv.class).inNamespace(namespace).withName(envName).get();
            return se != null && se.getSpec() != null && envName.equals(se.getSpec().getEnvName())
                    && Boolean.TRUE.equals(se.getSpec().getEnabled());
        } catch (Exception e) {
            log.debug("Check ServiceEnv {}/{} failed: {}", namespace, envName, e.getMessage());
            return false;
        }
    }

    public String getFallbackEnvFromNamespace(String namespace) {
        try {
            Namespace ns = kubernetesClient.namespaces().withName(namespace).get();
            if (ns != null && ns.getMetadata() != null && ns.getMetadata().getAnnotations() != null) {
                return ns.getMetadata().getAnnotations().get(OperatorConstants.NAMESPACE_FALLBACK_ENV_ANNOTATION);
            }
        } catch (Exception e) {
            log.debug("Get namespace {} annotation failed: {}", namespace, e.getMessage());
        }
        return null;
    }
}
