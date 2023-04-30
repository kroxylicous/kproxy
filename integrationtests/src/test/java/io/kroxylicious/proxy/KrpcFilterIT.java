/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.kroxylicious.proxy.filter.CreateTopicRejectFilter;
import io.kroxylicious.proxy.internal.filter.ByteBufferTransformation;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.common.BrokerCluster;
import io.kroxylicious.testing.kafka.common.KeytoolCertificateGenerator;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.proxy.Utils.startProxy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

@ExtendWith(KafkaClusterExtension.class)
public class KrpcFilterIT {

    private static final String TOPIC_1 = "my-test-topic";
    private static final String TOPIC_2 = "other-test-topic";
    private static final String PLAINTEXT = "Hello, world!";
    private static final byte[] TOPIC_1_CIPHERTEXT = { (byte) 0x3d, (byte) 0x5a, (byte) 0x61, (byte) 0x61, (byte) 0x64, (byte) 0x21, (byte) 0x15, (byte) 0x6c,
            (byte) 0x64, (byte) 0x67, (byte) 0x61, (byte) 0x59, (byte) 0x16 };
    private static final byte[] TOPIC_2_CIPHERTEXT = { (byte) 0xffffffa7, (byte) 0xffffffc4, (byte) 0xffffffcb, (byte) 0xffffffcb, (byte) 0xffffffce, (byte) 0xffffff8b,
            (byte) 0x7f, (byte) 0xffffffd6, (byte) 0xffffffce, (byte) 0xffffffd1, (byte) 0xffffffcb, (byte) 0xffffffc3, (byte) 0xffffff80 };
    @TempDir
    private Path certsDirectory;

    @BeforeAll
    public static void checkReversibleEncryption() {
        // The precise details of the cipher don't matter
        // What matters is that it the ciphertext key depends on the topic name
        // and that decode() is the inverse of encode()
        assertArrayEquals(TOPIC_1_CIPHERTEXT, encode(TOPIC_1, ByteBuffer.wrap(PLAINTEXT.getBytes(StandardCharsets.UTF_8))).array());
        assertEquals(PLAINTEXT, new String(decode(TOPIC_1, ByteBuffer.wrap(TOPIC_1_CIPHERTEXT)).array(), StandardCharsets.UTF_8));
        assertArrayEquals(TOPIC_2_CIPHERTEXT, encode(TOPIC_2, ByteBuffer.wrap(PLAINTEXT.getBytes(StandardCharsets.UTF_8))).array());
        assertEquals(PLAINTEXT, new String(decode(TOPIC_2, ByteBuffer.wrap(TOPIC_2_CIPHERTEXT)).array(), StandardCharsets.UTF_8));
    }

    public static class TestEncoder implements ByteBufferTransformation {

        @Override
        public ByteBuffer transform(String topicName, ByteBuffer in) {
            return encode(topicName, in);
        }
    }

    private static ByteBuffer encode(String topicName, ByteBuffer in) {
        var out = ByteBuffer.allocate(in.limit());
        byte rot = (byte) (topicName.hashCode() % Byte.MAX_VALUE);
        for (int index = 0; index < in.limit(); index++) {
            byte b = in.get(index);
            byte rotated = (byte) (b + rot);
            out.put(index, rotated);
        }
        return out;
    }

    public static class TestDecoder implements ByteBufferTransformation {

        @Override
        public ByteBuffer transform(String topicName, ByteBuffer in) {
            return decode(topicName, in);
        }
    }

    private static ByteBuffer decode(String topicName, ByteBuffer in) {
        var out = ByteBuffer.allocate(in.limit());
        out.limit(in.limit());
        byte rot = (byte) -(topicName.hashCode() % Byte.MAX_VALUE);
        for (int index = 0; index < in.limit(); index++) {
            byte b = in.get(index);
            byte rotated = (byte) (b + rot);
            out.put(index, rotated);
        }
        return out;
    }

    @Test
    public void shouldPassThroughRecordUnchanged(KafkaCluster cluster, Admin admin) throws Exception {
        String proxyAddress = "localhost:9192";

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();

        var config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers()).build().toYaml();

        try (var proxy = startProxy(config)) {
            try (var producer = new KafkaProducer<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ProducerConfig.CLIENT_ID_CONFIG, "shouldPassThroughRecordUnchanged",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000))) {
                producer.send(new ProducerRecord<>(TOPIC_1, "my-key", "Hello, world!")).get();
            }

            try (var consumer = new KafkaConsumer<String, String>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                consumer.subscribe(Set.of(TOPIC_1));
                var records = consumer.poll(Duration.ofSeconds(10));
                consumer.close();
                assertEquals(1, records.count());
                assertEquals("Hello, world!", records.iterator().next().value());
            }
        }
    }

    @Test
    public void requestFiltersCanRespondWithoutProxying(KafkaCluster cluster, Admin admin) throws Exception {
        String proxyAddress = "localhost:9192";

        var config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                .addNewFilter().withType("CreateTopicRejectFilter").endFilter().build().toYaml();

        try (var ignored = startProxy(config)) {
            try (var proxyAdmin = Admin.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress))) {
                Assertions.assertThatExceptionOfType(ExecutionException.class)
                        .isThrownBy(() -> {
                            proxyAdmin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();
                        })
                        .withCauseInstanceOf(InvalidTopicException.class)
                        .havingCause()
                        .withMessage(CreateTopicRejectFilter.ERROR_MESSAGE);

                // check no topic created on the cluster
                Set<String> names = admin.listTopics().names().get(10, TimeUnit.SECONDS);
                assertEquals(Set.of(), names);
            }
        }
    }

    @Test
    public void shouldModifyProduceMessage(KafkaCluster cluster, Admin admin) throws Exception {
        String proxyAddress = "localhost:9192";

        admin.createTopics(List.of(
                new NewTopic(TOPIC_1, 1, (short) 1),
                new NewTopic(TOPIC_2, 1, (short) 1))).all().get();

        var config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                .addNewFilter().withType("ProduceRequestTransformation").withConfig(Map.of("transformation", TestEncoder.class.getName())).endFilter()
                .build()
                .toYaml();

        try (var proxy = startProxy(config)) {
            try (var producer = new KafkaProducer<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ProducerConfig.CLIENT_ID_CONFIG, "shouldModifyProduceMessage",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000))) {
                producer.send(new ProducerRecord<>(TOPIC_1, "my-key", PLAINTEXT)).get();
                producer.send(new ProducerRecord<>(TOPIC_2, "my-key", PLAINTEXT)).get();
                producer.flush();
            }

            ConsumerRecords<String, byte[]> records1;
            ConsumerRecords<String, byte[]> records2;
            try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                    ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                consumer.subscribe(Set.of(TOPIC_1));
                records1 = consumer.poll(Duration.ofSeconds(10));
                consumer.subscribe(Set.of(TOPIC_2));
                records2 = consumer.poll(Duration.ofSeconds(10));
            }

            assertEquals(1, records1.count());
            assertArrayEquals(TOPIC_1_CIPHERTEXT, records1.iterator().next().value());
            assertEquals(1, records2.count());
            assertArrayEquals(TOPIC_2_CIPHERTEXT, records2.iterator().next().value());
        }
    }

    @Test
    public void shouldModifyFetchMessage(KafkaCluster cluster, Admin admin) throws Exception {
        String proxyAddress = "localhost:9192";

        admin.createTopics(List.of(
                new NewTopic(TOPIC_1, 1, (short) 1),
                new NewTopic(TOPIC_2, 1, (short) 1))).all().get();

        var config = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers())
                .addNewFilter().withType("FetchResponseTransformation").withConfig(Map.of("transformation", TestDecoder.class.getName())).endFilter()
                .build()
                .toYaml();

        try (var proxy = startProxy(config)) {
            try (var producer = new KafkaProducer<String, byte[]>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ProducerConfig.CLIENT_ID_CONFIG, "shouldModifyFetchMessage",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000))) {

                producer.send(new ProducerRecord<>(TOPIC_1, "my-key", TOPIC_1_CIPHERTEXT)).get();
                producer.send(new ProducerRecord<>(TOPIC_2, "my-key", TOPIC_2_CIPHERTEXT)).get();
            }

            ConsumerRecords<String, String> records1;
            ConsumerRecords<String, String> records2;
            try (var consumer = new KafkaConsumer<String, String>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                consumer.subscribe(Set.of(TOPIC_1));

                records1 = consumer.poll(Duration.ofSeconds(100));

                consumer.subscribe(Set.of(TOPIC_2));

                records2 = consumer.poll(Duration.ofSeconds(100));
            }
            assertEquals(1, records1.count());
            assertEquals(1, records2.count());
            assertEquals(List.of(PLAINTEXT, PLAINTEXT),
                    List.of(records1.iterator().next().value(),
                            records2.iterator().next().value()));
        }
    }

    @Test
    public void proxySslClusterPlain(KafkaCluster cluster) throws Exception {
        String proxyAddress = "localhost:9192";

        var brokerCertificateGenerator = new KeytoolCertificateGenerator();
        brokerCertificateGenerator.generateSelfSignedCertificateEntry("test@redhat.com", "localhost", "KI", "RedHat", null, null, "US");
        Path clientTrustStore = certsDirectory.resolve("kafka.truststore.jks");
        brokerCertificateGenerator.generateTrustStore(brokerCertificateGenerator.getCertFilePath(), "client",
                clientTrustStore.toAbsolutePath().toString());

        var builder = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers());
        var demo = builder.getVirtualClusters().get("demo");
        demo = new VirtualClusterBuilder(demo)
                .withKeyPassword(brokerCertificateGenerator.getPassword())
                .withKeyStoreFile(brokerCertificateGenerator.getKeyStoreLocation())
                .build();
        builder.addToVirtualClusters("demo", demo);
        var config = builder.build().toYaml();

        try (var proxy = startProxy(config)) {
            try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                    CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name,
                    SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, clientTrustStore.toAbsolutePath().toString(),
                    SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, brokerCertificateGenerator.getPassword()))) {
                // do some work to ensure connection is opened
                admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();
                var connectionsMetric = admin.metrics().entrySet().stream().filter(metricNameEntry -> "connections".equals(metricNameEntry.getKey().name()))
                        .findFirst();
                assertTrue(connectionsMetric.isPresent());
                var protocol = connectionsMetric.get().getKey().tags().get("protocol");
                assertThat(protocol).startsWith("TLS");
            }
        }
    }

    @Test
    public void proxyExposesClusterOfTwoBrokers(@BrokerCluster(numBrokers = 2) KafkaCluster cluster) throws Exception {
        var proxyAddress = "localhost:9192";
        var builder = baseConfigBuilder(proxyAddress, cluster.getBootstrapServers());
        var demo = builder.getVirtualClusters().get("demo");
        var brokerEndpoints = Map.of(0, "localhost:9193", 1, "localhost:9194");
        demo = new VirtualClusterBuilder(demo)
                .editClusterEndpointConfigProvider()
                .withType("StaticCluster")
                .withConfig(Map.of("bootstrapAddress", proxyAddress,
                        "brokers", brokerEndpoints))
                .endClusterEndpointConfigProvider()
                .build();
        builder.addToVirtualClusters("demo", demo);
        var config = builder.build().toYaml();

        try (var proxy = startProxy(config)) {

            try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress))) {
                var nodes = admin.describeCluster().nodes().get();
                assertThat(nodes).hasSize(2);
                var unique = nodes.stream().collect(Collectors.toMap(Node::id, KrpcFilterIT::toAddress));
                assertThat(unique).containsExactlyInAnyOrderEntriesOf(brokerEndpoints);
            }

            // Create a topic with one partition on each broker and publish to all of them..
            // As kafka client must connect to the partition leader, this verifies that kroxylicious is
            // indeed connecting to all brokers.
            int numPartitions = 2;
            try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress))) {
                // create topic and ensure that leaders are on different brokers.
                admin.createTopics(List.of(new NewTopic(TOPIC_1, numPartitions, (short) 1))).all().get();
                await().atMost(Duration.ofSeconds(5)).until(() -> admin.describeTopics(List.of(TOPIC_1)).topicNameValues().get(TOPIC_1).get()
                        .partitions().stream().map(TopicPartitionInfo::leader)
                        .collect(Collectors.toSet()),
                        leaders -> leaders.size() == numPartitions);

                try (var producer = new KafkaProducer<String, String>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyAddress,
                        ProducerConfig.CLIENT_ID_CONFIG, "myclient",
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                    for (int partition = 0; partition < numPartitions; partition++) {
                        try {
                            var send = producer.send(new ProducerRecord<>(TOPIC_1, partition, "key", "value"));
                            send.get(10, TimeUnit.SECONDS);
                        }
                        catch (TimeoutException e) {
                            // Need to explicitly close the producer otherwise it will await forever awaiting the in-flight send to complete.
                            producer.close(Duration.ofSeconds(1));
                            fail("send for partition %d timed-out (check logs for root cause)".formatted(partition), e);
                        }
                    }
                }
            }
        }
    }

    private static String toAddress(Node n) {
        return n.host() + ":" + n.port();
    }

    private static KroxyConfigBuilder baseConfigBuilder(String proxyAddress, String bootstrapServers) {
        return KroxyConfig.builder()
                .addToVirtualClusters("demo", new VirtualClusterBuilder()
                        .withNewTargetCluster()
                        .withBootstrapServers(bootstrapServers)
                        .endTargetCluster()
                        .withNewClusterEndpointConfigProvider()
                        .withType("StaticCluster")
                        .withConfig(Map.of("bootstrapAddress", proxyAddress))
                        .endClusterEndpointConfigProvider()
                        .build())
                .addNewFilter().withType("ApiVersions").endFilter()
                .addNewFilter().withType("BrokerAddress").endFilter();
    }

}
