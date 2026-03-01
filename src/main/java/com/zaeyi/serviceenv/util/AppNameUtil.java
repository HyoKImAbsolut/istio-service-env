package com.zaeyi.serviceenv.util;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import io.fabric8.kubernetes.api.model.apps.Deployment;

/**
 * 从 Deployment 读取 app 名称。
 * 仅从 Pod template labels 的 app.kubernetes.io/name 读取（K8s 标准）。
 */
public final class AppNameUtil {
    private AppNameUtil() {}

    public static String getAppName(Deployment d) {
        if (d == null || d.getSpec() == null || d.getSpec().getTemplate() == null
                || d.getSpec().getTemplate().getMetadata() == null
                || d.getSpec().getTemplate().getMetadata().getLabels() == null) {
            return null;
        }
        String name = d.getSpec().getTemplate().getMetadata().getLabels().get(OperatorConstants.APP_NAME_LABEL_KEY);
        return (name != null && !name.isEmpty()) ? name : null;
    }
}
