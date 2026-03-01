package com.zaeyi.serviceenv.service;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.reconciler.ServiceEnvReconciler.ReconcilerInput;

import io.fabric8.istio.api.api.networking.v1alpha3.*;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.fabric8.istio.api.networking.v1.DestinationRuleBuilder;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.fabric8.istio.api.networking.v1.VirtualServiceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 配置 Istio VirtualService 和 DestinationRule。
 * <p>主路由：为本环境服务创建 DR + VS（match x-service-env → subset）。
 * <p>Fallback：为「fallback 有、本环境无」的服务创建 VS（match x-service-env → fallback subset）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IstioConfigService {

    private final KubernetesClient kubernetesClient;

    /** 为本环境服务创建 DestinationRule 和主 VirtualService */
    public void configureIstio(ServiceEnv serviceEnv, ReconcilerInput input) {
        if (serviceEnv.getMetadata() == null || serviceEnv.getSpec() == null) {
            throw new IllegalArgumentException("ServiceEnv must have metadata and spec");
        }
        String envName = serviceEnv.getSpec().getEnvName();
        String namespace = serviceEnv.getMetadata().getNamespace();

        log.info("Configuring Istio for environment: {} in namespace: {} services: {}",
                envName, namespace, input.servicesInEnv().size());

        for (ServiceEnvStatus.ServiceInfo svc : input.servicesInEnv()) {
            String serviceName = svc.getName();
            if (serviceName == null || serviceName.isEmpty()) {
                continue;
            }
            Set<String> versions = input.serviceVersions().getOrDefault(serviceName, Set.of());
            createOrUpdateDestinationRule(serviceEnv, namespace, serviceName, envName, versions);
            createOrUpdateVirtualService(serviceEnv, namespace, serviceName, envName, envName);
        }

        log.info("Istio configuration completed for environment: {}", envName);
    }

    /** 为「fallback 有、本环境无」的服务创建 fallback VS，路由到 fallback subset */
    public void configureFallbackForSelf(ServiceEnv me, ReconcilerInput myInput, ReconcilerInput fallbackInput,
                                         String fallbackEnv) {
        if (fallbackEnv == null || fallbackEnv.isEmpty()) {
            return;
        }
        String myEnvName = me.getSpec().getEnvName();
        String namespace = me.getMetadata().getNamespace();

        Set<String> myServices = myInput.servicesInEnv().stream()
                .map(ServiceEnvStatus.ServiceInfo::getName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        int count = 0;
        for (ServiceEnvStatus.ServiceInfo svc : fallbackInput.servicesInEnv()) {
            String serviceName = svc.getName();
            if (serviceName == null || serviceName.isEmpty() || myServices.contains(serviceName)) {
                continue;
            }
            createOrUpdateVirtualService(me, namespace, serviceName, myEnvName, fallbackEnv);
            count++;
        }
        if (count > 0) {
            log.info("Configured fallback VirtualServices for {} services in env: {} -> {}",
                    count, myEnvName, fallbackEnv);
        }
    }

    // --- DR / VS 创建 ---

    private void createOrUpdateDestinationRule(ServiceEnv owner, String namespace, String serviceName,
                                                String envName, Set<String> versions) {
        String drName = String.format("%s-%s-dr", serviceName, envName);

        List<Subset> subsets = createSubsets(envName, versions);
        DestinationRule destinationRule = new DestinationRuleBuilder()
                .withMetadata(createMetadata(drName, namespace, envName, serviceName, owner))
                .withNewSpec()
                    .withHost(serviceName)
                    .withSubsets(subsets)
                .endSpec()
                .build();

        try {
            DestinationRule existing = kubernetesClient.resources(DestinationRule.class)
                    .inNamespace(namespace)
                    .withName(drName)
                    .get();

            if (existing != null) {
                kubernetesClient.resources(DestinationRule.class)
                        .inNamespace(namespace)
                        .resource(destinationRule)
                        .update();
                log.debug("Updated DestinationRule: {}/{}", namespace, drName);
            } else {
                kubernetesClient.resources(DestinationRule.class)
                        .inNamespace(namespace)
                        .resource(destinationRule)
                        .create();
                log.debug("Created DestinationRule: {}/{}", namespace, drName);
            }
        } catch (Exception e) {
            log.error("Error creating/updating DestinationRule: {}/{}", namespace, drName, e);
            throw new RuntimeException("Failed to configure DestinationRule", e);
        }
    }

    /** envName=匹配的 header，targetSubset=路由目标 subset */
    private void createOrUpdateVirtualService(ServiceEnv owner, String namespace, String serviceName,
                                               String envName, String targetSubset) {
        String vsName = String.format("%s-%s-vs", serviceName, envName);

        HTTPMatchRequest match = createHeaderMatch(envName);
        HTTPRoute route = new HTTPRouteBuilder()
                .withMatch(Collections.singletonList(match))
                .withRoute(Collections.singletonList(
                    new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder()
                            .withHost(serviceName)
                            .withSubset(targetSubset)
                            .build())
                        .withWeight(100)
                        .build()
                ))
                .build();

        VirtualService virtualService = new VirtualServiceBuilder()
                .withMetadata(createMetadata(vsName, namespace, envName, serviceName, owner))
                .withNewSpec()
                    .withHosts(Collections.singletonList(serviceName))
                    .withHttp(Collections.singletonList(route))
                .endSpec()
                .build();

        try {
            VirtualService existing = kubernetesClient.resources(VirtualService.class)
                    .inNamespace(namespace)
                    .withName(vsName)
                    .get();

            if (existing != null) {
                kubernetesClient.resources(VirtualService.class)
                        .inNamespace(namespace)
                        .resource(virtualService)
                        .update();
                log.debug("Updated VirtualService: {}/{}", namespace, vsName);
            } else {
                kubernetesClient.resources(VirtualService.class)
                        .inNamespace(namespace)
                        .resource(virtualService)
                        .create();
                log.debug("Created VirtualService: {}/{}", namespace, vsName);
            }
        } catch (Exception e) {
            log.error("Error creating/updating VirtualService: {}/{}", namespace, vsName, e);
            throw new RuntimeException("Failed to configure VirtualService", e);
        }
    }

    private ObjectMeta createMetadata(String name, String namespace, String envName,
                                      String serviceName, ServiceEnv owner) {
        if (owner.getMetadata() == null) {
            throw new IllegalArgumentException("ServiceEnv owner must have metadata");
        }
        Map<String, String> labels = new HashMap<>();
        labels.put(OperatorConstants.ISTIO_RESOURCE_LABEL_PREFIX + "/env", envName);
        labels.put(OperatorConstants.ISTIO_RESOURCE_LABEL_PREFIX + "/service", serviceName);
        labels.put("app.kubernetes.io/managed-by", "serviceenv-operator");

        return new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(labels)
                .withOwnerReferences(new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder()
                    .withApiVersion("serviceenv.zaeyi.com/v1")
                    .withKind("ServiceEnv")
                    .withName(owner.getMetadata().getName())
                    .withUid(owner.getMetadata().getUid())
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build())
                .build();
    }

    /** 匹配 header x-service-env: envName */
    private HTTPMatchRequest createHeaderMatch(String envName) {
        StringMatch stringMatch = new StringMatch(new StringMatchExact(envName));
        Map<String, StringMatch> headers = new HashMap<>();
        headers.put(OperatorConstants.ENV_HEADER_NAME, stringMatch);
        return new HTTPMatchRequestBuilder()
                .withHeaders(headers)
                .build();
    }

    private List<Subset> createSubsets(String envName, Set<String> versions) {
        List<Subset> subsets = new ArrayList<>();
        
        for (String version : versions) {
            Map<String, String> labels = new HashMap<>();
            labels.put(OperatorConstants.ENV_LABEL_KEY, envName);
            labels.put(OperatorConstants.VERSION_LABEL_KEY, version);

            Subset subset = new SubsetBuilder()
                    .withName(envName)
                    .withLabels(labels)
                    .build();
            
            subsets.add(subset);
        }

        if (subsets.isEmpty()) {
            Map<String, String> labels = new HashMap<>();
            labels.put(OperatorConstants.ENV_LABEL_KEY, envName);

            Subset subset = new SubsetBuilder()
                    .withName(envName)
                    .withLabels(labels)
                    .build();
            
            subsets.add(subset);
        }

        return subsets;
    }
}
