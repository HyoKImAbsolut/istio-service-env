package com.zaeyi.serviceenv.config;

import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvSpec;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.fabric8.kubernetes.api.model.AnyType;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.VersionInfo;
import io.fabric8.kubernetes.client.impl.KubernetesClientImpl;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.Arrays;
import java.util.List;

/**
 * GraalVM Native Image 反射与资源配置。
 * fabric8 kubernetes-client 与 java-operator-sdk 均未提供 Spring Boot Native 适配，
 * 参考 Quarkus kubernetes-client 扩展的 KubernetesClientProcessor 手动注册。
 */
@Configuration
@ImportRuntimeHints(NativeConfiguration.NativeHints.class)
@RegisterReflectionForBinding({
        ServiceEnv.class,
        ServiceEnvSpec.class,
        ServiceEnvStatus.class,
        VirtualService.class,
        DestinationRule.class,
        Pod.class,
        PodList.class,
        Deployment.class,
        DeploymentList.class,
        io.fabric8.kubernetes.api.model.Service.class,
        io.fabric8.kubernetes.api.model.ServiceList.class,
        io.fabric8.kubernetes.api.model.Namespace.class,
        io.fabric8.kubernetes.api.model.NamespaceList.class,
        io.fabric8.kubernetes.api.model.ConfigMap.class,
        io.fabric8.kubernetes.api.model.ConfigMapList.class,
        io.fabric8.kubernetes.api.model.Secret.class,
        io.fabric8.kubernetes.api.model.SecretList.class,
        io.fabric8.kubernetes.api.model.Event.class,
        io.fabric8.kubernetes.api.model.EventList.class,
        io.fabric8.kubernetes.api.model.apps.ReplicaSet.class,
        io.fabric8.kubernetes.api.model.apps.ReplicaSetList.class,
        io.fabric8.kubernetes.api.model.PersistentVolumeClaim.class,
        io.fabric8.kubernetes.api.model.PersistentVolumeClaimList.class,
        io.fabric8.kubernetes.api.model.ServiceAccount.class,
        io.fabric8.kubernetes.api.model.ServiceAccountList.class
})
public class NativeConfiguration {

    private static final MemberCategory[] CONSTRUCTOR_AND_METHODS = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS
    };

    private static final MemberCategory[] FULL_REFLECTION = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS
    };

    static class NativeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            registerCustomResourceTypes(hints);
            registerFabric8ClientTypes(hints);
            registerFabric8ImplTypes(hints);
            registerFabric8ModelTypes(hints);
            registerJosdkTypes(hints);
            registerResources(hints);
        }

        /**
         * Istio 类型需要 INVOKE_DECLARED_METHODS，@RegisterReflectionForBinding 的 binding 粒度不足。
         */
        private void registerCustomResourceTypes(RuntimeHints hints) {
            hints.reflection()
                    .registerType(VirtualService.class, hint -> hint.withMembers(FULL_REFLECTION))
                    .registerType(DestinationRule.class, hint -> hint.withMembers(FULL_REFLECTION));
        }

        private void registerFabric8ClientTypes(RuntimeHints hints) {
            hints.reflection()
                    .registerType(KubernetesClientImpl.class, hint -> hint.withMembers(FULL_REFLECTION))
                    .registerType(VersionInfo.class, hint -> hint.withMembers(FULL_REFLECTION))
                    .registerType(AnyType.class, hint -> hint.withMembers(FULL_REFLECTION))
                    .registerType(IntOrString.class, hint -> hint.withMembers(FULL_REFLECTION))
                    .registerType(KubernetesDeserializer.class, hint -> hint.withMembers(FULL_REFLECTION));
        }

        private void registerFabric8ImplTypes(RuntimeHints hints) {
            List<String> implClasses = Arrays.asList(
                    "io.fabric8.kubernetes.client.impl.Adapters",
                    "io.fabric8.kubernetes.client.impl.BaseClient",
                    "io.fabric8.kubernetes.client.impl.Handlers",
                    "io.fabric8.kubernetes.client.impl.InternalExtensionAdapter",
                    "io.fabric8.kubernetes.client.impl.NamespaceableResourceAdapter",
                    "io.fabric8.kubernetes.client.impl.ResourceHandler",
                    "io.fabric8.kubernetes.client.impl.ResourceHandlerImpl",
                    "io.fabric8.kubernetes.client.impl.ResourcedHasMetadataOperation",
                    "io.fabric8.kubernetes.client.impl.URLFromClusterIPImpl",
                    "io.fabric8.kubernetes.client.impl.URLFromEnvVarsImpl",
                    "io.fabric8.kubernetes.client.impl.URLFromIngressImpl",
                    "io.fabric8.kubernetes.client.impl.URLFromNodePortImpl",
                    "io.fabric8.kubernetes.client.impl.AdmissionRegistrationAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.ApiextensionsAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.AppsAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.AuthenticationAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.AuthorizationAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.AutoscalingAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.BatchAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.CertificatesAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.DiscoveryAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.ExtensionsAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.FlowControlAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.MetricAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.NetworkAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.PolicyAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.RbacAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.SchedulingAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.StorageAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1APIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1AdmissionRegistrationAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1ApiextensionsAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1AuthenticationAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1AuthorizationAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1AutoscalingAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1BatchAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1CertificatesAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1DiscoveryAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1NetworkAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1PolicyAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1SchedulingAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V1StorageAPIGroupClient",
                    "io.fabric8.kubernetes.client.impl.V2AutoscalingAPIGroupClient",
                    "io.fabric8.kubernetes.client.informers.impl.DefaultSharedIndexInformer",
                    "io.fabric8.kubernetes.client.informers.impl.ListerWatcher",
                    "io.fabric8.kubernetes.client.informers.impl.SharedInformerFactoryImpl",
                    "io.fabric8.kubernetes.client.informers.impl.cache.CacheImpl",
                    "io.fabric8.kubernetes.client.informers.impl.cache.ProcessorListener",
                    "io.fabric8.kubernetes.client.informers.impl.cache.ProcessorStore",
                    "io.fabric8.kubernetes.client.informers.impl.cache.Reflector",
                    "io.fabric8.kubernetes.client.informers.impl.cache.SharedProcessor",
                    "io.fabric8.kubernetes.client.vertx.VertxHttpClientFactory"
            );
            implClasses.forEach(className -> registerTypeIfPresent(hints, className, CONSTRUCTOR_AND_METHODS));
        }

        /**
         * 补充 @RegisterReflectionForBinding 无法覆盖的类型：
         * - Jackson Deserializer/Serializer（非绑定目标，Jackson 内部反射实例化）
         * - DefaultKubernetesResourceList（泛型列表反序列化）
         * - Config 等非 model 类型
         */
        private void registerFabric8ModelTypes(RuntimeHints hints) {
            List<String> supplementClasses = Arrays.asList(
                    "io.fabric8.kubernetes.api.model.Quantity$Deserializer",
                    "io.fabric8.kubernetes.api.model.Quantity$Serializer",
                    "io.fabric8.kubernetes.api.model.DefaultKubernetesResourceList",
                    "io.fabric8.kubernetes.client.Config",
                    "io.fabric8.kubernetes.client.ConfigBuilder",
                    "io.fabric8.kubernetes.client.Config$ExecCredential",
                    "io.fabric8.kubernetes.client.Config$ExecCredentialSpec",
                    "io.fabric8.kubernetes.client.Config$ExecCredentialStatus",
                    "io.fabric8.kubernetes.client.utils.OpenIDConnectionUtils$OpenIdConfiguration",
                    "io.fabric8.kubernetes.client.utils.OpenIDConnectionUtils$OAuthToken"
            );
            supplementClasses.forEach(className -> registerTypeIfPresent(hints, className, FULL_REFLECTION));
        }

        private void registerJosdkTypes(RuntimeHints hints) {
            List<String> josdkClasses = Arrays.asList(
                    "io.javaoperatorsdk.operator.processing.retry.GenericRetry",
                    "io.javaoperatorsdk.operator.processing.event.rate.LinearRateLimiter",
                    "io.javaoperatorsdk.operator.api.config.ControllerConfiguration",
                    "io.javaoperatorsdk.operator.api.config.ResolvedControllerConfiguration",
                    "io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration",
                    "io.javaoperatorsdk.operator.processing.event.rate.RateState",
                    "io.javaoperatorsdk.operator.processing.event.rate.RateLimiter",
                    "io.javaoperatorsdk.operator.processing.event.ReconciliationDispatcher",
                    "io.javaoperatorsdk.operator.processing.event.EventProcessor",
                    "io.javaoperatorsdk.operator.processing.event.ResourceStateManager",
                    "io.javaoperatorsdk.operator.processing.event.EventHandler",
                    "io.javaoperatorsdk.operator.processing.event.Event",
                    "io.javaoperatorsdk.operator.processing.event.PostExecutionControl",
                    "io.javaoperatorsdk.operator.processing.event.ResourceState",
                    "io.javaoperatorsdk.operator.processing.event.EventSources",
                    "io.javaoperatorsdk.operator.processing.GroupVersionKind",
                    "io.javaoperatorsdk.operator.processing.event.rate.RateLimited",
                    "java.util.TreeMap"
            );
            josdkClasses.forEach(className -> registerTypeIfPresent(hints, className, FULL_REFLECTION));
        }

        private void registerResources(RuntimeHints hints) {
            hints.resources()
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.http.HttpClient$Factory")
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource")
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.extension.ExtensionAdapter")
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.ServiceToURLProvider")
                    .registerPattern("META-INF/vertx/vertx-version.txt");
        }

        private void registerTypeIfPresent(RuntimeHints hints, String className, MemberCategory[] categories) {
            try {
                Class<?> clazz = Class.forName(className);
                hints.reflection().registerType(clazz, hint -> hint.withMembers(categories));
            } catch (ClassNotFoundException ignored) {
                // 依赖可能不存在 忽略
            }
        }
    }
}
