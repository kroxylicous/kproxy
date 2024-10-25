/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.operator;

import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.javaoperatorsdk.operator.junit.LocallyRunOperatorExtension;

import io.kroxylicious.crapi.v1alpha1.KafkaProxy;
import io.kroxylicious.crapi.v1alpha1.KafkaProxySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ProxyReconcilerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyReconcilerTest.class);

    public static final String RESOURCE_NAME = "test-proxy";
    public static final String INITIAL_BOOTSTRAP = "my-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092";
    public static final String CHANGED_BOOTSTRAP = "your-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092";

    static KubernetesClient client;

    @BeforeAll
    static void checkKubeAvailable() {
        boolean haveKube;
        try {
            client = new KubernetesClientBuilder().build();
            client.namespaces().list();
            haveKube = true;
        }
        catch (KubernetesClientException e) {
            haveKube = false;
        }
        Assumptions.assumeTrue(haveKube, "Test requires a viable kube client");
    }

    @AfterAll
    static void closeKube() {
        if (client != null) {
            LOGGER.info("Kube client closed");
            client.close();
        }
    }

    @RegisterExtension
    static LocallyRunOperatorExtension extension = LocallyRunOperatorExtension.builder()
            .withKubernetesClient(client)
            .withReconciler(ProxyReconciler.class)
            .build();

    @Test
    void testCRUDOperations() {
        final var cr = extension.create(testResource());

        await().untilAsserted(() -> {
            var secret = extension.get(Secret.class, ProxyConfigSecret.secretName(cr));
            assertThat(secret)
                    .isNotNull()
                    .extracting(ProxyReconcilerTest::decodeSecretData, InstanceOfAssertFactories.map(String.class, String.class))
                    .containsKey(ProxyConfigSecret.configYamlKey(cr))
                    .extracting(map -> map.get(ProxyConfigSecret.configYamlKey(cr)), InstanceOfAssertFactories.STRING)
                    .contains(INITIAL_BOOTSTRAP)
                    .doesNotContain(CHANGED_BOOTSTRAP);

            var deployment = extension.get(Deployment.class, ProxyDeployment.deploymentName(cr));
            assertThat(deployment).isNotNull()
                    .extracting(dep -> dep.getSpec().getTemplate().getSpec().getVolumes(), InstanceOfAssertFactories.list(Volume.class))
                    .describedAs("Deployment template should mount the proxy config secret")
                    .anyMatch(vol -> vol.getSecret() != null
                            && vol.getSecret().getSecretName().equals(ProxyConfigSecret.secretName(cr)));

            var service = extension.get(Service.class, ProxyService.serviceName(cr));
            assertThat(service).isNotNull()
                    .extracting(svc -> svc.getSpec().getSelector())
                    .describedAs("Service's selector should select proxy pods")
                    .isEqualTo(ProxyDeployment.podLabels());
        });

        cr.getSpec().setBootstrapServers(CHANGED_BOOTSTRAP);
        final var changedCr = extension.replace(cr);

        await().untilAsserted(() -> {
            var secret = extension.get(Secret.class, ProxyConfigSecret.secretName(cr));
            assertThat(secret)
                    .isNotNull()
                    .extracting(ProxyReconcilerTest::decodeSecretData, InstanceOfAssertFactories.map(String.class, String.class))
                    .containsKey(ProxyConfigSecret.configYamlKey(changedCr))
                    .extracting(map -> map.get(ProxyConfigSecret.configYamlKey(changedCr)), InstanceOfAssertFactories.STRING)
                    .doesNotContain(INITIAL_BOOTSTRAP)
                    .contains(CHANGED_BOOTSTRAP);
        });

        extension.delete(changedCr);

        await().untilAsserted(() -> {
            var secret = extension.get(Secret.class, ProxyConfigSecret.secretName(cr));
            assertThat(secret).isNull();

            var deployment = extension.get(Deployment.class, ProxyDeployment.deploymentName(cr));
            assertThat(deployment).isNull();

            var service = extension.get(Service.class, ProxyService.serviceName(cr));
            assertThat(service).isNull();
        });
    }

    private static Map<String, String> decodeSecretData(Secret cm) {
        return cm.getData().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new String(Base64.getDecoder().decode(entry.getValue()))));
    }

    KafkaProxy testResource() {
        var resource = new KafkaProxy();
        resource.setMetadata(new ObjectMetaBuilder()
                .withName(RESOURCE_NAME)
                .build());
        resource.setSpec(new KafkaProxySpec());
        resource.getSpec().setBootstrapServers(INITIAL_BOOTSTRAP);
        return resource;
    }
}