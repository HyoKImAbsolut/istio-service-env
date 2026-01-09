package com.zaeyi.serviceenv.reconciler;

import com.zaeyi.serviceenv.constants.OperatorConstants;
import com.zaeyi.serviceenv.crd.ServiceEnv;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ControllerConfiguration
@Slf4j
@RequiredArgsConstructor
public class DeploymentReconciler implements Reconciler<Deployment> {

    private final KubernetesClient kubernetesClient;

    @Override
    public UpdateControl<Deployment> reconcile(Deployment deployment, Context<Deployment> context) {
        Map<String, String> labels = deployment.getMetadata().getLabels();
        
        if (labels == null || !labels.containsKey(OperatorConstants.ENV_LABEL_KEY)) {
            return UpdateControl.noUpdate();
        }

        String envName = labels.get(OperatorConstants.ENV_LABEL_KEY);
        String namespace = deployment.getMetadata().getNamespace();

        log.info("Deployment {}/{} joined environment: {}", 
                namespace, deployment.getMetadata().getName(), envName);

        triggerServiceEnvReconcile(namespace, envName);
        return UpdateControl.noUpdate();
    }

    private void triggerServiceEnvReconcile(String namespace, String envName) {
        try {
            ServiceEnv serviceEnv = kubernetesClient.resources(ServiceEnv.class)
                    .inNamespace(namespace)
                    .withName(envName)
                    .get();

            if (serviceEnv != null) {
                Map<String, String> annotations = serviceEnv.getMetadata().getAnnotations();
                if (annotations == null) {
                    annotations = new java.util.HashMap<>();
                }
                annotations.put("serviceenv.zaeyi.com/last-deployment-update", 
                        String.valueOf(System.currentTimeMillis()));
                serviceEnv.getMetadata().setAnnotations(annotations);

                kubernetesClient.resources(ServiceEnv.class)
                        .inNamespace(namespace)
                        .withName(envName)
                        .patch(serviceEnv);

                log.debug("Triggered reconcile for ServiceEnv: {}/{}", namespace, envName);
            } else {
                log.warn("ServiceEnv {}/{} not found for deployment update", namespace, envName);
            }
        } catch (Exception e) {
            log.error("Error triggering ServiceEnv reconcile for {}/{}", namespace, envName, e);
        }
    }
}
