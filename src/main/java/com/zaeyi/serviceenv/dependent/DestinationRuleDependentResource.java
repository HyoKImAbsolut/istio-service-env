package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.crd.App;
import com.zaeyi.serviceenv.service.IstioConfigServiceHolder;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.ReconcileResult;
import io.javaoperatorsdk.operator.processing.dependent.Matcher;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import java.util.Set;

/**
 * DestinationRule DependentResource，ownerReference 由 SDK 自动添加。
 * JOSDK 通过反射创建，无法注入 Spring bean，故通过 {@link IstioConfigServiceHolder} 获取 IstioConfigService。
 */
public class DestinationRuleDependentResource extends CRUDKubernetesDependentResource<DestinationRule, App> {

    public DestinationRuleDependentResource() {
        super(DestinationRule.class);
    }

    @Override
    protected DestinationRule desired(App primary, Context<App> context) {
        Set<String> environmentNames = IstioConfigServiceHolder.get().computeEnvironmentNamesForApp(primary);
        if (environmentNames.isEmpty()) return null;

        String namespace = primary.getMetadata().getNamespace();
        String appName = primary.getSpec().getAppName();
        return IstioResourceBuilder.buildDestinationRule(namespace, appName, environmentNames);
    }

    @Override
    public Matcher.Result<DestinationRule> match(DestinationRule actual, App primary, Context<App> context) {
        var desired = desired(primary, context);
        if (desired == null) {
            return Matcher.Result.computed(false, null);
        }
        return super.match(actual, desired, primary, context);
    }

    @Override
    public ReconcileResult<DestinationRule> reconcile(App primary, Context<App> context) {
        var desired = desired(primary, context);
        var actualOpt = getSecondaryResource(primary, context);

        if (desired == null) {
            if (actualOpt.isPresent()) {
                context.getClient().resource(actualOpt.get()).delete();
            }
            return ReconcileResult.noOperation(null);
        }

        return super.reconcile(primary, context);
    }
}
