package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.App;
import com.zaeyi.serviceenv.crd.AppStatus;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.dependent.DestinationRuleDependentResource;
import com.zaeyi.serviceenv.dependent.HasActiveEnvsCondition;
import com.zaeyi.serviceenv.dependent.VirtualServiceDependentResource;
import com.zaeyi.serviceenv.util.AppNameUtil;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * App 的主协调器。
 *
 * <p><b>职责：</b>
 * <ol>
 *   <li>监听 Deployment 和 ServiceEnv 的变化，触发受影响的 App 重新协调
 *   <li>计算当前 App 的有效环境集合（有对应 Deployment 且 ServiceEnv 启用）
 *   <li>将 activeEnvs 写入 App.status，DependentResource 在同一 reconcile 周期内直接从 status 读取，驱动 VS/DR 更新
 *   <li>将服务信息同步写入各 ServiceEnv 的 status
 * </ol>
 *
 * <p><b>VS/DR 的写入</b>由 {@code @Workflow} 声明的两个 DependentResource 自动完成，
 * 本 Reconciler 不直接操作 Istio 资源。
 */
@Component
@Workflow(dependents = {
        @Dependent(type = DestinationRuleDependentResource.class, reconcilePrecondition = HasActiveEnvsCondition.class),
        @Dependent(type = VirtualServiceDependentResource.class,  reconcilePrecondition = HasActiveEnvsCondition.class)
})
@ControllerConfiguration(informer = @Informer(namespaces = {Constants.WATCH_ALL_NAMESPACES}))
@Slf4j
@RequiredArgsConstructor
public class AppReconciler implements Reconciler<App> {

    // -----------------------------------------------------------------------
    // Deployment 索引键定义
    //   NAMESPACE_ENV_INDEX  : "namespace#envName"   → 按 env 查找该 env 下所有 Deployment
    //   NAMESPACE_APP_INDEX  : "namespace#appName"   → 按 app 查找该 app 下所有 env Deployment
    // -----------------------------------------------------------------------
    private static final String NAMESPACE_ENV_INDEX = "namespace-env";
    private static final String NAMESPACE_APP_INDEX = "namespace-app";

    private InformerEventSource<Deployment, App> deploymentEventSource;
    private InformerEventSource<ServiceEnv, App> serviceEnvEventSource;
    private EventSourceContext<App> eventSourceContext;

    // -----------------------------------------------------------------------
    // EventSource 注册：监听 Deployment 和 ServiceEnv，建立内存索引
    // -----------------------------------------------------------------------

    @Override
    public List<EventSource<?, App>> prepareEventSources(EventSourceContext<App> context) {
        this.eventSourceContext = context;
        deploymentEventSource = buildDeploymentEventSource(context);
        serviceEnvEventSource = buildServiceEnvEventSource(context);
        return List.of(deploymentEventSource, serviceEnvEventSource);
    }

    /** 构建 Deployment EventSource，注册两个内存索引，并配置事件到 App 的反向映射。 */
    private InformerEventSource<Deployment, App> buildDeploymentEventSource(EventSourceContext<App> context) {
        var config = InformerEventSourceConfiguration.from(Deployment.class, App.class)
                .withSecondaryToPrimaryMapper(this::deploymentToApps)
                .withNamespacesInheritedFromController()
                .build();
        var source = new InformerEventSource<>(config, context);
        source.addIndexers(Map.of(
                NAMESPACE_ENV_INDEX, this::indexDeploymentByEnv,
                NAMESPACE_APP_INDEX, this::indexDeploymentByApp));
        return source;
    }

    /** 构建 ServiceEnv EventSource，配置事件到 App 的反向映射，并过滤 status-only 变更。 */
    private InformerEventSource<ServiceEnv, App> buildServiceEnvEventSource(EventSourceContext<App> context) {
        var config = InformerEventSourceConfiguration.from(ServiceEnv.class, App.class)
                .withSecondaryToPrimaryMapper(this::serviceEnvToApps)
                .withNamespacesInheritedFromController()
                // 只有 spec 变更（generation 增加）才触发 App 重新协调，status 变更忽略
                .withOnUpdateFilter((oldSe, newSe) -> !Objects.equals(
                        oldSe  != null && oldSe.getMetadata()  != null ? oldSe.getMetadata().getGeneration()  : null,
                        newSe  != null && newSe.getMetadata()  != null ? newSe.getMetadata().getGeneration()  : null))
                .build();
        return new InformerEventSource<>(config, context);
    }

    // -----------------------------------------------------------------------
    // Deployment 索引函数：决定每个 Deployment 在索引中的 key
    // -----------------------------------------------------------------------

    /** 索引 key: "namespace#envName"，只索引带 env 标签的 Deployment。 */
    private List<String> indexDeploymentByEnv(Deployment d) {
        String env = getEnvLabel(d);
        if (env == null || d.getMetadata().getNamespace() == null) return List.of();
        return List.of(d.getMetadata().getNamespace() + "#" + env);
    }

    /** 索引 key: "namespace#appName"，只索引同时带 app 名和 env 标签的 Deployment。 */
    private List<String> indexDeploymentByApp(Deployment d) {
        String appName = AppNameUtil.getAppName(d);
        String env = getEnvLabel(d);
        if (appName == null || env == null || d.getMetadata().getNamespace() == null) return List.of();
        return List.of(d.getMetadata().getNamespace() + "#" + appName);
    }

    // -----------------------------------------------------------------------
    // 事件反向映射：次级资源变更 → 找到需要重新协调的 App
    // -----------------------------------------------------------------------

    /** Deployment 变更时，找到拥有该 app 的 App 资源。 */
    private Set<ResourceID> deploymentToApps(Deployment d) {
        String appName = AppNameUtil.getAppName(d);
        String env = getEnvLabel(d);
        if (appName == null || env == null || d.getMetadata() == null) return Set.of();
        return findAppsByAppName(d.getMetadata().getNamespace(), appName);
    }

    /** ServiceEnv 变更时，找到该 env 下所有有 Deployment 的 App 资源。 */
    private Set<ResourceID> serviceEnvToApps(ServiceEnv se) {
        if (se.getSpec() == null) return Set.of();
        String namespace = se.getMetadata().getNamespace();
        String envName = se.getSpec().getEnvName();
        if (envName == null || envName.isEmpty()) return Set.of();

        return deploymentEventSource.byIndex(NAMESPACE_ENV_INDEX, namespace + "#" + envName)
                .stream()
                .map(AppNameUtil::getAppName)
                .filter(Objects::nonNull)
                .flatMap(appName -> findAppsByAppName(namespace, appName).stream())
                .collect(Collectors.toSet());
    }

    /** 从 primary cache（App informer 内存缓存）按 appName 查找 App，不发 API 请求。 */
    private Set<ResourceID> findAppsByAppName(String namespace, String appName) {
        if (eventSourceContext == null) return Set.of();
        return eventSourceContext.getPrimaryCache()
                .list(namespace, a -> a.getSpec() != null && appName.equals(a.getSpec().getAppName()))
                .map(a -> new ResourceID(a.getMetadata().getName(), a.getMetadata().getNamespace()))
                .collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------------
    // 主协调流程
    // -----------------------------------------------------------------------

    @Override
    public UpdateControl<App> reconcile(App app, Context<App> context) {
        String namespace = app.getMetadata().getNamespace();
        String appName   = app.getSpec().getAppName();

        if (appName == null || appName.isEmpty()) {
            log.warn("App {}/{} spec.appName is empty skipping", namespace, app.getMetadata().getName());
            return UpdateControl.noUpdate();
        }

        try {
            // 1. 计算有效 env 集合，写入 App.status（DependentResource 在同一周期内直接从 status 读取）
            Set<String> activeEnvs = resolveActiveEnvs(namespace, appName);

            // 2. 更新 App 自身的 status
            log.debug("App reconciled {}/{} activeEnvs {}", namespace, appName, activeEnvs);
            return UpdateControl.patchStatus(applyActiveStatus(app, activeEnvs));

        } catch (Exception e) {
            log.error("App reconcile failed {}/{}", namespace, appName, e);
            return UpdateControl.patchStatus(applyErrorStatus(app, e));
        }
    }

    // -----------------------------------------------------------------------
    // 步骤一：计算有效环境集合
    // -----------------------------------------------------------------------

    /**
     * 从 Deployment 内存索引中提取该 app 已部署的 env 标签，
     * 过滤掉没有对应 ServiceEnv 或 ServiceEnv 已禁用的 env。
     * 全程走内存缓存，不发 K8s API 请求。
     */
    private Set<String> resolveActiveEnvs(String namespace, String appName) {
        return deploymentEventSource.byIndex(NAMESPACE_APP_INDEX, namespace + "#" + appName)
                .stream()
                .map(this::getEnvLabel)
                .filter(env -> env != null && !env.isEmpty())
                .filter(env -> isServiceEnvEnabled(namespace, env))
                .collect(Collectors.toSet());
    }

    /**
     * 通过 serviceEnvEventSource 本地缓存判断指定 env 是否存在且启用，不发 API 请求。
     *
     * <p>本项目约定 ServiceEnv 的 metadata.name == spec.envName，因此可以直接用 envName
     * 构造 {@link ResourceID} 定位缓存中的对象，语义等价于 kubernetesClient.withName(envName).get()。
     */
    private boolean isServiceEnvEnabled(String namespace, String envName) {
        return serviceEnvEventSource.get(new ResourceID(envName, namespace))
                .map(se -> se.getSpec() != null
                        && envName.equals(se.getSpec().getEnvName())
                        && Boolean.TRUE.equals(se.getSpec().getEnabled()))
                .orElse(false);
    }

    // -----------------------------------------------------------------------
    // 步骤二：构建 App status
    // -----------------------------------------------------------------------

    private App applyActiveStatus(App app, Set<String> activeEnvs) {
        AppStatus status = app.getStatus() != null ? app.getStatus() : new AppStatus();
        status.setEnvs(new ArrayList<>(activeEnvs));
        status.setPhase("Active");
        status.setMessage("App " + app.getSpec().getAppName() + " with envs " + activeEnvs);
        status.setLastUpdateTime(now());
        app.setStatus(status);
        return app;
    }

    private App applyErrorStatus(App app, Exception e) {
        AppStatus status = app.getStatus() != null ? app.getStatus() : new AppStatus();
        status.setPhase("Error");
        status.setMessage("Reconciliation failed: " + e.getMessage());
        status.setLastUpdateTime(now());
        app.setStatus(status);
        return app;
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    /** 读取 Deployment 的 env 标签，metadata 或 labels 为空时返回 null。 */
    private String getEnvLabel(Deployment d) {
        if (d.getMetadata() == null || d.getMetadata().getLabels() == null) return null;
        return d.getMetadata().getLabels().get(OperatorConstants.ENV_LABEL_KEY);
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
