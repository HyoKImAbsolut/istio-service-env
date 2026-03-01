package com.zaeyi.serviceenv.crd;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

@Data
public class ServiceEnvSpec {
    @JsonProperty("envName")
    @JsonPropertyDescription("Unique environment name")
    private String envName;
    @JsonProperty("fallbackEnv")
    @JsonPropertyDescription("Fallback environment when service not in current env; each ServiceEnv can configure its own fallback")
    private String fallbackEnv;
    @JsonProperty("description")
    @JsonPropertyDescription("Description of this environment")
    private String description;
    @JsonProperty("enabled")
    @JsonPropertyDescription("Whether this environment is enabled")
    private Boolean enabled = true;
}
