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
    public static final String ISTIO_RESOURCE_LABEL_PREFIX = PREFIX;
    public static final String DEFAULT_ENV = "default";

    private OperatorConstants() {}
}
