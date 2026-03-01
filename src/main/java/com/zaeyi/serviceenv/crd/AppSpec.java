package com.zaeyi.serviceenv.crd;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/**
 * App CRD Spec.
 * appName 必须与 Deployment 的 app.kubernetes.io/name 一致，用于关联。
 */
@Data
public class AppSpec {
    @JsonProperty("appName")
    @JsonPropertyDescription("App name, must match Deployment's app.kubernetes.io/name")
    private String appName;

    @JsonProperty("description")
    @JsonPropertyDescription("Optional description of this app")
    private String description;
}
