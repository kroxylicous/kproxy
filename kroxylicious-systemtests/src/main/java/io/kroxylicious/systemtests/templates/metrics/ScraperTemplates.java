/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.templates.metrics;

import java.util.HashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;

import io.kroxylicious.systemtests.Constants;
import io.kroxylicious.systemtests.Environment;

public class ScraperTemplates {

    private ScraperTemplates() {
    }

    public static DeploymentBuilder scraperPod(String namespaceName, String podName) {
        Map<String, String> label = new HashMap<>();

        label.put(Constants.SCRAPER_LABEL_KEY, Constants.SCRAPER_LABEL_VALUE);
        label.put(Constants.DEPLOYMENT_TYPE, "Scraper");

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(podName)
                .withLabels(label)
                .withNamespace(namespaceName)
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .addToMatchLabels("app", podName)
                .addToMatchLabels(label)
                .endSelector()
                .withReplicas(1)
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", podName)
                .addToLabels(label)
                .endMetadata()
                .withNewSpec()
                .withContainers(
                        new ContainerBuilder()
                                .withName(podName)
                                .withImage(Environment.SCRAPER_IMAGE)
                                .withCommand("sleep")
                                .withArgs("infinity")
                                .withImagePullPolicy("IfNotPresent")
                                .withResources(new ResourceRequirementsBuilder()
                                        .addToRequests("memory", new Quantity("200M"))
                                        .build())
                                .build())
                .withImagePullSecrets(new LocalObjectReferenceBuilder()
                        .withName("regcred")
                        .build())
                .endSpec()
                .endTemplate()
                .endSpec();
    }
}