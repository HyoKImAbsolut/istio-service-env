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

    public static DestinationRule buildDestinationRule(String namespace, String serviceName, Set<String> envs) {
        List<Subset> subsets = envs.stream()
                .map(env -> new SubsetBuilder()
                        .withName(env)
                        .withLabels(Map.of(OperatorConstants.ENV_LABEL_KEY, env))
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
            Set<String> envs, String fallbackEnv) {
        List<HTTPRoute> routes = envs.stream()
                .map(env -> buildEnvRoute(serviceName, env))
                .collect(Collectors.toCollection(ArrayList::new));

        if (fallbackEnv != null && !fallbackEnv.isEmpty()) {
            routes.add(buildCatchAllRoute(serviceName, fallbackEnv));
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

    private static HTTPRoute buildEnvRoute(String serviceName, String env) {
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

    private static HTTPRoute buildCatchAllRoute(String serviceName, String fallbackEnv) {
        return new HTTPRouteBuilder()
                .withRoute(List.of(new HTTPRouteDestinationBuilder()
                        .withDestination(new DestinationBuilder().withHost(serviceName).withSubset(fallbackEnv).build())
                        .withWeight(100)
                        .build()))
                .build();
    }
}
