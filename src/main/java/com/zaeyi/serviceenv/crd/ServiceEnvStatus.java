package com.zaeyi.serviceenv.crd;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data
public class ServiceEnvStatus {
    @JsonProperty("services")
    private List<ServiceInfo> services = new ArrayList<>();
    @JsonProperty("phase")
    private String phase = "Pending";
    @JsonProperty("message")
    private String message;
    @JsonProperty("lastUpdateTime")
    private String lastUpdateTime;
    @JsonProperty("istioConfigured")
    private Boolean istioConfigured = false;
    @Data
    public static class ServiceInfo {
        @JsonProperty("name")
        private String name;
        @JsonProperty("namespace")
        private String namespace;
        @JsonProperty("version")
        private String version;
    }
}
