package com.zaeyi.serviceenv.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GraalVM Native Image 反射与资源配置。
 * 按需扫描 kubernetes-client、istio-model、JOSDK operator 及本应用，批量注册反射 hint。
 */
@Configuration
@ImportRuntimeHints(NativeConfiguration.NativeHints.class)
public class NativeConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(NativeConfiguration.class);

    private static final MemberCategory[] FULL_REFLECTION = MemberCategory.values();

    private static final String[] PACKAGE_PATTERNS = {
            "classpath*:io/fabric8/kubernetes/**/*.class",
            "classpath*:io/fabric8/istio/**/*.class",
            "classpath*:io/javaoperatorsdk/operator/**/*.class",
            "classpath*:com/zaeyi/serviceenv/**/*.class"
    };

    private static final Pattern LAMBDA_PATTERN = Pattern.compile(".*\\$\\d+.*");

    static class NativeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
            Set<String> registered = new HashSet<>();

            for (String pattern : PACKAGE_PATTERNS) {
                try {
                    Resource[] resources = resolver.getResources(pattern);
                    for (Resource r : resources) {
                        String className = resourcePathToClassName(r);
                        if (className != null && !registered.contains(className)) {
                            hints.reflection().registerType(TypeReference.of(className), FULL_REFLECTION);
                            registered.add(className);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("AOT scan failed for pattern {} {}", pattern, e.getMessage());
                }
            }

            registerSpecificClasses(hints);
            registerResources(hints);
            LOG.info("AOT registered {} types for reflection", registered.size());
        }

        private String resourcePathToClassName(Resource resource) {
            try {
                String path = resource.getURL().toString();
                int idx = path.indexOf("!/");
                if (idx >= 0) {
                    path = path.substring(idx + 2);
                } else {
                    for (String prefix : new String[]{"io/fabric8/", "io/javaoperatorsdk/", "com/zaeyi/"}) {
                        int p = path.indexOf(prefix);
                        if (p >= 0) {
                            path = path.substring(p);
                            break;
                        }
                    }
                }
                path = path.replace("%5C", "/").replace("\\", "/");
                if (!path.endsWith(".class")) {
                    return null;
                }
                String className = path.replace('/', '.').replace(".class", "");
                if (LAMBDA_PATTERN.matcher(className).matches()) {
                    return null;
                }
                return className;
            } catch (Exception e) {
                return null;
            }
        }

        private void registerSpecificClasses(RuntimeHints hints) {
            hints.reflection().registerType(TypeReference.of("java.util.TreeMap"), FULL_REFLECTION);
        }

        private void registerResources(RuntimeHints hints) {
            hints.resources()
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.http.HttpClient$Factory")
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource")
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.extension.ExtensionAdapter")
                    .registerPattern("META-INF/services/io.fabric8.kubernetes.client.ServiceToURLProvider")
                    .registerPattern("META-INF/vertx/vertx-version.txt");
        }
    }
}
