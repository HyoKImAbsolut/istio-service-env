package com.zaeyi.serviceenv.config;

import com.zaeyi.serviceenv.service.IstioConfigService;
import com.zaeyi.serviceenv.service.IstioConfigServiceHolder;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时将 IstioConfigService 注入 Holder，供 JOSDK 反射创建的 DependentResource 使用。
 *
 * <p>保证顺序：AppReconciler 使用 @DependsOn("istioConfigServiceHolderConfig")，
 * 确保本 Config 的 @PostConstruct 先于 Reconciler 及 DependentResource 的创建执行。
 */
@Configuration
public class IstioConfigServiceHolderConfig {

    private final IstioConfigService istioConfigService;

    public IstioConfigServiceHolderConfig(IstioConfigService istioConfigService) {
        this.istioConfigService = istioConfigService;
    }

    @PostConstruct
    void init() {
        IstioConfigServiceHolder.set(istioConfigService);
    }
}
