package com.zaeyi.serviceenv.crd;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.*;

/**
 * ServiceEnv自定义资源定义
 * 命名空间级别资源，管理当前namespace中的服务环境
 */
@Group("serviceenv.zaeyi.com")
@Version("v1")
@Kind("ServiceEnv")
@Plural("serviceenvs")
@ShortNames("senv")
public class ServiceEnv extends CustomResource<ServiceEnvSpec, ServiceEnvStatus> implements Namespaced {
    @Override
    protected ServiceEnvSpec initSpec() {
        return new ServiceEnvSpec();
    }
    @Override
    protected ServiceEnvStatus initStatus() {
        return new ServiceEnvStatus();
    }
}
