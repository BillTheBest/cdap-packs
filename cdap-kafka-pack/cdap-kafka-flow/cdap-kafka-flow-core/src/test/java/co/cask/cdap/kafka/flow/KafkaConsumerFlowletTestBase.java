/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.kafka.flow;

import co.cask.cdap.api.app.Application;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.FlowManager;
import co.cask.cdap.test.RuntimeMetrics;
import co.cask.cdap.test.RuntimeStats;
import co.cask.cdap.test.TestBase;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.twill.internal.kafka.EmbeddedKafkaServer;
import org.apache.twill.internal.utils.Networks;
import org.apache.twill.internal.zookeeper.InMemoryZKServer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base class for writing a Kafka consuming flowlet test.
 */
public abstract class KafkaConsumerFlowletTestBase extends TestBase {

  @ClassRule
  public static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();

  private static final int PARTITIONS = 6;

  protected static InMemoryZKServer zkServer;
  protected static EmbeddedKafkaServer kafkaServer;
  protected static int kafkaPort;

  @BeforeClass
  public static void initialize() throws IOException {
    zkServer = InMemoryZKServer.builder().setDataDir(TMP_FOLDER.newFolder()).build();
    zkServer.startAndWait();

    kafkaPort = Networks.getRandomPort();
    kafkaServer = new EmbeddedKafkaServer(generateKafkaConfig(zkServer.getConnectionStr(),
                                                              kafkaPort, TMP_FOLDER.newFolder()));
    kafkaServer.startAndWait();
  }

  @AfterClass
  public static void cleanup() {
    kafkaServer.stopAndWait();
    zkServer.stopAndWait();
  }

  /**
   * Returns the {@link Application} class for running the test.
   */
  protected abstract Class<? extends KafkaConsumingApp> getApplication();

  /**
   * Publish messages to Kafka for the given topic.
   *
   * @param topic Topic to publish to
   * @param message Map from message key to payload
   */
  protected abstract void sendMessage(String topic, Map<String, String> message);

  protected abstract boolean supportBrokerList();

  /**
   * Returns a Map supplied as runtime arguments to the Flow started by this test.
   */
  private Map<String, String> getRuntimeArgs(String topic, int partitions, boolean preferZK) {
    Map<String, String> args = Maps.newHashMap();

    args.put("kafka.topic", topic);
    if (!supportBrokerList() || preferZK) {
      args.put("kafka.zookeeper", zkServer.getConnectionStr());
    } else {
      args.put("kafka.brokers", "localhost:" + kafkaPort);
    }
    args.put("kafka.partitions", Integer.toString(partitions));
    return args;
  }

  @Test
  public final void testFlowlet() throws Exception {
    String topic = "testTopic";
    ApplicationManager appManager = deployApplication(getApplication());
    FlowManager flowManager = appManager.startFlow("KafkaConsumingFlow", getRuntimeArgs(topic, PARTITIONS, false));

    // Publish 5 messages to Kafka, the flow should consume them
    int msgCount = 5;
    Map<String, String> messages = Maps.newHashMap();
    for (int i = 0; i < msgCount; i++) {
      messages.put(Integer.toString(i), "Message " + i);
    }
    sendMessage(topic, messages);

    RuntimeMetrics sinkMetrics = RuntimeStats.getFlowletMetrics("KafkaConsumingApp", "KafkaConsumingFlow", "DataSink");
    sinkMetrics.waitForProcessed(msgCount, 10, TimeUnit.SECONDS);

    // Sleep for a while, no more message should be processed.
    TimeUnit.SECONDS.sleep(2);
    Assert.assertEquals(msgCount, sinkMetrics.getProcessed());

    flowManager.stop();

    // Publish a message, a failure and another message when the flow is not running
    messages.clear();
    messages.put(Integer.toString(msgCount), "Message " + msgCount++);
    messages.put("Failure", "Failure");
    messages.put(Integer.toString(msgCount), "Message " + msgCount++);
    sendMessage(topic, messages);

    // Clear stats and start the flow again (using ZK to discover broker this time)
    RuntimeStats.clearStats("KafkaConsumingApp");
    flowManager = startFlowWithRetry(appManager, "KafkaConsumingFlow", getRuntimeArgs(topic, PARTITIONS, true), 5);

    // Wait for 2 message. This should be ok.
    sinkMetrics.waitForProcessed(2L, 10, TimeUnit.SECONDS);

    // Sleep for a while, no more message should be processed.
    TimeUnit.SECONDS.sleep(2);
    Assert.assertEquals(2, sinkMetrics.getProcessed());

    flowManager.stop();

    // Verify through Dataset counter table, that keeps a count for each message the sink flowlet received
    KeyValueTable counter = appManager.<KeyValueTable>getDataSet("counter").get();
    CloseableIterator<KeyValue<byte[], byte[]>> scanner = counter.scan(null, null);
    try {
      int size = 0;
      while (scanner.hasNext()) {
        KeyValue<byte[], byte[]> keyValue = scanner.next();
        Assert.assertEquals(1L, Bytes.toLong(keyValue.getValue()));
        size++;
      }
      Assert.assertEquals(msgCount, size);
    } finally {
      scanner.close();
    }
  }

  @Test
  public void testChangeInstances() throws TimeoutException, InterruptedException {
    // Start the flow with one instance source.
    String topic = "testChangeInstances";
    ApplicationManager appManager = deployApplication(getApplication());
    FlowManager flowManager = appManager.startFlow("KafkaConsumingFlow", getRuntimeArgs(topic, PARTITIONS, false));

    // Publish 100 messages. Should expect each partition got some messages.
    int msgCount = 100;
    for (int i = 0; i < msgCount; i++) {
      sendMessage(topic, ImmutableMap.of(Integer.toString(i), "TestInstances " + i));
    }

    // Should received 100 messages
    RuntimeMetrics sinkMetrics = RuntimeStats.getFlowletMetrics("KafkaConsumingApp", "KafkaConsumingFlow", "DataSink");
    sinkMetrics.waitForProcessed(msgCount, 10, TimeUnit.SECONDS);

    // Scale to 3 instances
    flowManager.setFlowletInstances("KafkaSource", 3);

    // Send another 100 messages.
    for (int i = 0; i < msgCount; i++) {
      sendMessage(topic, ImmutableMap.of(Integer.toString(i + msgCount), "TestInstances " + (i + msgCount)));
    }
    msgCount *= 2;

    // Should received another 100 messages
    sinkMetrics.waitForProcessed(msgCount, 10, TimeUnit.SECONDS);

    flowManager.stop();

    // Verify through Dataset counter table, that keeps a count for each message the sink flowlet received
    KeyValueTable counter = appManager.<KeyValueTable>getDataSet("counter").get();
    byte[] startRow = Bytes.toBytes("TestInstances ");
    CloseableIterator<KeyValue<byte[], byte[]>> scanner = counter.scan(startRow, Bytes.stopKeyForPrefix(startRow));
    try {
      int size = 0;
      while (scanner.hasNext()) {
        KeyValue<byte[], byte[]> keyValue = scanner.next();
        Assert.assertEquals(1L, Bytes.toLong(keyValue.getValue()));
        size++;
      }
      Assert.assertEquals(msgCount, size);
    } finally {
      scanner.close();
    }
  }

  private FlowManager startFlowWithRetry(ApplicationManager appManager,
                                         String flowId, Map<String, String> args, int trials) {
    Throwable failure = null;
    do {
      try {
        if (failure != null) {
          TimeUnit.SECONDS.sleep(1);
        }
        return appManager.startFlow(flowId, args);
      } catch (InterruptedException e) {
        throw Throwables.propagate(e);
      } catch (Throwable t) {
        // Just memorize the failure
        failure = t;
      }
    } while (--trials > 0);

    throw Throwables.propagate(failure);
  }

  private static Properties generateKafkaConfig(String zkConnectStr, int port, File logDir) {
    Properties prop = new Properties();
    prop.setProperty("log.dir", logDir.getAbsolutePath());
    prop.setProperty("port", Integer.toString(port));
    prop.setProperty("broker.id", "1");
    prop.setProperty("socket.send.buffer.bytes", "1048576");
    prop.setProperty("socket.receive.buffer.bytes", "1048576");
    prop.setProperty("socket.request.max.bytes", "104857600");
    prop.setProperty("num.partitions", Integer.toString(PARTITIONS));
    prop.setProperty("log.retention.hours", "24");
    prop.setProperty("log.flush.interval.messages", "10000");
    prop.setProperty("log.flush.interval.ms", "1000");
    prop.setProperty("log.segment.bytes", "536870912");
    prop.setProperty("zookeeper.connect", zkConnectStr);
    prop.setProperty("zookeeper.connection.timeout.ms", "1000000");
    prop.setProperty("default.replication.factor", "1");

    // These are for Kafka-0.7
    prop.setProperty("brokerid", "1");
    prop.setProperty("zk.connect", zkConnectStr);
    prop.setProperty("zk.connectiontimeout.ms", "1000000");

    return prop;
  }
}
