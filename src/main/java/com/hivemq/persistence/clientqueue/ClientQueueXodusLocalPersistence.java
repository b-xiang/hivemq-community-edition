/*
 * Copyright 2019 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.persistence.clientqueue;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.ImmutableIntArray;
import com.hivemq.annotations.NotNull;
import com.hivemq.annotations.Nullable;
import com.hivemq.bootstrap.ioc.lazysingleton.LazySingleton;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.configuration.service.MqttConfigurationService.QueuedMessagesStrategy;
import com.hivemq.mqtt.message.MessageWithID;
import com.hivemq.mqtt.message.QoS;
import com.hivemq.mqtt.message.dropping.MessageDroppedService;
import com.hivemq.mqtt.message.publish.PUBLISH;
import com.hivemq.mqtt.message.pubrel.PUBREL;
import com.hivemq.persistence.PersistenceStartup;
import com.hivemq.persistence.local.xodus.EnvironmentUtil;
import com.hivemq.persistence.local.xodus.XodusLocalPersistence;
import com.hivemq.persistence.local.xodus.bucket.Bucket;
import com.hivemq.persistence.local.xodus.bucket.BucketUtils;
import com.hivemq.persistence.payload.PublishPayloadPersistence;
import com.hivemq.util.LocalPersistenceFileUtil;
import com.hivemq.util.PublishUtil;
import com.hivemq.util.Strings;
import com.hivemq.util.ThreadPreConditions;
import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.env.Cursor;
import jetbrains.exodus.env.StoreConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.hivemq.configuration.service.InternalConfigurations.QOS_0_MEMORY_HARD_LIMIT_DIVISOR;
import static com.hivemq.persistence.clientqueue.ClientQueuePersistenceImpl.Key;
import static com.hivemq.util.ThreadPreConditions.SINGLE_WRITER_THREAD_PREFIX;

/**
 * @author Lukas Brandl
 * @author Silvio Giebl
 */
@LazySingleton
public class ClientQueueXodusLocalPersistence extends XodusLocalPersistence implements ClientQueueLocalPersistence {

    @NotNull
    private static final Logger log = LoggerFactory.getLogger(ClientQueueXodusLocalPersistence.class);

    private static final String PERSISTENCE_NAME = "client_queue";
    private static final String PERSISTENCE_VERSION = "040000";
    private static final int LINKED_LIST_NODE_OVERHEAD = 24;


    private final @NotNull ClientQueuePersistenceSerializer serializer;

    private final @NotNull MessageDroppedService messageDroppedService;

    private final @NotNull ConcurrentHashMap<Integer, Map<Key, AtomicInteger>> queueSizeBuckets;

    private final @NotNull PublishPayloadPersistence payloadPersistence;

    private final @NotNull ConcurrentHashMap<Integer, Map<Key, LinkedList<PUBLISH>>> qos0MessageBuckets;

    private final @NotNull AtomicLong qos0MessagesMemory = new AtomicLong();
    private final long qos0MemoryLimit;


    @Inject
    ClientQueueXodusLocalPersistence(
            final @NotNull PublishPayloadPersistence payloadPersistence,
            final @NotNull EnvironmentUtil environmentUtil,
            final @NotNull LocalPersistenceFileUtil localPersistenceFileUtil,
            final @NotNull PersistenceStartup persistenceStartup,
            final @NotNull MessageDroppedService messageDroppedService) {

        super(environmentUtil, localPersistenceFileUtil, persistenceStartup, InternalConfigurations.PERSISTENCE_BUCKET_COUNT.get());

        this.serializer = new ClientQueuePersistenceSerializer(payloadPersistence);
        this.messageDroppedService = messageDroppedService;
        this.queueSizeBuckets = new ConcurrentHashMap<>();
        this.payloadPersistence = payloadPersistence;
        this.qos0MessageBuckets = new ConcurrentHashMap<>();
        this.qos0MemoryLimit = getQos0MemoryLimit();
    }

    private long getQos0MemoryLimit() {
        final long maxHeap = Runtime.getRuntime().maxMemory();
        final long maxHardLimit;

        final int hardLimitDivisor = QOS_0_MEMORY_HARD_LIMIT_DIVISOR.get();

        if (hardLimitDivisor < 1) {
            //fallback to default if config failed
            maxHardLimit = maxHeap / 4;
        } else {
            maxHardLimit = maxHeap / hardLimitDivisor;
        }
        log.debug("{} allocated for qos 0 inflight messages", Strings.convertBytes(maxHardLimit));
        return maxHardLimit;
    }

    @NotNull
    @Override
    protected String getName() {
        return PERSISTENCE_NAME;
    }

    @NotNull
    @Override
    protected String getVersion() {
        return PERSISTENCE_VERSION;
    }

    @NotNull
    @Override
    protected StoreConfig getStoreConfig() {
        return StoreConfig.WITHOUT_DUPLICATES_WITH_PREFIXING;
    }

    @NotNull
    @Override
    protected Logger getLogger() {
        return log;
    }

    @PostConstruct
    protected void postConstruct() {
        super.postConstruct();
    }

    @Override
    protected void init() {
        log.debug("Initializing payload reference count and queue sizes for {} persistence.", PERSISTENCE_NAME);

        Preconditions.checkNotNull(buckets, "Buckets must be initialized at this point");

        for (int i = 0; i < buckets.length; i++) {
            qos0MessageBuckets.put(i, new HashMap<>());
            queueSizeBuckets.put(i, new ConcurrentSkipListMap<>());
        }

        final AtomicLong nextMessageIndex = new AtomicLong(Long.MAX_VALUE / 2);

        for (final Bucket bucket : buckets) {

            bucket.getEnvironment().executeInReadonlyTransaction(txn -> {
                try (final Cursor cursor = bucket.getStore().openCursor(txn)) {
                    Key currentKey = null;
                    int queueSize = 0;
                    while (cursor.getNext()) {

                        final Key key = serializer.deserializeKeyId(cursor.getKey());

                        if (currentKey == null || !currentKey.equals(key)) {

                            if (currentKey != null && queueSize != 0) {
                                queueSizeBuckets.get(BucketUtils.getBucket(currentKey.getQueueId(), getBucketCount())).put(currentKey, new AtomicInteger(queueSize));
                            }
                            queueSize = 0;
                        }

                        currentKey = key;

                        final MessageWithID messageWithID = serializer.deserializeValue(cursor.getValue());
                        if (messageWithID instanceof PUBLISH) {
                            final long deserializeIndex = serializer.deserializeIndex(cursor.getKey());
                            if (nextMessageIndex.get() < deserializeIndex) {
                                nextMessageIndex.set(deserializeIndex);
                            }
                            final PUBLISH publish = (PUBLISH) messageWithID;
                            payloadPersistence.incrementReferenceCounterOnBootstrap(publish.getPayloadId());
                        }
                        queueSize++;

                    }

                    //we do not put if we change bucket, therefor we must check after
                    //we must check this, because a bucket may be empty
                    if (currentKey != null) {
                        if (queueSizeBuckets.get(BucketUtils.getBucket(currentKey.getQueueId(), getBucketCount())).get(currentKey) == null) {
                            queueSizeBuckets.get(BucketUtils.getBucket(currentKey.getQueueId(), getBucketCount())).put(currentKey, new AtomicInteger(queueSize));
                        }
                    }

                }
            });
        }

        ClientQueuePersistenceSerializer.NEXT_PUBLISH_NUMBER.set(nextMessageIndex.get());
    }

    @VisibleForTesting
    public long calculateQos0Size() {
        long total = 0;
        for (final Map<Key, LinkedList<PUBLISH>> map : qos0MessageBuckets.values()) {
            if (map == null) {
                continue;
            }
            for (final LinkedList<PUBLISH> messageList : map.values()) {
                if (messageList == null) {
                    continue;
                }
                total += messageList.size();
            }
        }
        return total;
    }

    @VisibleForTesting
    public long getTotalSize() {
        long total = 0;
        for (final Map<Key, AtomicInteger> map : queueSizeBuckets.values()) {
            if (map == null) {
                continue;
            }
            for (final AtomicInteger queueCount : map.values()) {
                if (queueCount == null) {
                    continue;
                }
                total += queueCount.get();
            }
        }
        return total;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(@NotNull final String queueId, final boolean shared, @NotNull final PUBLISH publish, final long max,
                    @NotNull final QueuedMessagesStrategy strategy, final int bucketIndex) {
        checkNotNull(queueId, "Queue ID must not be null");
        checkNotNull(publish, "Publish must not be null");
        checkNotNull(strategy, "Strategy must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(queueId, shared);
        if (publish.getQoS() == QoS.AT_MOST_ONCE) {
            final long currentQos0MessagesMemory = qos0MessagesMemory.get();
            if (currentQos0MessagesMemory > qos0MemoryLimit) {
                if (shared) {
                    messageDroppedService.qos0MemoryExceededShared(queueId, publish.getTopic(), 0, currentQos0MessagesMemory, qos0MemoryLimit);
                } else {
                    messageDroppedService.qos0MemoryExceeded(queueId, publish.getTopic(), 0, currentQos0MessagesMemory, qos0MemoryLimit);
                }
                payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                return;
            }
            getOrPutQos0Messages(key, bucketIndex).add(publish);
            getOrPutQueueSize(key, bucketIndex).incrementAndGet();
            increaseQos0MessagesMemory(publish.getEstimatedSizeInMemory());
            return;
        }

        final Bucket bucket = buckets[bucketIndex];

        final AtomicInteger queueSize = getOrPutQueueSize(key, bucketIndex);
        final int qos1And2QueueSize = queueSize.get() - qos0Size(key, bucketIndex);

        if (qos1And2QueueSize >= max) {
            if (strategy == QueuedMessagesStrategy.DISCARD) {
                logMessageDropped(publish, shared, queueId);
                payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                return;
            } else {

                final boolean discarded = discardOldest(bucket, key);
                if (!discarded) {
                    logMessageDropped(publish, shared, queueId);
                    payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                    return;
                }
            }
        } else {
            queueSize.incrementAndGet();
        }

        final ByteIterable keyBytes = serializer.serializeNewPublishKey(key);
        final ByteIterable valueBytes = serializer.serializePublishWithoutPacketId(publish);

        bucket.getEnvironment().executeInTransaction(txn -> bucket.getStore().put(txn, keyBytes, valueBytes));
    }

    private void logMessageDropped(@NotNull final PUBLISH publish, final boolean shared, @NotNull final String queueId) {
        if (shared) {
            messageDroppedService.queueFullShared(queueId, publish.getTopic(), publish.getQoS().getQosNumber());
        } else {
            messageDroppedService.queueFull(queueId, publish.getTopic(), publish.getQoS().getQosNumber());
        }
    }

    /**
     * @param size the amount of bytes the currently used qos 0 memory will be increased by. May be negative.
     */
    private void increaseQos0MessagesMemory(final int size) {
        if (size < 0) {
            qos0MessagesMemory.addAndGet(size - LINKED_LIST_NODE_OVERHEAD);
        } else {
            qos0MessagesMemory.addAndGet(size + LINKED_LIST_NODE_OVERHEAD);
        }
    }

    /**
     * @return true if a message was discarded, else false
     */
    private boolean discardOldest(@NotNull final Bucket bucket, @NotNull final Key key) {

        final AtomicBoolean discarded = new AtomicBoolean();
        bucket.getEnvironment().executeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                // Go to the first entry without a packet id because we don't discard in-flight messages
                iterateQueue(cursor, key, true, () -> {
                    final PUBLISH publish = (PUBLISH) serializer.deserializeValue(cursor.getValue());
                    payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                    cursor.deleteCurrent();
                    logMessageDropped(publish, key.isShared(), key.getQueueId());

                    discarded.set(true);
                    return false;
                });
            }
        });

        return discarded.get();
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public ImmutableList<PUBLISH> readNew(@NotNull final String queueId, final boolean shared, @NotNull final ImmutableIntArray packetIds,
                                          final long bytesLimit, final int bucketIndex) {
        checkNotNull(queueId, "Queue ID must not be null");
        checkNotNull(packetIds, "Packet IDs must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(queueId, shared);

        final AtomicInteger queueSize = getOrPutQueueSize(key, bucketIndex);
        if (queueSize.get() == 0) {
            return ImmutableList.of();
        }

        final LinkedList<PUBLISH> qos0Messages = getOrPutQos0Messages(key, bucketIndex);
        if (queueSize.get() == qos0Messages.size()) {
            // In case there are only qos 0 messages
            final ImmutableList.Builder<PUBLISH> publishes = ImmutableList.builder();
            int qos0MessagesFound = 0;
            while (qos0MessagesFound < packetIds.length()) {
                final PUBLISH qos0Publish = pollQos0Message(key, bucketIndex);
                if (!PublishUtil.isExpired(qos0Publish.getTimestamp(), qos0Publish.getMessageExpiryInterval())) {
                    publishes.add(qos0Publish);
                    qos0MessagesFound++;
                }
                if (qos0Messages.isEmpty()) {
                    break;
                }
            }

            return publishes.build();
        }

        final Bucket bucket = buckets[bucketIndex];
        return bucket.getEnvironment().computeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                final int countLimit = packetIds.length();
                final int[] messageCount = {0};
                final int[] packetIdIndex = {0};
                final int[] bytes = {0};
                final ImmutableList.Builder<PUBLISH> publishes = ImmutableList.builder();

                iterateQueue(cursor, key, true, () -> {
                    final ByteIterable serializedValue = cursor.getValue();
                    final PUBLISH publish = (PUBLISH) serializer.deserializeValue(serializedValue);
                    if (PublishUtil.isExpired(publish.getTimestamp(), publish.getMessageExpiryInterval())) {
                        cursor.deleteCurrent();
                        payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                        getOrPutQueueSize(key, bucketIndex).decrementAndGet();
                        //do not return here, because we could have a QoS 0 message left
                    } else {

                        final int packetId = packetIds.get(packetIdIndex[0]);
                        publish.setPacketIdentifier(packetId);
                        bucket.getStore()
                                .put(txn, cursor.getKey(), serializer.serializeAndSetPacketId(serializedValue, packetId));

                        publishes.add(publish);
                        packetIdIndex[0]++;
                        messageCount[0]++;
                        bytes[0] += publish.getEstimatedSizeInMemory();
                        if ((messageCount[0] == countLimit) || (bytes[0] > bytesLimit)) {
                            return false;
                        }
                    }

                    // Add a qos 0 message
                    if (!qos0Messages.isEmpty()) {
                        final PUBLISH qos0Publish = pollQos0Message(key, bucketIndex);
                        if (!PublishUtil.isExpired(qos0Publish.getTimestamp(), qos0Publish.getMessageExpiryInterval())) {
                            publishes.add(qos0Publish);
                            messageCount[0]++;
                            bytes[0] += qos0Publish.getEstimatedSizeInMemory();
                        }
                    }
                    return (messageCount[0] != countLimit) && (bytes[0] <= bytesLimit);
                });
                return publishes.build();
            }
        });
    }

    @NotNull
    private PUBLISH pollQos0Message(@NotNull final Key key, final int bucketIndex) {
        final LinkedList<PUBLISH> qos0Messages = getOrPutQos0Messages(key, bucketIndex);
        final PUBLISH qos0Publish = qos0Messages.get(0);
        qos0Messages.remove(0);
        getOrPutQueueSize(key, bucketIndex).decrementAndGet();
        increaseQos0MessagesMemory(qos0Publish.getEstimatedSizeInMemory() * -1);
        payloadPersistence.decrementReferenceCounter(qos0Publish.getPayloadId());
        return qos0Publish;
    }


    @NotNull
    @Override
    public ImmutableList<MessageWithID> readInflight(@NotNull final String client, final boolean shared, final int batchSize,
                                                     final long bytesLimit, final int bucketIndex) {
        checkNotNull(client, "client id must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(client, shared);

        final Bucket bucket = buckets[bucketIndex];

        return bucket.getEnvironment().computeInReadonlyTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                final int[] count = {0};
                final int[] bytes = {0};
                final ImmutableList.Builder<MessageWithID> messages = ImmutableList.builder();

                iterateQueue(cursor, key, false, () -> {
                    final ByteIterable serializedValue = cursor.getValue();
                    final MessageWithID message = serializer.deserializeValue(serializedValue);

                    // This works because in-flight messages are always first in the queue
                    if (message.getPacketIdentifier() == ClientQueuePersistenceSerializer.NO_PACKET_ID) {
                        return false;
                    }

                    messages.add(message);

                    count[0]++;

                    if (message instanceof PUBLISH) {
                        bytes[0] += ((PUBLISH) message).getEstimatedSizeInMemory();
                        ((PUBLISH) message).setDuplicateDelivery(true);
                    }

                    return (count[0] != batchSize) && (bytes[0] <= bytesLimit);
                });
                return messages.build();
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public String replace(@NotNull final String client, @NotNull final PUBREL pubrel, final int bucketIndex) {
        checkNotNull(client, "client id must not be null");
        checkNotNull(pubrel, "pubrel must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(client, false);

        final Bucket bucket = buckets[bucketIndex];
        final ByteIterable serializedPubRel = serializer.serializePubRel(pubrel);

        return bucket.getEnvironment().computeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                final boolean[] packetIdFound = new boolean[1];
                final String[] replacedId = new String[1];

                iterateQueue(cursor, key, false, () -> {
                    final MessageWithID message = serializer.deserializeValue(cursor.getValue());
                    final int packetId = message.getPacketIdentifier();
                    if (packetId == pubrel.getPacketIdentifier()) {
                        packetIdFound[0] = true;
                        if (message instanceof PUBLISH) {
                            final PUBLISH publish = (PUBLISH) message;
                            payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                            bucket.getStore().put(txn, cursor.getKey(), serializedPubRel);
                            replacedId[0] = publish.getUniqueId();
                        }
                        bucket.getStore().put(txn, cursor.getKey(), serializedPubRel);
                        return false;
                    }
                    return packetId != ClientQueuePersistenceSerializer.NO_PACKET_ID;
                });
                if (!packetIdFound[0]) {
                    getOrPutQueueSize(key, bucketIndex).incrementAndGet();
                    bucket.getStore().put(txn, serializer.serializeUnknownPubRelKey(key), serializedPubRel);
                }
                return replacedId[0];
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String remove(@NotNull final String client, final int packetId, final int bucketIndex) {
        return remove(client, packetId, null, bucketIndex);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public String remove(@NotNull final String client, final int packetId, @Nullable final String uniqueId, final int bucketIndex) {
        checkNotNull(client, "client id must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(client, false);

        final Bucket bucket = buckets[bucketIndex];
        return bucket.getEnvironment().computeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                final String[] result = {null};

                iterateQueue(cursor, key, false, () -> {
                    final MessageWithID message = serializer.deserializeValue(cursor.getValue());
                    if (message.getPacketIdentifier() == packetId) {
                        String removedId = null;
                        if (message instanceof PUBLISH) {
                            final PUBLISH publish = (PUBLISH) message;
                            if (uniqueId != null && !uniqueId.equals(publish.getUniqueId())) {
                                return false;
                            }
                            payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                            removedId = publish.getUniqueId();
                        }
                        cursor.deleteCurrent();
                        getOrPutQueueSize(key, bucketIndex).decrementAndGet();
                        result[0] = removedId;
                        return false;
                    }
                    return true;
                });
                return result[0];
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size(@NotNull final String queueId, final boolean shared, final int bucketIndex) {
        checkNotNull(queueId, "Queue ID must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX); // QueueSizes are not thread save
        final Key key = new Key(queueId, shared);
        final AtomicInteger queueSize = queueSizeBuckets.get(bucketIndex).get(key);
        return (queueSize == null) ? 0 : queueSize.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int qos0Size(@NotNull final String queueId, final boolean shared, final int bucketIndex) {
        checkNotNull(queueId, "Queue ID must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX); // QueueSizes are not thread save
        final Key key = new Key(queueId, shared);
        return qos0Size(key, bucketIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear(@NotNull final String queueId, final boolean shared, final int bucketIndex) {
        checkNotNull(queueId, "Queue ID must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(queueId, shared);

        final Bucket bucket = buckets[bucketIndex];
        bucket.getEnvironment().executeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                iterateQueue(cursor, key, false, () -> {
                    final MessageWithID message = serializer.deserializeValue(cursor.getValue());
                    if (message instanceof PUBLISH) {
                        payloadPersistence.decrementReferenceCounter(((PUBLISH) message).getPayloadId());
                    }
                    cursor.deleteCurrent();
                    return true;
                });
            }
        });

        final LinkedList<PUBLISH> qos0Messages = getOrPutQos0Messages(key, bucketIndex);
        for (final PUBLISH qos0Message : qos0Messages) {
            increaseQos0MessagesMemory(qos0Message.getEstimatedSizeInMemory() * -1);
            payloadPersistence.decrementReferenceCounter(qos0Message.getPayloadId());
        }
        qos0MessageBuckets.get(bucketIndex).remove(key);
        queueSizeBuckets.get(bucketIndex).remove(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAllQos0Messages(@NotNull final String queueId, final boolean shared, final int bucketIndex) {
        checkNotNull(queueId, "Queue id must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(queueId, shared);
        final LinkedList<PUBLISH> qos0Messages = getOrPutQos0Messages(key, bucketIndex);
        final Iterator<PUBLISH> iterator = qos0Messages.iterator();
        while (iterator.hasNext()) {
            final PUBLISH publish = iterator.next();
            iterator.remove();
            payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
            getOrPutQueueSize(key, bucketIndex).decrementAndGet();
            increaseQos0MessagesMemory(publish.getEstimatedSizeInMemory() * -1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public ImmutableSet<String> cleanUp(final int bucketIndex) {
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        if (super.stopped.get()) {
            return ImmutableSet.of();
        }

        final ImmutableSet.Builder<String> sharedQueues = ImmutableSet.builder();
        final Map<Key, AtomicInteger> bucketClients = queueSizeBuckets.get(bucketIndex);

        for (final Key bucketKey : bucketClients.keySet()) {
            if (bucketKey.isShared()) {
                sharedQueues.add(bucketKey.getQueueId());
            }
            cleanExpiredMessages(bucketKey, bucketIndex);
        }

        return sharedQueues.build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeShared(@NotNull final String sharedSubscription, @NotNull final String uniqueId, final int bucketIndex) {
        checkNotNull(sharedSubscription, "Shared subscription must not be null");
        checkNotNull(uniqueId, "Unique id must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(sharedSubscription, true);

        final Bucket bucket = buckets[bucketIndex];
        bucket.getEnvironment().executeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                iterateQueue(cursor, key, false, () -> {
                    final MessageWithID message = serializer.deserializeValue(cursor.getValue());

                    if (message instanceof PUBLISH) {
                        final PUBLISH publish = (PUBLISH) message;
                        if (!uniqueId.equals(publish.getUniqueId())) {
                            return true;
                        }
                        payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                        cursor.deleteCurrent();
                        getOrPutQueueSize(key, bucketIndex).decrementAndGet();
                    }
                    return false;
                });
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeInFlightMarker(@NotNull final String sharedSubscription, @NotNull final String uniqueId, final int bucketIndex) {
        checkNotNull(sharedSubscription, "Shared subscription must not be null");
        checkNotNull(uniqueId, "Unique id must not be null");
        ThreadPreConditions.startsWith(SINGLE_WRITER_THREAD_PREFIX);

        final Key key = new Key(sharedSubscription, true);

        final Bucket bucket = buckets[bucketIndex];
        bucket.getEnvironment().executeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                iterateQueue(cursor, key, false, () -> {
                    final MessageWithID message = serializer.deserializeValue(cursor.getValue());

                    if (message instanceof PUBLISH) {
                        final PUBLISH publish = (PUBLISH) message;
                        if (!uniqueId.equals(publish.getUniqueId())) {
                            return true;
                        }
                        bucket.getStore().put(txn, cursor.getKey(), serializer.serializePublishWithoutPacketId(publish));
                    }
                    return false;
                });
            }
        });
    }

    public @NotNull ConcurrentHashMap<Integer, Map<Key, AtomicInteger>> getQueueSizeBuckets() {
        return queueSizeBuckets;
    }

    private void cleanExpiredMessages(@NotNull final Key key, final int bucketIndex) {
        final LinkedList<PUBLISH> qos0Messages = getOrPutQos0Messages(key, bucketIndex);
        final Iterator<PUBLISH> iterator = qos0Messages.iterator();
        while (iterator.hasNext()) {
            final PUBLISH qos0Message = iterator.next();
            if (PublishUtil.isExpired(qos0Message.getTimestamp(), qos0Message.getMessageExpiryInterval())) {
                getOrPutQueueSize(key, bucketIndex).decrementAndGet();
                increaseQos0MessagesMemory(qos0Message.getEstimatedSizeInMemory() * -1);
                payloadPersistence.decrementReferenceCounter(qos0Message.getPayloadId());
                iterator.remove();
            }
        }

        final Bucket bucket = buckets[bucketIndex];

        bucket.getEnvironment().executeInExclusiveTransaction(txn -> {
            try (final Cursor cursor = bucket.getStore().openCursor(txn)) {

                iterateQueue(cursor, key, false, () -> {
                    final ByteIterable serializedValue = cursor.getValue();
                    final MessageWithID message = serializer.deserializeValue(serializedValue);
                    if (!(message instanceof PUBLISH)) {
                        return true;
                    }
                    final PUBLISH publish = (PUBLISH) message;
                    if (PublishUtil.isExpired(publish) && !(publish.getQoS() == QoS.EXACTLY_ONCE && publish.getPacketIdentifier() > 0)) {
                        payloadPersistence.decrementReferenceCounter(publish.getPayloadId());
                        getOrPutQueueSize(key, bucketIndex).decrementAndGet();
                        cursor.deleteCurrent();
                    }
                    return true;
                });
            }
        });
    }

    private int skipPrefix(@NotNull final ByteIterable serializedKey, @NotNull final Cursor cursor) {
        int comparison = serializer.compareClientId(serializedKey, cursor.getKey());
        while (comparison == ClientQueuePersistenceSerializer.CLIENT_ID_SAME_PREFIX) {
            comparison = compareNextClientId(serializedKey, cursor);
        }
        return comparison;
    }

    private int skipWithId(
            @NotNull final ByteIterable serializedKey, @NotNull final Cursor cursor, int comparison) {
        while (comparison == ClientQueuePersistenceSerializer.CLIENT_ID_MATCH) {
            if (serializer.deserializePacketId(cursor.getValue()) == ClientQueuePersistenceSerializer.NO_PACKET_ID) {
                break;
            }
            comparison = compareNextClientId(serializedKey, cursor);
        }
        return comparison;
    }

    private int compareNextClientId(@NotNull final ByteIterable serializedClientId, @NotNull final Cursor cursor) {
        if (!cursor.getNext()) {
            return ClientQueuePersistenceSerializer.CLIENT_ID_NO_MATCH;
        }
        return serializer.compareClientId(serializedClientId, cursor.getKey());
    }

    /**
     * Move the cursor to every position of the client id order and calls the given callback.
     */
    private void iterateQueue(final Cursor cursor, @NotNull final Key key, final boolean skipWithId, @NotNull final Callback callback) {
        final ByteIterable serializedKey = serializer.serializeKey(key);

        if (cursor.getSearchKeyRange(serializedKey) == null) {
            return;
        }
        int comparison = skipPrefix(serializedKey, cursor);
        if (skipWithId) {
            comparison = skipWithId(serializedKey, cursor, comparison);
        }
        while (comparison == ClientQueuePersistenceSerializer.CLIENT_ID_MATCH) {
            if (!callback.call()) {
                return;
            }
            comparison = compareNextClientId(serializedKey, cursor);
        }
    }

    private interface Callback {
        boolean call();
    }

    @NotNull
    private AtomicInteger getOrPutQueueSize(@NotNull final Key key, final int bucketIndex) {
        final Map<Key, AtomicInteger> queueSizeBucket = queueSizeBuckets.get(bucketIndex);
        final AtomicInteger queueSize = queueSizeBucket.get(key);
        if (queueSize != null) {
            return queueSize;
        }
        final AtomicInteger newQueueSize = new AtomicInteger();
        queueSizeBucket.put(key, newQueueSize);
        return newQueueSize;
    }

    @NotNull
    private LinkedList<PUBLISH> getOrPutQos0Messages(@NotNull final Key key, final int bucketIndex) {
        final Map<Key, LinkedList<PUBLISH>> bucketMessages = qos0MessageBuckets.get(bucketIndex);
        LinkedList<PUBLISH> publishes = bucketMessages.get(key);
        if (publishes != null) {
            return publishes;
        }
        publishes = new LinkedList<>();
        bucketMessages.put(key, publishes);
        return publishes;
    }

    private int qos0Size(@NotNull final Key key, final int bucketIndex) {
        final Map<Key, LinkedList<PUBLISH>> bucketMessages = qos0MessageBuckets.get(bucketIndex);
        final LinkedList<PUBLISH> publishes = bucketMessages.get(key);
        if (publishes != null) {
            return publishes.size();
        }
        return 0;
    }
}
