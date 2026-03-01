package com.zaeyi.serviceenv.service;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;

import io.fabric8.istio.api.api.networking.v1alpha3.*;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.fabric8.istio.api.networking.v1.DestinationRuleBuilder;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.fabric8.istio.api.networking.v1.VirtualServiceBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    private static final String MANAGED_BY = "serviceenv-operator";

    private final KubernetesClient kubernetesClient;

    /** 增量：仅配置单个 service 的 DR、VS */
    public void configureServiceForIstio(String namespace, String serviceName, Set<String> envs, String fallbackEnv) {
        if (namespace == null || serviceName == null || envs == null) {
            throw new IllegalArgumentException("namespace, serviceName, envs required");
        }
        if (envs.isEmpty()) {
            deleteServiceResources(namespace, serviceName);
            return;
        }
        createOrUpdateDestinationRule(namespace, serviceName, envs);
        createOrUpdateVirtualService(namespace, serviceName, envs, fallbackEnv);
    }

    private void deleteServiceResources(String namespace, String serviceName) {
        try {
            kubernetesClient.resources(io.fabric8.istio.api.networking.v1.VirtualService.class)
                    .inNamespace(namespace).withName(serviceName + "-vs").delete();
            kubernetesClient.resources(io.fabric8.istio.api.networking.v1.DestinationRule.class)
                    .inNamespace(namespace).withName(serviceName + "-dr").delete();
            log.debug("Deleted VS/DR for {}/{}", namespace, serviceName);
        } catch (Exception e) {
            log.debug("Delete VS/DR {}/{}: {}", namespace, serviceName, e.getMessage());
        }
    }

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

    private void createOrUpdateDestinationRule(String namespace, String serviceName, Set<String> envs) {
        List<Subset> subsets = envs.stream()
                .map(env -> new SubsetBuilder()
                        .withName(env)
                        .withLabels(Map.of(OperatorConstants.ENV_LABEL_KEY, env))
                        .build())
                .toList();

        var dr = new DestinationRuleBuilder()
                .withNewMetadata()
                    .withName(serviceName + "-dr")
                    .withNamespace(namespace)
                    .addToLabels(OperatorConstants.ISTIO_RESOURCE_LABEL_PREFIX + "/service", serviceName)
                    .addToLabels("app.kubernetes.io/managed-by", MANAGED_BY)
                .endMetadata()
                .withNewSpec()
                    .withHost(serviceName)
                    .withSubsets(subsets)
                .endSpec()
                .build();

        apply(kubernetesClient.resources(DestinationRule.class).inNamespace(namespace).resource(dr),
                "DestinationRule", namespace, serviceName + "-dr");
    }

    private void createOrUpdateVirtualService(String namespace, String serviceName,
            Set<String> envs, String fallbackEnv) {
        List<HTTPRoute> routes = envs.stream()
                .map(env -> buildEnvRoute(serviceName, env))
                .collect(Collectors.toCollection(ArrayList::new));

        if (fallbackEnv != null && !fallbackEnv.isEmpty()) {
            routes.add(buildCatchAllRoute(serviceName, fallbackEnv));
        }

        var vs = new VirtualServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName + "-vs")
                    .withNamespace(namespace)
                    .addToLabels(OperatorConstants.ISTIO_RESOURCE_LABEL_PREFIX + "/service", serviceName)
                    .addToLabels("app.kubernetes.io/managed-by", MANAGED_BY)
                .endMetadata()
                .withNewSpec()
                    .withHosts(List.of(serviceName))
                    .withHttp(routes)
                .endSpec()
                .build();

        apply(kubernetesClient.resources(VirtualService.class).inNamespace(namespace).resource(vs),
                "VirtualService", namespace, serviceName + "-vs");
    }

    private <T> void apply(io.fabric8.kubernetes.client.dsl.Resource<T> resource, String kind, String namespace, String name) {
        try {
            resource.createOr(r -> resource.update());
            log.debug("Created/updated {} {}/{}", kind, namespace, name);
        } catch (Exception e) {
            log.error("Failed {} {}/{}", kind, namespace, name, e);
            throw new RuntimeException("Failed to configure " + kind, e);
        }
    }

    private HTTPRoute buildEnvRoute(String serviceName, String env) {
        HTTPMatchRequest match = new HTTPMatchRequestBuilder()
                .withHeaders(Map.of(OperatorConstants.ENV_HEADER_NAME, new StringMatch(new StringMatchExact(env))))
                .build();
        return new HTTPRouteBuilder()
                .withMatch(List.of(match))
                .withRoute(List.of(new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder().withHost(serviceName).withSubset(env).build())
                        .withWeight(100)
                        .build()))
                .build();
    }

    private HTTPRoute buildCatchAllRoute(String serviceName, String fallbackEnv) {
        return new HTTPRouteBuilder()
                .withRoute(List.of(new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder().withHost(serviceName).withSubset(fallbackEnv).build())
                        .withWeight(100)
                        .build()))
                .build();
    }

}
