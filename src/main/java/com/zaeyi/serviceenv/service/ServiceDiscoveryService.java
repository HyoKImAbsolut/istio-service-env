package com.zaeyi.serviceenv.service;

import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.constants.OperatorConstants;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ServiceDiscoveryService {

    private final KubernetesClient kubernetesClient;

    /**
     * 发现指定环境中的所有服务
     * 通过扫描Pod和Deployment的标签来识别加入环境的服务
     * @param namespace 要扫描的命名空间
     * @param envName 环境名称
     */
    public List<ServiceEnvStatus.ServiceInfo> discoverServicesInEnv(String namespace, String envName) {
        log.debug("Discovering services in environment: {} in namespace: {}", envName, namespace);

        Map<String, ServiceEnvStatus.ServiceInfo> serviceMap = new HashMap<>();

        // 查找标记了环境的Deployment
        List<Deployment> deployments = kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withLabel(OperatorConstants.ENV_LABEL_KEY, envName)
                .list()
                .getItems();

        for (Deployment deployment : deployments) {
            String serviceName = deployment.getMetadata().getName();
            Map<String, String> labels = deployment.getMetadata().getLabels();
            String version = labels != null ? labels.getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default") : "default";

            ServiceEnvStatus.ServiceInfo serviceInfo = new ServiceEnvStatus.ServiceInfo();
            serviceInfo.setName(serviceName);
            serviceInfo.setNamespace(namespace);
            serviceInfo.setVersion(version);
            
            Integer replicas = deployment.getStatus() != null ? 
                    deployment.getStatus().getReadyReplicas() : 0;
            serviceInfo.setPodCount(replicas != null ? replicas : 0);

            serviceMap.put(serviceName, serviceInfo);
        }

        // 同时检查直接标记环境的Pod（不通过Deployment）
        List<Pod> pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel(OperatorConstants.ENV_LABEL_KEY, envName)
                .list()
                .getItems();

        Map<String, List<Pod>> podsByService = pods.stream()
                .filter(pod -> pod.getMetadata() != null && pod.getMetadata().getLabels() != null
                        && pod.getMetadata().getLabels().containsKey("app"))
                .collect(Collectors.groupingBy(pod -> pod.getMetadata().getLabels().get("app")));

        for (Map.Entry<String, List<Pod>> entry : podsByService.entrySet()) {
            String serviceName = entry.getKey();
            List<Pod> servicePods = entry.getValue();

            if (!serviceMap.containsKey(serviceName)) {
                ServiceEnvStatus.ServiceInfo serviceInfo = new ServiceEnvStatus.ServiceInfo();
                serviceInfo.setName(serviceName);
                serviceInfo.setNamespace(namespace);
                serviceInfo.setPodCount(servicePods.size());

                Map<String, String> podLabels = servicePods.get(0).getMetadata() != null
                        ? servicePods.get(0).getMetadata().getLabels() : null;
                String version = podLabels != null ? podLabels.getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default") : "default";
                serviceInfo.setVersion(version);

                serviceMap.put(serviceName, serviceInfo);
            }
        }

        List<ServiceEnvStatus.ServiceInfo> services = new ArrayList<>(serviceMap.values());
        log.info("Found {} services in environment: {} in namespace: {}", services.size(), envName, namespace);
        
        return services;
    }

    public Set<String> getServiceVersions(String namespace, String serviceName, String envName) {
        List<Pod> pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("app", serviceName)
                .withLabel(OperatorConstants.ENV_LABEL_KEY, envName)
                .list()
                .getItems();

        return pods.stream()
                .map(pod -> pod.getMetadata().getLabels()
                        .getOrDefault(OperatorConstants.VERSION_LABEL_KEY, "default"))
                .collect(Collectors.toSet());
    }
}
