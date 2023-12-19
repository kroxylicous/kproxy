/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * The type Environment.
 */
public class Environment {

    private Environment() {
    }

    /**
     * Env. variables names
     */
    private static final String KAFKA_VERSION_ENV = "KAFKA_VERSION";
    private static final String KROXY_VERSION_ENV = "KROXYLICIOUS_VERSION";
    private static final String KROXY_IMAGE_REPO_ENV = "KROXYLICIOUS_IMAGE_REPO";
    private static final String STRIMZI_URL_ENV = "STRIMZI_URL";
    private static final String SKIP_TEARDOWN_ENV = "SKIP_TEARDOWN";

    /**
     * The kafka version default value
     */
    public static final String KAFKA_VERSION_DEFAULT = "3.6.0";

    /**
     * The kroxy version default value
     */
    public static final String KROXY_VERSION_DEFAULT;

    static {
        var p = new Properties();
        try (var stream = Environment.class.getResourceAsStream("/metadata.properties");) {
            Objects.requireNonNull(stream, "/metadata.properties absent");
            p.load(stream);
            var version = p.getProperty("kroxylicious.version");
            Objects.requireNonNull(version, "kroxylicious version key absent in metadata.properties");
            KROXY_VERSION_DEFAULT = version;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    /**
     * The url where kroxylicious image lives to be downloaded.
     */
    public static final String KROXY_IMAGE_REPO_DEFAULT = "quay.io/kroxylicious/kroxylicious-developer";

    /**
     * The strimzi installation url for kubernetes.
     */
    public static final String STRIMZI_URL_DEFAULT = "https://strimzi.io/install/latest?namespace=" + Constants.KROXY_DEFAULT_NAMESPACE;
    /**
     * The default value for skipping the teardown locally.
     */
    public static final String SKIP_TEARDOWN_DEFAULT = "false";

    /**
     * KAFKA_VERSION env variable assignment
     */
    public static final String KAFKA_VERSION = getOrDefault(KAFKA_VERSION_ENV, KAFKA_VERSION_DEFAULT);

    /**
     * KROXY_VERSION env variable assignment
     */
    public static final String KROXY_VERSION = getOrDefault(KROXY_VERSION_ENV, KROXY_VERSION_DEFAULT);
    /**
     * STRIMZI_URL env variable assignment
     */
    public static final String STRIMZI_URL = getOrDefault(STRIMZI_URL_ENV, STRIMZI_URL_DEFAULT);
    /**
     * KROXY_IMAGE_REPO env variable assignment
     */
    public static final String KROXY_IMAGE_REPO = getOrDefault(KROXY_IMAGE_REPO_ENV, KROXY_IMAGE_REPO_DEFAULT);
    /**
     * SKIP_TEARDOWN env variable assignment.
     */
    public static final String SKIP_TEARDOWN = getOrDefault(SKIP_TEARDOWN_ENV, SKIP_TEARDOWN_DEFAULT);

    private static String getOrDefault(String varName, String defaultValue) {
        return getOrDefault(varName, String::toString, defaultValue);
    }

    private static <T> T getOrDefault(String varName, Function<String, T> converter, T defaultValue) {
        return System.getenv(varName) != null ? converter.apply(System.getenv(varName)) : defaultValue;
    }
}
