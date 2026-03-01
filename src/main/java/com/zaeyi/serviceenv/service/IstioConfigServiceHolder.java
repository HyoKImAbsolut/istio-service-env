package com.zaeyi.serviceenv.service;

/**
 * IstioConfigService 的静态持有者。
 *
 * <p>JOSDK 对 @Workflow 的 DependentResource 通过反射+无参构造器创建，不会从 Spring 容器获取，
 * 故无法注入 IstioConfigService。见 <a href="https://github.com/operator-framework/java-operator-sdk/issues/2166">#2166</a>。
 * 此处用 Holder 在 Spring 启动时由 {@link IstioConfigServiceHolderConfig} 注入，供 DependentResource 使用。
 */
public final class IstioConfigServiceHolder {

    private static volatile IstioConfigService instance;

    private IstioConfigServiceHolder() {}

    public static void set(IstioConfigService service) {
        instance = service;
    }

    public static IstioConfigService get() {
        IstioConfigService s = instance;
        if (s == null) {
            throw new IllegalStateException("IstioConfigServiceHolder not initialized. Ensure IstioConfigServiceHolderConfig runs on startup.");
        }
        return s;
    }
}
