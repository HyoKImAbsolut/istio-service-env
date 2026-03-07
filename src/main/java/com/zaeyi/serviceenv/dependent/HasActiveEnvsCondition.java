package com.zaeyi.serviceenv.dependent;

import com.zaeyi.serviceenv.crd.App;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

/**
 * DependentResource 的前置条件：App.status.envs 非空才允许创建/更新 VS/DR。
 *
 * <p>当 envs 为空时（App 刚创建、还未 reconcile 出有效环境），
 * JOSDK 会跳过该 DependentResource，避免 desired() 返回 null 导致的框架异常。
 * 若 VS/DR 已存在，JOSDK 会自动将其删除（符合"无 env 则无路由"的语义）。
 */
public class HasActiveEnvsCondition implements Condition<Object, App> {

    @Override
    public boolean isMet(DependentResource<Object, App> dependentResource,
                         App primary,
                         Context<App> context) {
        if (primary.getStatus() == null) return false;
        var envs = primary.getStatus().getEnvs();
        return envs != null && !envs.isEmpty();
    }
}
