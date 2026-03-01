package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.App;
import com.zaeyi.serviceenv.service.IstioConfigService;
import com.zaeyi.serviceenv.util.AppNameUtil;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Istio DependentResource 基类，提供 envs 计算。
 * 当 envs 为空时，desired 返回 null，由子类或 reconcile 处理删除。
 */
public abstract class AbstractIstioDependentResource<R extends HasMetadata>
        extends CRUDKubernetesDependentResource<R, App> {

    protected final IstioConfigService istioConfigService;

    protected AbstractIstioDependentResource(Class<R> resourceType, IstioConfigService istioConfigService) {
        super(resourceType);
        this.istioConfigService = istioConfigService;
    }

    @Override
    protected abstract R desired(App primary, Context<App> context);

    protected Set<String> computeEnvs(App primary, Context<App> context) {
        if (primary.getMetadata() == null || primary.getSpec() == null) return Set.of();
        String namespace = primary.getMetadata().getNamespace();
        String appName = primary.getSpec().getAppName();
        if (namespace == null || appName == null || appName.isEmpty()) return Set.of();

        return context.getClient().resources(Deployment.class).inNamespace(namespace).list().getItems().stream()
                .filter(d -> d.getMetadata() != null && d.getMetadata().getLabels() != null)
                .filter(d -> appName.equals(AppNameUtil.getAppName(d)))
                .map(d -> d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY))
                .filter(Objects::nonNull)
                .filter(e -> !e.isEmpty())
                .filter(env -> istioConfigService.serviceEnvExists(namespace, env))
                .collect(Collectors.toSet());
    }

    protected String serviceIndexKey(String namespace, String appName) {
        return namespace + "#" + appName;
    }
}
