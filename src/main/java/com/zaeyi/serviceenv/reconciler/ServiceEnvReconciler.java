package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.crd.ServiceEnv;
import com.zaeyi.serviceenv.crd.ServiceEnvStatus;
import com.zaeyi.serviceenv.service.IstioConfigService;
import com.zaeyi.serviceenv.service.ServiceDiscoveryService;
import io.javaoperatorsdk.operator.api.reconciler.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ServiceEnv资源的Reconciler
 * 实现Reconciler接口处理创建/更新，实现Cleaner接口处理删除
 */
@Component
@ControllerConfiguration
@Slf4j
@RequiredArgsConstructor
public class ServiceEnvReconciler implements Reconciler<ServiceEnv> {

    private final IstioConfigService istioConfigService;
    private final ServiceDiscoveryService serviceDiscoveryService;

    @Override
    public UpdateControl<ServiceEnv> reconcile(ServiceEnv resource, Context<ServiceEnv> context) {
        log.info("Reconciling ServiceEnv: {}/{}", resource.getMetadata().getNamespace(), 
                resource.getMetadata().getName());

        try {
            String envName = resource.getSpec().getEnvName();
            String namespace = resource.getMetadata().getNamespace();

            List<ServiceEnvStatus.ServiceInfo> services = 
                    serviceDiscoveryService.discoverServicesInEnv(namespace, envName);
            
            ServiceEnvStatus status = resource.getStatus();
            if (status == null) {
                status = new ServiceEnvStatus();
                resource.setStatus(status);
            }
            
            status.setServices(services);
            status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            if (resource.getSpec().getEnabled()) {
                istioConfigService.configureIstio(resource, services);
                status.setPhase("Active");
                status.setMessage("Environment is active with " + services.size() + " services");
                status.setIstioConfigured(true);
            } else {
                // 禁用环境时，Kubernetes会通过OwnerReferences自动删除Istio资源
                // 不需要手动清理
                status.setPhase("Disabled");
                status.setMessage("Environment is disabled");
                status.setIstioConfigured(false);
            }

            log.info("Successfully reconciled ServiceEnv: {}, services: {}", envName, services.size());
            return UpdateControl.patchStatus(resource);

        } catch (Exception e) {
            log.error("Error reconciling ServiceEnv: {}", resource.getMetadata().getName(), e);
            
            ServiceEnvStatus status = resource.getStatus();
            if (status == null) {
                status = new ServiceEnvStatus();
                resource.setStatus(status);
            }
            
            status.setPhase("Error");
            status.setMessage("Reconciliation failed: " + e.getMessage());
            status.setLastUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            return UpdateControl.patchStatus(resource);
        }
    }

    // 删除cleanup方法 - 现在使用OwnerReferences自动级联删除
    // Kubernetes会自动删除所有带有ownerReferences指向此ServiceEnv的Istio资源
}
