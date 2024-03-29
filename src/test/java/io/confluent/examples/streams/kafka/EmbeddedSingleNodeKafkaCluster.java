/*
 * Copyright Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams.kafka;

import io.confluent.examples.streams.zookeeper.ZooKeeperEmbedded;
import io.confluent.kafka.schemaregistry.RestApp;
import io.confluent.kafka.schemaregistry.avro.AvroCompatibilityLevel;
import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig;
import kafka.server.KafkaConfig$;
import kafka.utils.ZkUtils;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.security.JaasUtils;
import org.apache.kafka.test.TestCondition;
import org.apache.kafka.test.TestUtils;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Runs an in-memory, "embedded" Kafka cluster with 1 ZooKeeper instance, 1 Kafka broker, and 1
 * Confluent Schema Registry instance.
 */
public class EmbeddedSingleNodeKafkaCluster extends ExternalResource {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedSingleNodeKafkaCluster.class);
  private static final int DEFAULT_BROKER_PORT = 0; // 0 results in a random port being selected
  private static final String KAFKA_SCHEMAS_TOPIC = "_schemas";
  private static final String AVRO_COMPATIBILITY_TYPE = AvroCompatibilityLevel.NONE.name;

  private static final String KAFKASTORE_OPERATION_TIMEOUT_MS = "60000";
  private static final String KAFKASTORE_DEBUG = "true";
  private static final String KAFKASTORE_INIT_TIMEOUT = "90000";

  private ZooKeeperEmbedded zookeeper;
  private ZkUtils zkUtils = null;
  private KafkaEmbedded broker;
  private RestApp schemaRegistry;
  private final Properties brokerConfig;
  private boolean running;

  /**
   * Creates and starts the cluster.
   */
  public EmbeddedSingleNodeKafkaCluster() {
    this(new Properties());
  }

  /**
   * Creates and starts the cluster.
   *
   * @param brokerConfig Additional broker configuration settings.
   */
  public EmbeddedSingleNodeKafkaCluster(final Properties brokerConfig) {
    this.brokerConfig = new Properties();
    this.brokerConfig.putAll(brokerConfig);
  }

  /**
   * Creates and starts the cluster.
   */
  public void start() throws Exception {
    log.debug("Initiating embedded Kafka cluster startup");
    log.debug("Starting a ZooKeeper instance...");
    zookeeper = new ZooKeeperEmbedded();
    log.debug("ZooKeeper instance is running at {}", zookeeper.connectString());

    zkUtils = ZkUtils.apply(
        zookeeper.connectString(),
        30000,
        30000,
        JaasUtils.isZkSecurityEnabled());

    final Properties effectiveBrokerConfig = effectiveBrokerConfigFrom(brokerConfig, zookeeper);
    log.debug("Starting a Kafka instance on port {} ...",
        effectiveBrokerConfig.getProperty(KafkaConfig$.MODULE$.PortProp()));
    broker = new KafkaEmbedded(effectiveBrokerConfig);
    log.debug("Kafka instance is running at {}, connected to ZooKeeper at {}",
        broker.brokerList(), broker.zookeeperConnect());

    schemaRegistry = new RestApp(0, zookeeperConnect(), KAFKA_SCHEMAS_TOPIC, AVRO_COMPATIBILITY_TYPE);
    // note: this following only goes to 3.3.x branches---don't merge into 4.x branches;
    // 4.x branches have a different fix to make SR access stable
    // this fix also requires to update pom.xml do depend on schema registry 3.3.2 (test artifact for unit tests only)
    final Properties schemaRegistryProps = new Properties();
    schemaRegistryProps.put(SchemaRegistryConfig.KAFKASTORE_TIMEOUT_CONFIG, KAFKASTORE_OPERATION_TIMEOUT_MS);
    schemaRegistryProps.put(SchemaRegistryConfig.DEBUG_CONFIG, KAFKASTORE_DEBUG);
    schemaRegistryProps.put(SchemaRegistryConfig.KAFKASTORE_INIT_TIMEOUT_CONFIG, KAFKASTORE_INIT_TIMEOUT);
    schemaRegistry.addConfigs(schemaRegistryProps);
    // end fix
    schemaRegistry.start();
    running = true;
  }

  private Properties effectiveBrokerConfigFrom(final Properties brokerConfig,
                                               final ZooKeeperEmbedded zookeeper) {
    final Properties effectiveConfig = new Properties();
    effectiveConfig.putAll(brokerConfig);
    effectiveConfig.put(KafkaConfig$.MODULE$.ZkConnectProp(), zookeeper.connectString());
    effectiveConfig.put(KafkaConfig$.MODULE$.PortProp(), DEFAULT_BROKER_PORT);
    effectiveConfig.put(KafkaConfig$.MODULE$.ZkConnectionTimeoutMsProp(), 60 * 1000);
    effectiveConfig.put(KafkaConfig$.MODULE$.DeleteTopicEnableProp(), true);
    effectiveConfig.put(KafkaConfig$.MODULE$.LogCleanerDedupeBufferSizeProp(), 2 * 1024 * 1024L);
    effectiveConfig.put(KafkaConfig$.MODULE$.GroupMinSessionTimeoutMsProp(), 0);
    effectiveConfig.put(KafkaConfig$.MODULE$.OffsetsTopicReplicationFactorProp(), (short) 1);
    effectiveConfig.put(KafkaConfig$.MODULE$.AutoCreateTopicsEnableProp(), true);
    return effectiveConfig;
  }

  @Override
  protected void before() throws Exception {
    start();
  }

  @Override
  protected void after() {
    stop();
  }

  /**
   * Stops the cluster.
   */
  public void stop() {
    log.info("Stopping Confluent");
    try {
      try {
        if (schemaRegistry != null) {
          schemaRegistry.stop();
        }
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
      if (broker != null) {
        broker.stop();
      }
      try {
        if (zookeeper != null) {
          zookeeper.stop();
        }
      } catch (final IOException e) {
        throw new RuntimeException(e);
      }
    } finally {
      running = false;
    }
    log.info("Confluent Stopped");
  }

  /**
   * This cluster's `bootstrap.servers` value.  Example: `127.0.0.1:9092`.
   *
   * You can use this to tell Kafka Streams applications, Kafka producers, and Kafka consumers (new
   * consumer API) how to connect to this cluster.
   */
  public String bootstrapServers() {
    return broker.brokerList();
  }

  /**
   * This cluster's ZK connection string aka `zookeeper.connect` in `hostnameOrIp:port` format.
   * Example: `127.0.0.1:2181`.
   *
   * You can use this to e.g. tell Kafka consumers (old consumer API) how to connect to this
   * cluster.
   */
  public String zookeeperConnect() {
    return zookeeper.connectString();
  }

  /**
   * The "schema.registry.url" setting of the schema registry instance.
   */
  public String schemaRegistryUrl() {
    return schemaRegistry.restConnect;
  }

  /**
   * Creates a Kafka topic with 1 partition and a replication factor of 1.
   *
   * @param topic The name of the topic.
   */
  public void createTopic(final String topic) {
    createTopic(topic, 1, 1, new Properties());
  }

  /**
   * Creates a Kafka topic with the given parameters.
   *
   * @param topic       The name of the topic.
   * @param partitions  The number of partitions for this topic.
   * @param replication The replication factor for (the partitions of) this topic.
   */
  public void createTopic(final String topic, final int partitions, final int replication) {
    createTopic(topic, partitions, replication, new Properties());
  }

  /**
   * Creates a Kafka topic with the given parameters.
   *
   * @param topic       The name of the topic.
   * @param partitions  The number of partitions for this topic.
   * @param replication The replication factor for (partitions of) this topic.
   * @param topicConfig Additional topic-level configuration settings.
   */
  public void createTopic(final String topic,
                          final int partitions,
                          final int replication,
                          final Properties topicConfig) {
    broker.createTopic(topic, partitions, replication, topicConfig);
  }

  /**
   * Deletes multiple topics and blocks until all topics got deleted.
   *
   * @param timeoutMs the max time to wait for the topics to be deleted (does not block if {@code <= 0})
   * @param topics the name of the topics
   */
  public void deleteTopicsAndWait(final long timeoutMs, final String... topics) throws InterruptedException {
    for (final String topic : topics) {
      try {
        broker.deleteTopic(topic);
      } catch (final UnknownTopicOrPartitionException e) { }
    }

    if (timeoutMs > 0) {
      TestUtils.waitForCondition(new TopicsDeletedCondition(topics), timeoutMs, "Topics not deleted after " + timeoutMs + " milli seconds.");
    }
  }

  public boolean isRunning() {
    return running;
  }

  private final class TopicsDeletedCondition implements TestCondition {
    final Set<String> deletedTopics = new HashSet<>();

    private TopicsDeletedCondition(final String... topics) {
      Collections.addAll(deletedTopics, topics);
    }

    @Override
    public boolean conditionMet() {
      final Set<String> allTopics = new HashSet<>(scala.collection.JavaConversions.seqAsJavaList(zkUtils.getAllTopics()));
      return !allTopics.removeAll(deletedTopics);
    }
  }

}
