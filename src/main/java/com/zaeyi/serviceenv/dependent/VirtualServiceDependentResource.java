package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.App;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.fabric8.kubernetes.api.model.Namespace;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * VirtualService DependentResource，ownerReference 由 SDK 自动添加。
 * 直接从 App.status.envs 读取有效环境集合，该值由 AppReconciler.reconcile() 在同一周期内写入。
 *
 * <p>reconcilePrecondition 在 AppReconciler 的 @Workflow/@Dependent 注解中配置为 {@link HasActiveEnvsCondition}，
 * 保证只有 envs 非空时才调用 desired()，避免 status 尚未就绪时的异常。
 */
public class VirtualServiceDependentResource extends CRUDKubernetesDependentResource<VirtualService, App> {

    private static final Logger log = LoggerFactory.getLogger(VirtualServiceDependentResource.class);

    public VirtualServiceDependentResource() {
        super(VirtualService.class);
    }

    @Override
    protected VirtualService desired(App primary, Context<App> context) {
        List<String> envList = primary.getStatus().getEnvs();
        Set<String> environmentNames = Set.copyOf(envList);

        String namespace = primary.getMetadata().getNamespace();
        String appName = primary.getSpec().getAppName();
        String fallbackEnvironment = getFallbackEnv(namespace, context);
        return IstioResourceBuilder.buildVirtualService(namespace, appName, environmentNames, fallbackEnvironment);
    }

    /** 从 namespace 注解读取 fallback env，无注解时返回 null（VS 不生成 catch-all 路由）。 */
    private String getFallbackEnv(String namespace, Context<App> context) {
        try {
            Namespace ns = context.getClient().namespaces().withName(namespace).get();
            if (ns != null && ns.getMetadata() != null && ns.getMetadata().getAnnotations() != null) {
                return ns.getMetadata().getAnnotations().get(OperatorConstants.NAMESPACE_FALLBACK_ENV_ANNOTATION);
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to read fallback-env annotation from namespace {} VirtualService will have no catch-all route: {}",
                    namespace, e.getMessage());
            return null;
        }
    }
}
