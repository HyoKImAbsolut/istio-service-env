package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import io.fabric8.istio.api.api.networking.v1alpha3.*;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.fabric8.istio.api.networking.v1.DestinationRuleBuilder;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.fabric8.istio.api.networking.v1.VirtualServiceBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 构建 Istio VS/DR 资源，不含 ownerReference（由 DependentResource 自动添加）。
 */
public final class IstioResourceBuilder {
    private static final String MANAGED_BY = "serviceenv-operator";

    private IstioResourceBuilder() {}

    public static DestinationRule buildDestinationRule(String namespace, String serviceName, Set<String> environmentNames) {
        List<Subset> subsets = environmentNames.stream()
                .map(environmentName -> new SubsetBuilder()
                        .withName(environmentName)
                        .withLabels(Map.of(OperatorConstants.ENV_LABEL_KEY, environmentName))
                        .build())
                .toList();

        return new DestinationRuleBuilder()
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
    }

    public static VirtualService buildVirtualService(String namespace, String serviceName,
            Set<String> environmentNames, String fallbackEnvironment) {
        List<HTTPRoute> routes = environmentNames.stream()
                .map(environmentName -> buildEnvRoute(serviceName, environmentName))
                .collect(Collectors.toCollection(ArrayList::new));

        if (fallbackEnvironment != null && !fallbackEnvironment.isEmpty()) {
            routes.add(buildCatchAllRoute(serviceName, fallbackEnvironment));
        }

        return new VirtualServiceBuilder()
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
    }

    private static HTTPRoute buildEnvRoute(String serviceName, String environmentName) {
        HTTPMatchRequest match = new HTTPMatchRequestBuilder()
                .withHeaders(Map.of(OperatorConstants.ENV_HEADER_NAME, new StringMatch(new StringMatchExact(environmentName))))
                .build();
        return new HTTPRouteBuilder()
                .withMatch(List.of(match))
                .withRoute(List.of(new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder().withHost(serviceName).withSubset(environmentName).build())
                        .withWeight(100)
                        .build()))
                .build();
    }

    private static HTTPRoute buildCatchAllRoute(String serviceName, String fallbackEnvironment) {
        return new HTTPRouteBuilder()
                .withRoute(List.of(new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder().withHost(serviceName).withSubset(fallbackEnvironment).build())
                        .withWeight(100)
                        .build()))
                .build();
    }
}
