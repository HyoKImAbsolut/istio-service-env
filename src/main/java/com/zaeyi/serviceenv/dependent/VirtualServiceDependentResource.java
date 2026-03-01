package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.crd.App;
import com.zaeyi.serviceenv.service.IstioConfigServiceHolder;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.ReconcileResult;
import io.javaoperatorsdk.operator.processing.dependent.Matcher;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import java.util.Set;

/**
 * VirtualService DependentResource，ownerReference 由 SDK 自动添加。
 * JOSDK 通过反射创建，无法注入 Spring bean，故通过 {@link IstioConfigServiceHolder} 获取 IstioConfigService。
 */
public class VirtualServiceDependentResource extends CRUDKubernetesDependentResource<VirtualService, App> {

    public VirtualServiceDependentResource() {
        super(VirtualService.class);
    }

    @Override
    protected VirtualService desired(App primary, Context<App> context) {
        Set<String> environmentNames = IstioConfigServiceHolder.get().computeEnvironmentNamesForApp(primary);
        if (environmentNames.isEmpty()) return null;

        String namespace = primary.getMetadata().getNamespace();
        String appName = primary.getSpec().getAppName();
        String fallbackEnvironment = IstioConfigServiceHolder.get().getFallbackEnvFromNamespace(namespace);
        return IstioResourceBuilder.buildVirtualService(namespace, appName, environmentNames, fallbackEnvironment);
    }

    @Override
    public Matcher.Result<VirtualService> match(VirtualService actual, App primary, Context<App> context) {
        var desired = desired(primary, context);
        if (desired == null) {
            return Matcher.Result.computed(false, null);
        }
        return super.match(actual, desired, primary, context);
    }

    @Override
    public ReconcileResult<VirtualService> reconcile(App primary, Context<App> context) {
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
