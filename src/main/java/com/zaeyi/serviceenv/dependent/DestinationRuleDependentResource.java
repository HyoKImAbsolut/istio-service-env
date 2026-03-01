package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.crd.App;
import com.zaeyi.serviceenv.service.IstioConfigService;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.ReconcileResult;
import io.javaoperatorsdk.operator.processing.dependent.Matcher;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DestinationRule DependentResource，ownerReference 由 SDK 自动添加。
 */
@Component
public class DestinationRuleDependentResource extends AbstractIstioDependentResource<DestinationRule> {

    public DestinationRuleDependentResource(IstioConfigService istioConfigService) {
        super(DestinationRule.class, istioConfigService);
    }

    @Override
    protected DestinationRule desired(App primary, Context<App> context) {
        Set<String> envs = computeEnvs(primary, context);
        if (envs.isEmpty()) return null;

        String namespace = primary.getMetadata().getNamespace();
        String appName = primary.getSpec().getAppName();
        return IstioResourceBuilder.buildDestinationRule(namespace, appName, envs);
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
