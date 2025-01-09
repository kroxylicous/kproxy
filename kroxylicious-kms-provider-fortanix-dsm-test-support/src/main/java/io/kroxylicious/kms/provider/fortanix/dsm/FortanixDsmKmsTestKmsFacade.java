/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.fortanix.dsm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.kms.provider.fortanix.dsm.config.ApiKeySessionProviderConfig;
import io.kroxylicious.kms.provider.fortanix.dsm.config.Config;
import io.kroxylicious.kms.provider.fortanix.dsm.model.KeyResponse;
import io.kroxylicious.kms.provider.fortanix.dsm.model.SecurityObjectDescriptor;
import io.kroxylicious.kms.provider.fortanix.dsm.model.SecurityObjectRequest;
import io.kroxylicious.kms.provider.fortanix.dsm.model.SecurityObjectResponse;
import io.kroxylicious.kms.provider.fortanix.dsm.session.ApiKeySessionProvider;
import io.kroxylicious.kms.service.KmsException;
import io.kroxylicious.kms.service.TestKekManager;
import io.kroxylicious.kms.service.TestKekManager.AlreadyExistsException;
import io.kroxylicious.kms.service.TestKmsFacade;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.proxy.config.secret.InlinePassword;

import edu.umd.cs.findbugs.annotations.NonNull;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Test facade backed by the Fortanix DSM SaaS service.
 * <br/>
 * To use activate this facade it is necessary to set the following three environment variables.
 * <br/>
 * <table>
 *     <caption>Environment variables required for Fortanix DSM test facade</caption>
 *     <tr>
 *         <td>FORTANIX_API_ENDPOINT</td>
 *         <td>Endpoint URL of the Fortanix e.g. <a href="https://api.uk.smartkey.io/">https://api.uk.smartkey.io/</a></td>
 *     </tr>
 *     <tr>
 *         <td>FORTANIX_ADMIN_API_KEY</td>
 *         <td>API key for the Fortanix - this will be used by the test facade. This user needs
 *             privileges to perform actions like create/delete/rotate keys.</td>
 *     </tr>
 *     <tr>
 *         <td>FORTANIX_API_KEY</td>
 *         <td>API key for the Fortanix - this will be used by the production code.</td>
 *     </tr>
 * </table>
 */
class FortanixDsmKmsTestKmsFacade implements TestKmsFacade<Config, String, FortanixDsmKmsEdek> {
    private static final Logger LOGGER = LoggerFactory.getLogger(FortanixDsmKmsTestKmsFacade.class);
    private static final String TEST_RUN_INSTANCE_ID_METADATA_KEY = "testInstance";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Optional<URI> FORTANIX_API_ENDPOINT = Optional.ofNullable(System.getenv().get("FORTANIX_API_ENDPOINT")).map(URI::create);
    private static final Optional<String> FORTANIX_ADMIN_API_KEY = Optional.ofNullable(System.getenv().get("FORTANIX_ADMIN_API_KEY"));
    private static final Optional<String> FORTANIX_API_KEY = Optional.ofNullable(System.getenv().get("FORTANIX_API_KEY"));

    static final boolean AVAILABLE;

    static {
        AVAILABLE = FORTANIX_API_ENDPOINT.isPresent() && FORTANIX_ADMIN_API_KEY.isPresent() && FORTANIX_API_KEY.isPresent();
        if (!AVAILABLE) {
            LOGGER.info("FORTANIX_API_ENDPOINT, FORTANIX_ADMIN_API_KEY and FORTANIX_API_KEY are not defined, tests requiring the Fortanix KMS will be skipped");
        }
    }

    private static final TypeReference<List<KeyResponse>> KEY_LIST_RESPONSE_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<SecurityObjectResponse> SECURITY_OBJECT_RESPONSE_TYPE_REF = new TypeReference<>() {
    };

    private final String testRunInstance = UUID.randomUUID().toString();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private ApiKeySessionProvider adminSessionProvider;

    FortanixDsmKmsTestKmsFacade() {
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE;
    }

    @Override
    public void start() {
        var adminConfig = new Config(getEndpointUrl(), new ApiKeySessionProviderConfig(new InlinePassword(getAdminApiKey()), null), null);
        adminSessionProvider = new ApiKeySessionProvider(adminConfig);
    }

    @Override
    public final void stop() {
        try {
            deleteTestKeks();
        }
        finally {
            Optional.ofNullable(adminSessionProvider).ifPresent(ApiKeySessionProvider::close);
        }
    }

    @NonNull
    protected URI getEndpointUrl() {
        return FORTANIX_API_ENDPOINT.orElseThrow();
    }

    @Override
    public Config getKmsServiceConfig() {
        return new Config(getEndpointUrl(), new ApiKeySessionProviderConfig(new InlinePassword(getApiKey()), 0.8), null);
    }

    private String getApiKey() {
        return FORTANIX_API_KEY.orElseThrow();
    }

    private String getAdminApiKey() {
        return FORTANIX_ADMIN_API_KEY.orElseThrow();
    }

    @Override
    public final Class<FortanixDsmKmsService> getKmsServiceClass() {
        return FortanixDsmKmsService.class;
    }

    private void deleteTestKeks() {

        var keys = listAllKeys();
        keys.stream()
                .filter(k1 -> Optional.ofNullable(k1.customMetadata())
                        .map(m -> m.get(TEST_RUN_INSTANCE_ID_METADATA_KEY))
                        .filter(testRunInstance::equals)
                        .isPresent())
                .forEach(k -> {

                    var keyDeleteRequest = HttpRequest.newBuilder()
                            .uri(getEndpointUrl().resolve("/crypto/v1/keys/" + k.kid()))
                            .header(FortanixDsmKms.AUTHORIZATION_HEADER, getSessionHeader())
                            .DELETE()
                            .build();

                    sendRequestExpectingNoResponse(keyDeleteRequest);
                });

    }

    private List<KeyResponse> listAllKeys() {
        var keyListRequest = HttpRequest.newBuilder()
                .uri(getEndpointUrl().resolve("/crypto/v1/keys"))
                .header(FortanixDsmKms.AUTHORIZATION_HEADER, getSessionHeader())
                .GET()
                .build();

        return sendRequest("", keyListRequest, KEY_LIST_RESPONSE_RESPONSE);
    }

    @Override
    public final TestKekManager getTestKekManager() {
        return new FortanixDsmKmsTestKekManager();
    }

    class FortanixDsmKmsTestKekManager implements TestKekManager {

        @Override
        public void generateKek(String alias) {
            var generateRequest = new SecurityObjectRequest(alias, 256, "AES", false, List.of("ENCRYPT", "DECRYPT", "APPMANAGEABLE"),
                    Map.of(TEST_RUN_INSTANCE_ID_METADATA_KEY, testRunInstance));
            var request = createRequest("/crypto/v1/keys", generateRequest);
            sendRequest(alias, request, SECURITY_OBJECT_RESPONSE_TYPE_REF);
        }

        @Override
        public SecurityObjectResponse read(String alias) {
            var descriptor = new SecurityObjectDescriptor(null, alias, null);
            return read(descriptor);
        }

        private SecurityObjectResponse read(SecurityObjectDescriptor descriptor) {
            var request = createRequest("/crypto/v1/keys/info", descriptor);
            return sendRequest(descriptor.toString(), request, SECURITY_OBJECT_RESPONSE_TYPE_REF);
        }

        @Override
        public void deleteKek(String alias) {
            var key = read(alias);

            var keyDeleteRequest = HttpRequest.newBuilder()
                    .uri(getEndpointUrl().resolve("/crypto/v1/keys/" + key.kid()))
                    .header(FortanixDsmKms.AUTHORIZATION_HEADER, getSessionHeader())
                    .DELETE()
                    .build();
            sendRequestExpectingNoResponse(keyDeleteRequest);

            // FIXME: Seems we need to wait about a second until the key actually goes away
            // reproducer: https://github.com/k-wall/envelope-encryption-with-fortanix/pull/3
            // raised with https://github.com/ffaruqui
            try {
                Thread.sleep(1000L);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        @Override
        public void rotateKek(String alias) {
            final var descriptor = new SecurityObjectDescriptor(null, alias, null);
            var rekeyRequest = createRequest("/crypto/v1/keys/rekey", descriptor);
            sendRequest(alias, rekeyRequest, SECURITY_OBJECT_RESPONSE_TYPE_REF);
        }
    }

    private HttpRequest createRequest(String path, Object request) {
        var body = encodeJson(request).getBytes(UTF_8);

        return HttpRequest.newBuilder()
                .uri(getEndpointUrl().resolve(path))
                .header(FortanixDsmKms.AUTHORIZATION_HEADER, getSessionHeader())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    @NonNull
    private String getSessionHeader() {
        return adminSessionProvider.getSession().toCompletableFuture().join().authorizationHeader();
    }

    private <R> R sendRequest(String key, HttpRequest request, TypeReference<R> valueTypeRef) {
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            checkForError(key, request.uri(), response.statusCode(), response);
            return decodeJson(valueTypeRef, response.body());
        }
        catch (IOException e) {
            if (e.getCause() instanceof KmsException ke) {
                throw ke;
            }
            throw new UncheckedIOException("Request to %s failed".formatted(request), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during REST API call: %s".formatted(request.uri()), e);
        }
    }

    private void checkForError(String key, URI uri, int statusCode, HttpResponse<byte[]> response) {
        if (!isHttpSuccess(statusCode)) {
            if (statusCode == 404) {
                throw new UnknownAliasException(key);
            }
            else if (statusCode == 409) {
                throw new AlreadyExistsException(key);
            }
            var body = new String(response.body(), UTF_8);
            throw new IllegalStateException(
                    "Unable to read error response with Status Code: %s from Fortanix for request: %s, body %s".formatted(response.statusCode(), uri,
                            body));
        }
    }

    private void sendRequestExpectingNoResponse(HttpRequest request) {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (!isHttpSuccess(response.statusCode())) {
                throw new IllegalStateException("Unexpected response: %d to request %s".formatted(response.statusCode(), request.uri()));
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Request to %s failed".formatted(request), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private boolean isHttpSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static <T> T decodeJson(TypeReference<T> valueTypeRef, byte[] bytes) {
        try {
            return OBJECT_MAPPER.readValue(bytes, valueTypeRef);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String encodeJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to encode the request body", e);
        }
    }

}
