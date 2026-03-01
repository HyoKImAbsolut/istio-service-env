package com.zaeyi.serviceenv.crd;

import lombok.Data;

import java.util.List;

/**
 * App CRD Status.
 */
@Data
public class AppStatus {
    private List<String> envs;
    private String phase;
    private String message;
    private String lastUpdateTime;
}
