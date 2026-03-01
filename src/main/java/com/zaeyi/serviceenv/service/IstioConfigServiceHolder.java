package com.zaeyi.serviceenv.service;

import org.springframework.context.annotation.Configuration;

/**
 * IstioConfigService 的静态持有者。
 *
 * <p>JOSDK 对 @Workflow 的 DependentResource 通过反射+无参构造器创建，不会从 Spring 容器获取，
 * 故无法注入 IstioConfigService。见 <a href="https://github.com/operator-framework/java-operator-sdk/issues/2166">#2166</a>。
 * 本类作为 @Configuration，构造时注入 IstioConfigService 到静态 instance，供 DependentResource 使用。
 */
@Configuration
public class IstioConfigServiceHolder {

    private static volatile IstioConfigService instance;

    public IstioConfigServiceHolder(IstioConfigService istioConfigService) {
        instance = istioConfigService;
    }

    public static IstioConfigService get() {
        IstioConfigService s = instance;
        if (s == null) {
            throw new IllegalStateException("IstioConfigServiceHolder not initialized. Ensure it runs on startup.");
        }
        return s;
    }
}
