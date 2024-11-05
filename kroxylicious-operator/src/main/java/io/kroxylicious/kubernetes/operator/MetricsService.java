/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.kubernetes.operator;

import java.util.Optional;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;

/**
 * The Kube {@code Service} for exposing the proxy metrics.
 * This is named like {@code metrics-${KafkaProxy.metadata.name}}.
 */
@KubernetesDependent
public class MetricsService
        extends CRUDKubernetesDependentResource<Service, KafkaProxy> {

    public static final int METRICS_PORT = 9190;

    public MetricsService() {
        super(Service.class);
    }

    @Override
    public Optional<Service> getSecondaryResource(KafkaProxy primary, Context<KafkaProxy> context) {
        String serviceName = serviceName(primary);
        return context.eventSourceRetriever()
                .getResourceEventSourceFor(Service.class, "io.kroxylicious.kubernetes.operator.MetricsService")
                .getSecondaryResources(primary).stream()
                .filter(svc -> serviceName.equals(svc.getMetadata().getName()))
                .findFirst();
    }

    /**
     * @return The {@code metadata.name} of the desired {@code Service}.
     */
    static String serviceName(KafkaProxy primary) {
        return "metrics-" + primary.getMetadata().getName();
    }

    @Override
    public Service desired(
                           KafkaProxy primary,
                           Context<KafkaProxy> context) {
        // @formatter:off
        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName(primary))
                    .withNamespace(primary.getMetadata().getNamespace())
                .endMetadata()
                .withNewSpec()
                    .withSelector(ProxyDeployment.podLabels())
                    .addNewPort()
                        .withName("metrics")
                        .withPort(METRICS_PORT)
                        .withTargetPort(new IntOrString(METRICS_PORT))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
        // @formatter:on
    }
}
