package com.zaeyi.serviceenv.config;

import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvSpec;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import io.fabric8.istio.api.networking.v1.DestinationRule;
import io.fabric8.istio.api.networking.v1.VirtualService;
import io.fabric8.kubernetes.api.model.AnyType;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.client.VersionInfo;
import io.fabric8.kubernetes.client.impl.KubernetesClientImpl;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
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
public class NativeConfiguration {

    private static final MemberCategory[] CONSTRUCTOR_AND_METHODS = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS
    };

    private static final MemberCategory[] FULL_REFLECTION = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.INVOKE_DECLARED_FIELDS
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

        private void registerCustomResourceTypes(RuntimeHints hints) {
            hints.reflection()
                    .registerType(ServiceEnv.class, hint -> hint.withMembers(CONSTRUCTOR_AND_METHODS))
                    .registerType(ServiceEnvSpec.class, hint -> hint.withMembers(CONSTRUCTOR_AND_METHODS))
                    .registerType(ServiceEnvStatus.class, hint -> hint.withMembers(CONSTRUCTOR_AND_METHODS))
                    .registerType(ServiceEnvStatus.ServiceInfo.class, hint -> hint.withMembers(CONSTRUCTOR_AND_METHODS))
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

        private void registerFabric8ModelTypes(RuntimeHints hints) {
            List<String> modelClasses = Arrays.asList(
                    "io.fabric8.kubernetes.api.model.Pod",
                    "io.fabric8.kubernetes.api.model.PodList",
                    "io.fabric8.kubernetes.api.model.PodSpec",
                    "io.fabric8.kubernetes.api.model.PodStatus",
                    "io.fabric8.kubernetes.api.model.PodListBuilder",
                    "io.fabric8.kubernetes.api.model.Service",
                    "io.fabric8.kubernetes.api.model.ServiceList",
                    "io.fabric8.kubernetes.api.model.ServiceSpec",
                    "io.fabric8.kubernetes.api.model.ServicePort",
                    "io.fabric8.kubernetes.api.model.Deployment",
                    "io.fabric8.kubernetes.api.model.DeploymentList",
                    "io.fabric8.kubernetes.api.model.DeploymentSpec",
                    "io.fabric8.kubernetes.api.model.DeploymentStatus",
                    "io.fabric8.kubernetes.api.model.Namespace",
                    "io.fabric8.kubernetes.api.model.NamespaceList",
                    "io.fabric8.kubernetes.api.model.ConfigMap",
                    "io.fabric8.kubernetes.api.model.ConfigMapList",
                    "io.fabric8.kubernetes.api.model.Secret",
                    "io.fabric8.kubernetes.api.model.SecretList",
                    "io.fabric8.kubernetes.api.model.Event",
                    "io.fabric8.kubernetes.api.model.EventList",
                    "io.fabric8.kubernetes.api.model.ObjectMeta",
                    "io.fabric8.kubernetes.api.model.OwnerReference",
                    "io.fabric8.kubernetes.api.model.ListMeta",
                    "io.fabric8.kubernetes.api.model.Status",
                    "io.fabric8.kubernetes.api.model.StatusDetails",
                    "io.fabric8.kubernetes.api.model.PreferredSchedulingTerm",
                    "io.fabric8.kubernetes.api.model.NodeSelector",
                    "io.fabric8.kubernetes.api.model.NodeSelectorTerm",
                    "io.fabric8.kubernetes.api.model.NodeSelectorRequirement",
                    "io.fabric8.kubernetes.api.model.Container",
                    "io.fabric8.kubernetes.api.model.ContainerPort",
                    "io.fabric8.kubernetes.api.model.EnvVar",
                    "io.fabric8.kubernetes.api.model.EnvVarSource",
                    "io.fabric8.kubernetes.api.model.PodTemplateSpec",
                    "io.fabric8.kubernetes.api.model.LabelSelector",
                    "io.fabric8.kubernetes.api.model.LabelSelectorRequirement",
                    "io.fabric8.kubernetes.api.model.ReplicationControllerSpec",
                    "io.fabric8.kubernetes.api.model.ReplicationControllerStatus",
                    "io.fabric8.kubernetes.api.model.ReplicaSet",
                    "io.fabric8.kubernetes.api.model.ReplicaSetList",
                    "io.fabric8.kubernetes.api.model.ReplicaSetSpec",
                    "io.fabric8.kubernetes.api.model.ReplicaSetStatus",
                    "io.fabric8.kubernetes.api.model.ConfigMapEnvSource",
                    "io.fabric8.kubernetes.api.model.ConfigMapKeySelector",
                    "io.fabric8.kubernetes.api.model.ExecAction",
                    "io.fabric8.kubernetes.api.model.HTTPGetAction",
                    "io.fabric8.kubernetes.api.model.HTTPHeader",
                    "io.fabric8.kubernetes.api.model.TCPSocketAction",
                    "io.fabric8.kubernetes.api.model.Probe",
                    "io.fabric8.kubernetes.api.model.Lifecycle",
                    "io.fabric8.kubernetes.api.model.LifecycleHandler",
                    "io.fabric8.kubernetes.api.model.ResourceRequirements",
                    "io.fabric8.kubernetes.api.model.Quantity",
                    "io.fabric8.kubernetes.api.model.SecurityContext",
                    "io.fabric8.kubernetes.api.model.Capabilities",
                    "io.fabric8.kubernetes.api.model.PodSecurityContext",
                    "io.fabric8.kubernetes.api.model.Volume",
                    "io.fabric8.kubernetes.api.model.VolumeMount",
                    "io.fabric8.kubernetes.api.model.EmptyDirVolumeSource",
                    "io.fabric8.kubernetes.api.model.ConfigMapVolumeSource",
                    "io.fabric8.kubernetes.api.model.SecretVolumeSource",
                    "io.fabric8.kubernetes.api.model.ConfigMapProjection",
                    "io.fabric8.kubernetes.api.model.SecretProjection",
                    "io.fabric8.kubernetes.api.model.DownwardAPIProjection",
                    "io.fabric8.kubernetes.api.model.DownwardAPIVolumeSource",
                    "io.fabric8.kubernetes.api.model.DownwardAPIVolumeFile",
                    "io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource",
                    "io.fabric8.kubernetes.api.model.PersistentVolumeClaim",
                    "io.fabric8.kubernetes.api.model.PersistentVolumeClaimList",
                    "io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec",
                    "io.fabric8.kubernetes.api.model.PersistentVolumeClaimStatus",
                    "io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource",
                    "io.fabric8.kubernetes.api.model.ServiceAccount",
                    "io.fabric8.kubernetes.api.model.ServiceAccountList",
                    "io.fabric8.kubernetes.api.model.LocalObjectReference",
                    "io.fabric8.kubernetes.api.model.Toleration",
                    "io.fabric8.kubernetes.api.model.Affinity",
                    "io.fabric8.kubernetes.api.model.PodAffinity",
                    "io.fabric8.kubernetes.api.model.PodAntiAffinity",
                    "io.fabric8.kubernetes.api.model.PodAffinityTerm",
                    "io.fabric8.kubernetes.api.model.WeightedPodAffinityTerm",
                    "io.fabric8.kubernetes.api.model.NodeAffinity",
                    "io.fabric8.kubernetes.api.model.NodeSelector",
                    "io.fabric8.kubernetes.api.model.NodeSelectorTerm",
                    "io.fabric8.kubernetes.api.model.NodeSelectorRequirement",
                    "io.fabric8.kubernetes.api.model.LoadBalancerStatus",
                    "io.fabric8.kubernetes.api.model.LoadBalancerIngress",
                    "io.fabric8.kubernetes.api.model.PodCondition",
                    "io.fabric8.kubernetes.api.model.PodConditionBuilder",
                    "io.fabric8.kubernetes.api.model.ContainerState",
                    "io.fabric8.kubernetes.api.model.ContainerStateRunning",
                    "io.fabric8.kubernetes.api.model.ContainerStateTerminated",
                    "io.fabric8.kubernetes.api.model.ContainerStateWaiting",
                    "io.fabric8.kubernetes.api.model.DeploymentCondition",
                    "io.fabric8.kubernetes.api.model.DeploymentConditionBuilder",
                    "io.fabric8.kubernetes.api.model.Condition",
                    "io.fabric8.kubernetes.client.Config",
                    "io.fabric8.kubernetes.client.ConfigBuilder",
                    "io.fabric8.kubernetes.client.Config$ExecCredential",
                    "io.fabric8.kubernetes.client.Config$ExecCredentialSpec",
                    "io.fabric8.kubernetes.client.Config$ExecCredentialStatus",
                    "io.fabric8.kubernetes.client.utils.OpenIDConnectionUtils$OpenIdConfiguration",
                    "io.fabric8.kubernetes.client.utils.OpenIDConnectionUtils$OAuthToken"
            );
            modelClasses.forEach(className -> registerTypeIfPresent(hints, className, FULL_REFLECTION));
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
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.ServiceToURLProvider");
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
