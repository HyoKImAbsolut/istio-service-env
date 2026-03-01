package com.zaeyi.serviceenv.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.*;

/**
 * App 自定义资源定义。
 * VS 和 DR 归属于 App，app 名称从 Deployment/Service 的 app.kubernetes.io/name 读取。
 * metadata.name 即为 app 名称（与 K8s Service 名一致）。
 */
@Group("serviceenv.zaeyi.com")
@Version("v1")
@Kind("App")
@Plural("apps")
@ShortNames("app")
public class App extends CustomResource<AppSpec, AppStatus> implements Namespaced {
    @Override
    protected AppSpec initSpec() {
        return new AppSpec();
    }

    @Override
    protected AppStatus initStatus() {
        return new AppStatus();
    }
}
