/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.hashicorp.vault;

import java.time.Duration;
import java.util.Objects;

import io.kroxylicious.kms.provider.hashicorp.vault.config.Config;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.plugin.Plugin;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of the {@link KmsService} interface backed by a remote instance of HashiCorp Vault.
 */
@Plugin(configType = Config.class)
public class VaultKmsService implements KmsService<Config, String, VaultEdek> {

    @SuppressWarnings("java:S3077") // Config is an immutable object
    private volatile Config config;

    @Override
    public void initialize(@NonNull Config config) {
        Objects.requireNonNull(config);
        if (this.config != null) {
            throw new IllegalStateException("KMS service is already initialized");
        }
        this.config = config;
    }

    @NonNull
    @Override
    public VaultKms buildKms() {
        if (config == null) {
            throw new IllegalStateException("KMS service not initialized");
        }
        return new VaultKms(config.vaultTransitEngineUrl(), config.vaultToken().getProvidedPassword(), Duration.ofSeconds(20),
                config.sslContext());
    }

}
