package com.zaeyi.serviceenv.constants;

/** Operator 常量 */
public class OperatorConstants {
    /** Deployment 声明加入的环境 */
    public static final String ENV_LABEL_KEY = "serviceenv.zaeyi.com/env";
    private static final String PREFIX = "serviceenv.zaeyi.com";
    public static final String APP_NAME_LABEL_KEY = "app.kubernetes.io/name";
    public static final String VERSION_LABEL_KEY = "app.kubernetes.io/version";
    public static final String ISTIO_INJECTION_LABEL = "istio-injection";
    public static final String ENV_HEADER_NAME = "x-service-env";
    /** Namespace 注解：兜底 fallback 的 env，如 base。无此注解则不配置 fallback。 */
    public static final String NAMESPACE_FALLBACK_ENV_ANNOTATION = PREFIX + "/fallback-env";
    public static final String ISTIO_RESOURCE_LABEL_PREFIX = PREFIX;
    public static final String DEFAULT_ENV = "default";

    private OperatorConstants() {}
}
