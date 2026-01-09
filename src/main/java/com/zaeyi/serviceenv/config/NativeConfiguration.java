package com.zaeyi.serviceenv.config;

import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvSpec;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import io.fabric8.istio.api.networking.v1beta1.DestinationRule;
import io.fabric8.istio.api.networking.v1beta1.VirtualService;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(NativeConfiguration.NativeHints.class)
public class NativeConfiguration {

    static class NativeHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.reflection()
                    .registerType(ServiceEnv.class, hint -> hint
                            .withMembers(
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_METHODS
                            ))
                    .registerType(ServiceEnvSpec.class, hint -> hint
                            .withMembers(
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_METHODS
                            ))
                    .registerType(ServiceEnvStatus.class, hint -> hint
                            .withMembers(
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_METHODS
                            ))
                    .registerType(ServiceEnvStatus.ServiceInfo.class, hint -> hint
                            .withMembers(
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_METHODS
                            ));

            hints.reflection()
                    .registerType(VirtualService.class, hint -> hint
                            .withMembers(
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_METHODS
                            ))
                    .registerType(DestinationRule.class, hint -> hint
                            .withMembers(
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                                    org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_METHODS
                            ));
        }
    }
}
