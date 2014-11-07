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

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.flow.flowlet.FlowletContext;
import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import kafka.api.FetchRequest;
import kafka.common.OffsetOutOfRangeException;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.MessageAndOffset;
import org.apache.twill.common.Threads;
import org.apache.twill.kafka.client.TopicPartition;
import org.apache.twill.zookeeper.RetryStrategies;
import org.apache.twill.zookeeper.ZKClientService;
import org.apache.twill.zookeeper.ZKClientServices;
import org.apache.twill.zookeeper.ZKClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for implementing flowlet that consumes data from Kafka 0.7 cluster. One can simply extends
 * from this class and implements the {@link #configureKafka(KafkaConfigurer)} method to provide information of
 * the Kafka cluster and topics to consume from.
 * <br/><br/>
 * To process messages received from Kafka, overrides {@link #processMessage(Object) processMessage(PAYLOAD)}.
 * You can also override
 * {@link #decodePayload(ByteBuffer)} to provide custom decoding into the {@code PAYLOAD} type if it is not
 * one of the built-in support types ({@link ByteBuffer}, {@link String} and {@code byte[]}).
 * <br/>
 * For advanced usage, override {@link #processMessage(KafkaMessage)} instead to get
 * full information about the message being fetched.
 * <br/><br/>
 * To enjoy automatic persisting and restoring of consumers' offsets, the {@link #getOffsetStore()} method should be
 * overridden to return a {@link KeyValueTable} as well.
 * <br/><br/>
 * The offset type for Kafka 0.7 is a map from broker id to a long offset. It's because in 0.7, there is no single
 * leader for a given topic partition, and each broker has different offset value, which is basically local file
 * offset.
 *
 * @param <PAYLOAD> type of the message payload
 */
public abstract class Kafka07ConsumerFlowlet<PAYLOAD>
                extends KafkaConsumerFlowlet<ByteBuffer, PAYLOAD, Map<String, Long>> {

  private static final Logger LOG = LoggerFactory.getLogger(Kafka07ConsumerFlowlet.class);

  private ZKClientService zkClient;
  private KafkaBrokerCache kafkaBrokerCache;
  private Cache<KafkaBroker, SimpleConsumer> kafkaConsumers;
  private ExecutorService fetchExecutor;

  @Override
  public void initialize(FlowletContext context) throws Exception {
    super.initialize(context);

    String kafkaZKConnect = getKafkaConfig().getZookeeper();
    if (kafkaZKConnect == null) {
      throw new IllegalStateException("Must provide ZooKeeper quorum string to consume from Kafka 0.7 cluster");
    }

    zkClient = ZKClientServices.delegate(
      ZKClients.reWatchOnExpire(
        ZKClients.retryOnFailure(ZKClientService.Builder.of(kafkaZKConnect).build(),
                                 RetryStrategies.fixDelay(2, TimeUnit.SECONDS))
      ));
    zkClient.startAndWait();

    kafkaBrokerCache = new KafkaBrokerCache(zkClient);
    kafkaBrokerCache.startAndWait();

    kafkaConsumers = CacheBuilder.newBuilder()
      .concurrencyLevel(1)
      .expireAfterAccess(60, TimeUnit.SECONDS)
      .removalListener(createConsumerCacheRemovalListener())
      .build();

    fetchExecutor = Executors.newCachedThreadPool(Threads.createDaemonThreadFactory("kafka-consumer-%d"));
  }

  @Override
  public void destroy() {
    super.destroy();
    fetchExecutor.shutdownNow();

    if (kafkaBrokerCache != null) {
      stopService(kafkaBrokerCache);
    }
    if (zkClient != null) {
      stopService(zkClient);
    }
  }

  @Override
  protected Iterator<KafkaMessage<Map<String, Long>>> readMessages(
    final KafkaConsumerInfo<Map<String, Long>> consumerInfo) {

    final TopicPartition topicPartition = consumerInfo.getTopicPartition();
    String topic = topicPartition.getTopic();
    int partition = topicPartition.getPartition();

    List<KafkaBroker> brokers = kafkaBrokerCache.getBrokers(topic, partition);
    if (brokers.isEmpty()) {
      return Iterators.emptyIterator();
    }

    // If there are more than one broker, use the fetchExecutor to fetch them in parallel.
    if (brokers.size() > 1) {
      return multiFetch(consumerInfo, brokers, fetchExecutor);
    }

    // If there is only one broker, just fetch the message inline and returns an iterator
    Map<String, Long> offsets = Maps.newHashMap(consumerInfo.getReadOffset());
    KafkaBroker broker = brokers.get(0);
    SimpleConsumer consumer = getConsumer(broker, consumerInfo.getFetchSize());
    long offset = getBrokerOffset(broker, consumerInfo.getTopicPartition(), offsets, consumer);
    FetchResult result = fetchMessages(broker, consumer, topicPartition, offset, consumerInfo.getFetchSize());
    return handleFetch(consumerInfo, offsets, result);
  }

  /**
   * Always return {@code null} as in Kafka-0.7, there is no key in the message.
   */
  @Override
  protected final ByteBuffer decodeKey(ByteBuffer buffer) {
    return null;
  }

  /**
   * Always call {@link #processMessage(Object)} as in Kafka-0.7, there is no key in the message.
   */
  @Override
  protected final void processMessage(ByteBuffer key, PAYLOAD payload) throws Exception {
    processMessage(payload);
  }

  /**
   * Persists offset for each {@link TopicPartition} to a {@link KeyValueTable} provided by
   * {@link #getOffsetStore()}. The key is simply concatenation of
   * topic, partition and brokerId. The value is a 8-bytes encoded long of the offset. If no dataset is provided,
   * this method is a no-op.
   *
   * @param offsets Map from topic partition to offsets to save.
   */
  @Override
  protected void saveReadOffsets(Map<TopicPartition, Map<String, Long>> offsets) {
    KeyValueTable offsetStore = getOffsetStore();
    if (offsetStore == null) {
      return;
    }

    for (Map.Entry<TopicPartition, Map<String, Long>> entry : offsets.entrySet()) {
      TopicPartition topicPartition = entry.getKey();

      for (Map.Entry<String, Long> offsetEntry : entry.getValue().entrySet()) {
        String key = getStoreKey(topicPartition) + ":" + offsetEntry.getKey();
        offsetStore.write(key, Bytes.toBytes(offsetEntry.getValue()));
      }
    }
  }

  /**
   * Returns the beginning offset for the given topic partition. It uses the {@link KeyValueTable} returned
   * by {@link #getOffsetStore()} to lookup information. If no table is provided, this method returns an empty Map.
   *
   * @param topicPartition The topic and partition that needs the start offset
   * @return The starting offset or {@link kafka.api.OffsetRequest#EarliestTime()} if offset is unknown.
   */
  @Override
  protected Map<String, Long> getBeginOffset(TopicPartition topicPartition) {
    KeyValueTable offsetStore = getOffsetStore();
    if (offsetStore == null) {
      return ImmutableMap.of();
    }

    ImmutableMap.Builder<String, Long> result = ImmutableMap.builder();
    byte[] startRow = Bytes.toBytes(topicPartition.getTopic() + ":" + topicPartition.getPartition() + ":");
    CloseableIterator<KeyValue<byte[], byte[]>> iterator = offsetStore.scan(startRow, Bytes.stopKeyForPrefix(startRow));
    while (iterator.hasNext()) {
      KeyValue<byte[], byte[]> keyValue = iterator.next();
      byte[] key = keyValue.getKey();

      String brokerId = new String(key, startRow.length, key.length - startRow.length, Charsets.UTF_8);
      long offset = Bytes.toLong(keyValue.getValue());

      result.put(brokerId, offset);
    }

    return result.build();
  }

  /**
   * Returns the default value of offset to start with when encounter a new broker for a given topic partition.
   * By default it is {@code -2L}, which represents earliest offset in Kafka. Sub-class can override this to return
   * different value (e.g. {@code -1L} means latest offset).
   */
  protected long getDefaultOffset(KafkaBroker broker, TopicPartition topicPartition) {
    return -2L; // Earliest
  }

  /**
   * Returns a {@link SimpleConsumer} that talks to given broker. It will first lookup one from cache. If none exists
   * in the cache, it will create one and cache it.
   *
   * @param broker Broker to connect to
   * @param fetchSize maximum number of bytes for each fetch
   */
  private SimpleConsumer getConsumer(KafkaBroker broker, int fetchSize) {
    SimpleConsumer consumer = kafkaConsumers.getIfPresent(broker);
    if (consumer != null) {
      return consumer;
    }

    consumer = new SimpleConsumer(broker.getHost(), broker.getPort(), SO_TIMEOUT, fetchSize);
    kafkaConsumers.put(broker, consumer);
    return consumer;
  }

  /**
   * Fetches messages from the given Kafka broker. If fetch fails, exception will be carried inside the fetch result.
   *
   * @param consumer The consumer to use for the fetch
   * @param topicPartition Topic and partition to fetch from
   * @param offset message offset to start fetching
   * @param fetchSize Size in bytes for the fetch.
   */
  private FetchResult fetchMessages(KafkaBroker broker, SimpleConsumer consumer,
                                    TopicPartition topicPartition, long offset, int fetchSize) {
    FetchRequest fetchRequest = new FetchRequest(topicPartition.getTopic(),
                                                 topicPartition.getPartition(), offset, fetchSize);
    try {
      return new FetchResult(broker, offset, consumer.fetch(fetchRequest));
    } catch (Throwable t) {
      return new FetchResult(broker, offset, t);
    }
  }

  /**
   * Performs fetch from multiple brokers simultaneously.
   *
   * @param consumerInfo information about how to consumer
   * @param brokers list of brokers to consume from
   * @param executor {@link Executor} to fetch in parallel.
   * @return A {@link Iterator} that is a concatenation of iterators obtained from each fetch.
   */
  private Iterator<KafkaMessage<Map<String, Long>>> multiFetch(final KafkaConsumerInfo<Map<String, Long>> consumerInfo,
                                                               List<KafkaBroker> brokers,
                                                               Executor executor) {
    final TopicPartition topicPartition = consumerInfo.getTopicPartition();

    Map<String, Long> offsets = Maps.newHashMap(consumerInfo.getReadOffset());
    CompletionService<FetchResult> fetches = new ExecutorCompletionService<FetchResult>(executor);
    for (final KafkaBroker broker : brokers) {
      final SimpleConsumer consumer = getConsumer(broker, consumerInfo.getFetchSize());
      final long offset = getBrokerOffset(broker, consumerInfo.getTopicPartition(), offsets, consumer);

      fetches.submit(new Callable<FetchResult>() {
        @Override
        public FetchResult call() throws Exception {
          return fetchMessages(broker, consumer, topicPartition, offset, consumerInfo.getFetchSize());
        }
      });
    }

    try {
      // Returns a concatenated iterator created from all fetches
      List<Iterator<KafkaMessage<Map<String, Long>>>> messageIterators = Lists.newArrayList();
      for (int i = 0; i < brokers.size(); i++) {
        FetchResult result = fetches.take().get();
        messageIterators.add(handleFetch(consumerInfo, offsets, result));
      }
      return Iterators.concat(messageIterators.iterator());
    } catch (Exception ex) {
      // On any exception when getting the future, simply return an empty iterator
      // This is because the task submitted to the executor should never throw exception.
      return Iterators.emptyIterator();
    }
  }

  /**
   * Creates a {@link RemovalListener} to close {@link SimpleConsumer} when it is evicted from the consumer cache.
   */
  private RemovalListener<KafkaBroker, SimpleConsumer> createConsumerCacheRemovalListener() {
    return new RemovalListener<KafkaBroker, SimpleConsumer>() {
      @Override
      public void onRemoval(RemovalNotification<KafkaBroker, SimpleConsumer> notification) {
        SimpleConsumer consumer = notification.getValue();
        if (consumer == null) {
          return;
        }
        try {
          consumer.close();
        } catch (Throwable t) {
          LOG.error("Exception when closing Kafka consumer.", t);
        }
      }
    };
  }

  /**
   * Returns the offset to start fetching from.
   *
   * @param broker The broker to fetch from
   * @param topicPartition Topic and partition to fetch from
   * @param offsets Existing offsets states. The Map may get modified after calling this method.
   * @param consumer consumer for talking to the broker.
   * @return offset for the given {@link TopicPartition} in the given {@link KafkaBroker}.
   */
  private long getBrokerOffset(KafkaBroker broker, TopicPartition topicPartition,
                               Map<String, Long> offsets, SimpleConsumer consumer) {
    Long offset = offsets.get(broker.getId());
    if (offset == null) {
      offset = getDefaultOffset(broker, topicPartition);
      offsets.put(broker.getId(), offset);
    }

    // Special offset value. Need to talk to Kafka to find the right offset.
    if (offset < 0) {
      long[] result = consumer.getOffsetsBefore(topicPartition.getTopic(), topicPartition.getPartition(), offset, 1);
      offset = result.length > 0 ? result[0] : 0L;
      offsets.put(broker.getId(), offset);
    }
    return offset;
  }

  /**
   * Creates an {@link Iterator} of {@link KafkaMessage} based on the given {@link FetchResult}.
   *
   * @param topicPartition topic and partition of the fetch
   * @param offsets Existing offsets states.
   *                The Map will get updated while iterating with the resulting {@link Iterator}.
   * @param result The fetch result
   */
  private Iterator<KafkaMessage<Map<String, Long>>> createMessageIterator(final TopicPartition topicPartition,
                                                                          final Map<String, Long> offsets,
                                                                          final FetchResult result) {
    final Iterator<MessageAndOffset> messages = result.iterator();
    return new AbstractIterator<KafkaMessage<Map<String, Long>>>() {
      @Override
      protected KafkaMessage<Map<String, Long>> computeNext() {
        while (messages.hasNext()) {
          MessageAndOffset message = messages.next();
          if (message.offset() < result.getBeginOffset()) {
            continue;
          }
          offsets.put(result.getBroker().getId(), message.offset());
          return new KafkaMessage<Map<String, Long>>(topicPartition, offsets, null, message.message().payload());
        }
        return endOfData();
      }
    };
  }

  /**
   * Handles a given {@link FetchResult}.
   *
   * @param consumerInfo information about how to consumer
   * @param offsets Existing offsets states.
   *                The Map will get updated while iterating with the resulting {@link Iterator}.
   *                It may also get modified after calling this method
   *                if the fetch failed with {@link OffsetOutOfRangeException}.
   * @param result The fetch result
   * @return An {@link Iterator} of {@link KafkaMessage}.
   */
  private Iterator<KafkaMessage<Map<String, Long>>> handleFetch(KafkaConsumerInfo<Map<String, Long>> consumerInfo,
                                                                Map<String, Long> offsets, FetchResult result) {
    TopicPartition topicPartition = consumerInfo.getTopicPartition();

    if (result.isSuccess()) {
      return createMessageIterator(topicPartition, offsets, result);
    }

    // If fetch failed, distinguish them as offset out of range vs other
    if (result.getFailureCause() instanceof OffsetOutOfRangeException) {
      String topic = topicPartition.getTopic();
      int partition = topicPartition.getPartition();

      // Get the offset before the current offset.
      // The consumer should be cached already, hence the fetch size doesn't matter
      SimpleConsumer consumer = getConsumer(result.getBroker(), consumerInfo.getFetchSize());
      long newOffset = consumer.getOffsetsBefore(topic, partition, -2L, 1)[0];
      if (newOffset < result.getBeginOffset()) {
        // If current offset is greater than earliest offset, yet out of range, meaning it is after the latest offset
        // Hence using latest offset as the new offset
        newOffset = consumer.getOffsetsBefore(topic, partition, -1L, 1)[0];
      }
      offsets.put(result.getBroker().getId(), newOffset);
      consumerInfo.setReadOffset(offsets);
    } else {
      // For other type of error, just remove the consumer from cache, which will lead to closing of it.
      // The next iteration will open the right one again.
      kafkaConsumers.invalidate(result.getBroker());
    }
    return Iterators.emptyIterator();
  }

  /**
   * Helper class to carries message fetch result.
   */
  private static final class FetchResult implements Iterable<MessageAndOffset> {
    private final KafkaBroker broker;
    private final long beginOffset;
    private final ByteBufferMessageSet messageSet;
    private final Throwable failureCause;

    private FetchResult(KafkaBroker broker, long beginOffset, ByteBufferMessageSet messageSet) {
      this(broker, beginOffset, messageSet, null);
    }

    private FetchResult(KafkaBroker broker, long beginOffset, Throwable failureCause) {
      this(broker, beginOffset, null, failureCause);
    }

    private FetchResult(KafkaBroker broker, long beginOffset, ByteBufferMessageSet messageSet, Throwable failureCause) {
      this.broker = broker;
      this.beginOffset = beginOffset;
      this.messageSet = messageSet;
      this.failureCause = failureCause;
    }

    @Override
    public Iterator<MessageAndOffset> iterator() {
      if (messageSet == null) {
        throw new IllegalStateException("There was error in the fetch.");
      }
      return messageSet.iterator();
    }

    KafkaBroker getBroker() {
      return broker;
    }

    Throwable getFailureCause() {
      return failureCause;
    }

    boolean isSuccess() {
      return failureCause == null;
    }

    long getBeginOffset() {
      return beginOffset;
    }
  }
}
