/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.installation.kroxy;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.systemtests.Constants;
import io.kroxylicious.systemtests.Environment;
import io.kroxylicious.systemtests.resources.manager.ResourceManager;
import io.kroxylicious.systemtests.templates.KroxyConfigTemplates;
import io.kroxylicious.systemtests.templates.KroxyDeploymentTemplates;
import io.kroxylicious.systemtests.templates.KroxyServiceTemplates;
import io.kroxylicious.systemtests.utils.DeploymentUtils;

/**
 * The type Kroxy.
 */
public class Kroxy {
    private static final Logger LOGGER = LoggerFactory.getLogger(Kroxy.class);
    private final String deploymentNamespace;
    private String containerImage = "quay.io/kroxylicious/kroxylicious-developer:" + Environment.KROXY_VERSION;
    private final ResourceManager resourceManager = ResourceManager.getInstance();

    /**
     * Instantiates a new Kroxy.
     *
     * @param deploymentNamespace the deployment namespace
     */
    public Kroxy(String deploymentNamespace) {
        this.deploymentNamespace = deploymentNamespace;
        if (!Objects.equals(Environment.QUAY_ORG, Environment.QUAY_ORG_DEFAULT)) {
            containerImage = "quay.io/" + Environment.QUAY_ORG + "/kroxylicious:" + Environment.KROXY_VERSION;
        }
    }

    /**
     * Deploy - Port per broker plain config
     * @param testInfo the test info
     */
    public void deployPortPerBrokerPlain(TestInfo testInfo) {
        LOGGER.info("Deploy Kroxy in {} namespace", deploymentNamespace);
        resourceManager.createResourceWithWait(testInfo, KroxyConfigTemplates.defaultKroxyConfig(deploymentNamespace).build());
        resourceManager.createResourceWithWait(testInfo, KroxyDeploymentTemplates.defaultKroxyDeployment(deploymentNamespace, containerImage).build());
        resourceManager.createResourceWithoutWait(testInfo, KroxyServiceTemplates.defaultKroxyService(deploymentNamespace).build());
    }

    /**
     * Delete.
     * @param testInfo the test info
     * @throws IOException the io exception
     */
    public void delete(TestInfo testInfo) throws IOException {
        LOGGER.info("Deleting Kroxy in {} namespace", deploymentNamespace);
        resourceManager.deleteResources(testInfo);
        DeploymentUtils.waitForDeploymentDeletion(deploymentNamespace, Constants.KROXY_DEPLOYMENT_NAME);
    }
}
