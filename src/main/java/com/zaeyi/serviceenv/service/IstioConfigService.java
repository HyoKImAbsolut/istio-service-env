package com.zaeyi.serviceenv.service;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;

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

@Service
@Slf4j
@RequiredArgsConstructor
public class IstioConfigService {

    private final KubernetesClient kubernetesClient;
    private final ServiceDiscoveryService serviceDiscoveryService;

    public void configureIstio(ServiceEnv serviceEnv, List<ServiceEnvStatus.ServiceInfo> services) {
        String envName = serviceEnv.getSpec().getEnvName();
        String namespace = serviceEnv.getMetadata().getNamespace();
        String fallbackEnv = serviceEnv.getSpec().getFallbackEnv();

        log.info("Configuring Istio for environment: {} in namespace: {} with {} services", 
                envName, namespace, services.size());
        
        for (ServiceEnvStatus.ServiceInfo serviceInfo : services) {
            String serviceName = serviceInfo.getName();
            createOrUpdateDestinationRule(serviceEnv, namespace, serviceName, envName, serviceInfo.getVersion());
            createOrUpdateVirtualService(serviceEnv, namespace, serviceName, envName, fallbackEnv);
        }

        log.info("Istio configuration completed for environment: {}", envName);
    }


    private void createOrUpdateDestinationRule(ServiceEnv owner, String namespace, String serviceName, 
                                                String envName, String version) {
        String drName = String.format("%s-%s-dr", serviceName, envName);
        Set<String> versions = serviceDiscoveryService.getServiceVersions(namespace, serviceName, envName);

        DestinationRule destinationRule = new DestinationRuleBuilder()
                .withMetadata(createMetadata(drName, namespace, envName, serviceName, owner))
                .withNewSpec()
                    .withHost(serviceName)
                    .withSubsets(createSubsets(envName, versions))
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

    private void createOrUpdateVirtualService(ServiceEnv owner, String namespace, String serviceName, 
                                               String envName, String fallbackEnv) {
        String vsName = String.format("%s-%s-vs", serviceName, envName);
        List<HTTPRoute> httpRoutes = new ArrayList<>();
        
        // 主路由 - 使用Builder模式
        HTTPRoute primaryRoute = new HTTPRouteBuilder()
                .withMatch(Collections.singletonList(
                    new HTTPMatchRequest()
                ))
                .withRoute(Collections.singletonList(
                    new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder()
                            .withHost(serviceName)
                            .withSubset(envName)
                            .build())
                        .withWeight(100)
                        .build()
                ))
                .build();
        
        httpRoutes.add(primaryRoute);

        if (fallbackEnv != null && !fallbackEnv.isEmpty()) {
            // Fallback路由
            HTTPRoute fallbackRoute = new HTTPRouteBuilder()
                    .withMatch(Collections.singletonList(
                        new HTTPMatchRequest()
                    ))
                    .withRoute(Collections.singletonList(
                        new HTTPRouteDestinationBuilder()
                            .withDestination(new DestinationBuilder()
                                .withHost(serviceName)
                                .withSubset(fallbackEnv)
                                .build())
                            .withWeight(100)
                            .build()
                    ))
                    .build();
            
            httpRoutes.add(fallbackRoute);
        }

        VirtualService virtualService = new VirtualServiceBuilder()
                .withMetadata(createMetadata(vsName, namespace, envName, serviceName, owner))
                .withNewSpec()
                    .withHosts(Collections.singletonList(serviceName))
                    .withHttp(httpRoutes)
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
