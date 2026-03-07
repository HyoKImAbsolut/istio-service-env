package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.crd.App;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import java.util.List;
import java.util.Set;

/**
 * DestinationRule DependentResource，ownerReference 由 SDK 自动添加。
 * 直接从 App.status.envs 读取有效环境集合，该值由 AppReconciler.reconcile() 在同一周期内写入。
 */
public class DestinationRuleDependentResource extends CRUDKubernetesDependentResource<DestinationRule, App> {

    public DestinationRuleDependentResource() {
        super(DestinationRule.class);
    }

    @Override
    protected DestinationRule desired(App primary, Context<App> context) {
        List<String> envList = primary.getStatus() != null ? primary.getStatus().getEnvs() : null;
        Set<String> environmentNames = envList != null ? Set.copyOf(envList) : Set.of();

        if (environmentNames.isEmpty()) return null;

        String namespace = primary.getMetadata().getNamespace();
        String appName = primary.getSpec().getAppName();
        return IstioResourceBuilder.buildDestinationRule(namespace, appName, environmentNames);
    }

}
