/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.broker.service.persistent;

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.bookkeeper.mledger.AsyncCallbacks;
import org.apache.bookkeeper.mledger.AsyncCallbacks.AddEntryCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.CloseCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.DeleteCursorCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.OpenCursorCallback;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedCursor.IndividualDeletedEntries;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.ManagedLedgerFencedException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.ManagedCursorImpl;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.internal.Lists;
import com.carrotsearch.hppc.ObjectObjectHashMap;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.yahoo.pulsar.broker.admin.AdminResource;
import com.yahoo.pulsar.broker.service.BrokerService;
import com.yahoo.pulsar.broker.service.BrokerServiceException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.ConsumerBusyException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.NamingException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.PersistenceException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.ServerMetadataException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.SubscriptionBusyException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.TopicBusyException;
import com.yahoo.pulsar.broker.service.BrokerServiceException.TopicFencedException;
import com.yahoo.pulsar.broker.service.Consumer;
import com.yahoo.pulsar.broker.service.Producer;
import com.yahoo.pulsar.broker.service.ServerCnx;
import com.yahoo.pulsar.broker.service.Topic;
import com.yahoo.pulsar.broker.stats.ClusterReplicationMetrics;
import com.yahoo.pulsar.broker.stats.NamespaceStats;
import com.yahoo.pulsar.broker.stats.ReplicationMetrics;
import com.yahoo.pulsar.client.impl.MessageImpl;
import com.yahoo.pulsar.client.util.FutureUtil;
import com.yahoo.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import com.yahoo.pulsar.common.naming.DestinationName;
import com.yahoo.pulsar.common.policies.data.BacklogQuota;
import com.yahoo.pulsar.common.policies.data.ConsumerStats;
import com.yahoo.pulsar.common.policies.data.PersistentSubscriptionStats;
import com.yahoo.pulsar.common.policies.data.PersistentTopicInternalStats;
import com.yahoo.pulsar.common.policies.data.PersistentTopicInternalStats.CursorStats;
import com.yahoo.pulsar.common.policies.data.PersistentTopicInternalStats.LedgerInfo;
import com.yahoo.pulsar.common.policies.data.PersistentTopicStats;
import com.yahoo.pulsar.common.policies.data.Policies;
import com.yahoo.pulsar.common.policies.data.PublisherStats;
import com.yahoo.pulsar.common.policies.data.ReplicatorStats;
import com.yahoo.pulsar.common.policies.data.loadbalancer.NamespaceBundleStats;
import com.yahoo.pulsar.common.util.Codec;
import com.yahoo.pulsar.common.util.collections.ConcurrentOpenHashMap;
import com.yahoo.pulsar.common.util.collections.ConcurrentOpenHashSet;
import com.yahoo.pulsar.utils.StatsOutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;

public class PersistentTopic implements Topic, AddEntryCallback {
    private final String topic;

    // Managed ledger associated with the topic
    private final ManagedLedger ledger;

    // Producers currently connected to this topic
    private final ConcurrentOpenHashSet<Producer> producers;

    // Subscriptions to this topic
    private final ConcurrentOpenHashMap<String, PersistentSubscription> subscriptions;

    private final ConcurrentOpenHashMap<String, PersistentReplicator> replicators;

    private final BrokerService brokerService;

    private volatile boolean isFenced;

    protected AtomicLong usageCount = new AtomicLong(0);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Prefix for replication cursors
    public final String replicatorPrefix;

    private static final double MESSAGE_EXPIRY_THRESHOLD = 1.5;

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // Timestamp of when this topic was last seen active
    private volatile long lastActive;

    private static final FastThreadLocal<TopicStats> threadLocalTopicStats = new FastThreadLocal<TopicStats>() {
        @Override
        protected TopicStats initialValue() {
            return new TopicStats();
        }
    };

    private static class TopicStats {
        public double averageMsgSize;
        public double aggMsgRateIn;
        public double aggMsgThroughputIn;
        public double aggMsgRateOut;
        public double aggMsgThroughputOut;
        public final ObjectObjectHashMap<String, PublisherStats> remotePublishersStats;

        public TopicStats() {
            remotePublishersStats = new ObjectObjectHashMap<String, PublisherStats>();
            reset();
        }

        public void reset() {
            averageMsgSize = 0;
            aggMsgRateIn = 0;
            aggMsgThroughputIn = 0;
            aggMsgRateOut = 0;
            aggMsgThroughputOut = 0;
            remotePublishersStats.clear();
        }
    }

    public PersistentTopic(String topic, ManagedLedger ledger, BrokerService brokerService) {
        this.topic = topic;
        this.ledger = ledger;
        this.brokerService = brokerService;
        this.producers = new ConcurrentOpenHashSet<Producer>();
        this.subscriptions = new ConcurrentOpenHashMap<>();
        this.replicators = new ConcurrentOpenHashMap<>();
        this.isFenced = false;
        this.replicatorPrefix = brokerService.pulsar().getConfiguration().getReplicatorPrefix();

        for (ManagedCursor cursor : ledger.getCursors()) {
            if (cursor.getName().startsWith(replicatorPrefix)) {
                String localCluster = brokerService.pulsar().getConfiguration().getClusterName();
                String remoteCluster = PersistentReplicator.getRemoteCluster(cursor.getName());
                replicators.put(remoteCluster,
                        new PersistentReplicator(this, cursor, localCluster, remoteCluster, brokerService));
            } else {
                final String cursorName = Codec.decode(cursor.getName());
                subscriptions.put(cursorName, new PersistentSubscription(this, cursor));
                // subscription-cursor gets activated by default: deactivate as there is no active subscription right
                // now
                subscriptions.get(cursorName).deactivateCursor();
            }
        }
        this.lastActive = System.nanoTime();
    }

    @Override
    public void publishMessage(ByteBuf headersAndPayload, PublishCallback callback) {
        ledger.asyncAddEntry(headersAndPayload, this, callback);
    }

    @Override
    public void addComplete(Position pos, Object ctx) {
        PublishCallback callback = (PublishCallback) ctx;
        PositionImpl position = (PositionImpl) pos;
        // Message has been successfully persisted
        callback.completed(null, position.getLedgerId(), position.getEntryId());
        position.recycle();
    }

    @Override
    public void addFailed(ManagedLedgerException exception, Object ctx) {
        PublishCallback callback = (PublishCallback) ctx;
        log.error("[{}] Failed to persist msg in store: {}", topic, exception.getMessage());
        callback.completed(new PersistenceException(exception), -1, -1);

        if (exception instanceof ManagedLedgerFencedException) {
            // If the managed ledger has been fenced, we cannot continue using it. We need to close and reopen
            close();
        }
    }

    @Override
    public void addProducer(Producer producer) throws BrokerServiceException {
        checkArgument(producer.getTopic() == this);

        lock.readLock().lock();
        try {
            if (isFenced) {
                log.warn("[{}] Attempting to add producer to a fenced topic", topic);
                throw new TopicFencedException("Topic is temporarily unavailable");
            }

            if (log.isDebugEnabled()) {
                log.debug("[{}] {} Got request to create producer ", topic, producer.getProducerName());
            }

            if (!producers.add(producer)) {
                throw new NamingException(
                        "Producer with name '" + producer.getProducerName() + "' is already connected to topic");
            }

            usageCount.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Added producer -- count: {}", topic, producer.getProducerName(), usageCount.get());
            }

            // Start replication producers if not already
            startReplProducers();
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean hasLocalProducers() {
        AtomicBoolean foundLocal = new AtomicBoolean(false);
        producers.forEach(producer -> {
            if (!producer.isRemote()) {
                foundLocal.set(true);
            }
        });

        return foundLocal.get();
    }

    private boolean hasRemoteProducers() {
        AtomicBoolean foundRemote = new AtomicBoolean(false);
        producers.forEach(producer -> {
            if (producer.isRemote()) {
                foundRemote.set(true);
            }
        });

        return foundRemote.get();
    }

    private void startReplProducers() {
        replicators.forEach((region, replicator) -> replicator.startProducer());
    }

    public CompletableFuture<Void> stopReplProducers() {
        List<CompletableFuture<Void>> closeFutures = Lists.newArrayList();
        replicators.forEach((region, replicator) -> closeFutures.add(replicator.disconnect()));
        return FutureUtil.waitForAll(closeFutures);
    }

    private synchronized CompletableFuture<Void> closeReplProducersIfNoBacklog() {
        List<CompletableFuture<Void>> closeFutures = Lists.newArrayList();
        replicators.forEach((region, replicator) -> closeFutures.add(replicator.disconnect(true)));
        return FutureUtil.waitForAll(closeFutures);
    }

    @Override
    public void removeProducer(Producer producer) {
        checkArgument(producer.getTopic() == this);
        if (producers.remove(producer)) {
            // decrement usage only if this was a valid producer close
            usageCount.decrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Removed producer -- count: {}", topic, producer.getProducerName(),
                        usageCount.get());
            }
            lastActive = System.nanoTime();
        }
    }

    @Override
    public CompletableFuture<Consumer> subscribe(final ServerCnx cnx, String subscriptionName, long consumerId,
            SubType subType, String consumerName) {

        final CompletableFuture<Consumer> future = new CompletableFuture<>();

        if (subscriptionName.startsWith(replicatorPrefix)) {
            log.warn("[{}] Failed to create subscription for {}", topic, subscriptionName);
            future.completeExceptionally(new NamingException("Subscription with reserved subscription name attempted"));
            return future;
        }

        lock.readLock().lock();
        try {
            if (isFenced) {
                log.warn("[{}] Attempting to subscribe to a fenced topic", topic);
                future.completeExceptionally(new TopicFencedException("Topic is temporarily unavailable"));
                return future;
            }
            usageCount.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] [{}] Added consumer -- count: {}", topic, subscriptionName, consumerName,
                        usageCount.get());
            }
        } finally {
            lock.readLock().unlock();
        }

        ledger.asyncOpenCursor(Codec.encode(subscriptionName), new OpenCursorCallback() {
            @Override
            public void openCursorComplete(ManagedCursor cursor, Object ctx) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}][{}] Opened cursor for {} {}", topic, subscriptionName, consumerId, consumerName);
                }

                try {
                    PersistentSubscription subscription = subscriptions.computeIfAbsent(subscriptionName,
                            name -> new PersistentSubscription(PersistentTopic.this, cursor));
                    
                    Consumer consumer = new Consumer(subscription, subType, consumerId, consumerName,
                            brokerService.pulsar().getConfiguration().getMaxUnackedMessagesPerConsumer(), cnx,
                            cnx.getRole());
                    subscription.addConsumer(consumer);
                    if (!cnx.isActive()) {
                        consumer.close();
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] [{}] [{}] Subscribe failed -- count: {}", topic, subscriptionName,
                                    consumer.consumerName(), usageCount.get());
                        }
                        future.completeExceptionally(
                                new BrokerServiceException("Connection was closed while the opening the cursor "));
                    } else {
                        log.info("[{}][{}] Created new subscription for {}", topic, subscriptionName, consumerId);
                        future.complete(consumer);
                    }
                } catch (BrokerServiceException e) {
                    if (e instanceof ConsumerBusyException) {
                        log.warn("[{}][{}] Consumer {} {} already connected", topic, subscriptionName, consumerId,
                                consumerName);
                    } else if (e instanceof SubscriptionBusyException) {
                        log.warn("[{}][{}] {}", topic, subscriptionName, e.getMessage());
                    }

                    usageCount.decrementAndGet();
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void openCursorFailed(ManagedLedgerException exception, Object ctx) {
                log.warn("[{}] Failed to create subscription for {}", topic, subscriptionName);
                usageCount.decrementAndGet();
                future.completeExceptionally(new PersistenceException(exception));
            }
        }, null);

        return future;
    }

    /**
     * Delete the cursor ledger for a given subscription
     *
     * @param subscriptionName
     *            Subscription for which the cursor ledger is to be deleted
     * @return Completable future indicating completion of unsubscribe operation Completed exceptionally with:
     *         ManagedLedgerException if cursor ledger delete fails
     */
    @Override
    public CompletableFuture<Void> unsubscribe(String subscriptionName) {
        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();

        ledger.asyncDeleteCursor(Codec.encode(subscriptionName), new DeleteCursorCallback() {
            @Override
            public void deleteCursorComplete(Object ctx) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}][{}] Cursor deleted successfully", topic, subscriptionName);
                }
                subscriptions.remove(subscriptionName);
                unsubscribeFuture.complete(null);
                lastActive = System.nanoTime();
            }

            @Override
            public void deleteCursorFailed(ManagedLedgerException exception, Object ctx) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}][{}] Error deleting cursor for subscription", topic, subscriptionName, exception);
                }
                unsubscribeFuture.completeExceptionally(new PersistenceException(exception));
            }
        }, null);

        return unsubscribeFuture;
    }

    /**
     * Delete the managed ledger associated with this topic
     *
     * @return Completable future indicating completion of delete operation Completed exceptionally with:
     *         IllegalStateException if topic is still active ManagedLedgerException if ledger delete operation fails
     */
    @Override
    public CompletableFuture<Void> delete() {
        return delete(false);
    }

    /**
     * Delete the managed ledger associated with this topic
     *
     * @param failIfHasSubscriptions
     *            Flag indicating whether delete should succeed if topic still has unconnected subscriptions. Set to
     *            false when called from admin API (it will delete the subs too), and set to true when called from GC
     *            thread
     *
     * @return Completable future indicating completion of delete operation Completed exceptionally with:
     *         IllegalStateException if topic is still active ManagedLedgerException if ledger delete operation fails
     */
    private CompletableFuture<Void> delete(boolean failIfHasSubscriptions) {
        CompletableFuture<Void> deleteFuture = new CompletableFuture<>();

        lock.writeLock().lock();
        try {
            if (isFenced) {
                log.warn("[{}] Topic is already being closed or deleted", topic);
                deleteFuture.completeExceptionally(new TopicFencedException("Topic is already fenced"));
                return deleteFuture;
            }
            if (usageCount.get() == 0) {
                isFenced = true;

                List<CompletableFuture<Void>> futures = Lists.newArrayList();

                if (failIfHasSubscriptions) {
                    if (!subscriptions.isEmpty()) {
                        isFenced = false;
                        deleteFuture.completeExceptionally(new TopicBusyException("Topic has subscriptions"));
                        return deleteFuture;
                    }
                } else {
                    subscriptions.forEach((s, sub) -> futures.add(sub.delete()));
                }

                FutureUtil.waitForAll(futures).whenComplete((v, ex) -> {
                    if (ex != null) {
                        log.error("[{}] Error deleting topic", topic, ex);
                        isFenced = false;
                        deleteFuture.completeExceptionally(ex);
                    } else {
                        ledger.asyncDelete(new AsyncCallbacks.DeleteLedgerCallback() {
                            @Override
                            public void deleteLedgerComplete(Object ctx) {
                                brokerService.removeTopicFromCache(topic);
                                log.info("[{}] Topic deleted", topic);
                                deleteFuture.complete(null);
                            }

                            @Override
                            public void deleteLedgerFailed(ManagedLedgerException exception, Object ctx) {
                                isFenced = false;
                                log.error("[{}] Error deleting topic", topic, exception);
                                deleteFuture.completeExceptionally(new PersistenceException(exception));
                            }
                        }, null);
                    }
                });
            } else {
                deleteFuture.completeExceptionally(
                        new TopicBusyException("Topic has " + usageCount.get() + " connected producers/consumers"));
            }
        } finally {
            lock.writeLock().unlock();
        }

        return deleteFuture;
    }

    /**
     * Close this topic - close all producers and subscriptions associated with this topic
     *
     * @return Completable future indicating completion of close operation
     */
    @Override
    public CompletableFuture<Void> close() {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();

        lock.writeLock().lock();
        try {
            if (!isFenced) {
                isFenced = true;
            } else {
                log.warn("[{}] Topic is already being closed or deleted", topic);
                closeFuture.completeExceptionally(new TopicFencedException("Topic is already fenced"));
                return closeFuture;
            }
        } finally {
            lock.writeLock().unlock();
        }

        List<CompletableFuture<Void>> futures = Lists.newArrayList();

        replicators.forEach((cluster, replicator) -> futures.add(replicator.disconnect()));
        producers.forEach(producer -> futures.add(producer.disconnect()));
        subscriptions.forEach((s, sub) -> futures.add(sub.disconnect()));

        FutureUtil.waitForAll(futures).thenRun(() -> {
            // After having disconnected all producers/consumers, close the managed ledger
            ledger.asyncClose(new CloseCallback() {
                @Override
                public void closeComplete(Object ctx) {
                    // Everything is now closed, remove the topic from map
                    brokerService.removeTopicFromCache(topic);

                    log.info("[{}] Topic closed", topic);
                    closeFuture.complete(null);
                }

                @Override
                public void closeFailed(ManagedLedgerException exception, Object ctx) {
                    log.error("[{}] Failed to close managed ledger, proceeding anyway.", topic, exception);
                    brokerService.removeTopicFromCache(topic);
                    closeFuture.complete(null);
                }
            }, null);
        }).exceptionally(exception -> {
            log.error("[{}] Error closing topic", topic, exception);
            isFenced = false;
            closeFuture.completeExceptionally(exception);
            return null;
        });

        return closeFuture;
    }

    @Override
    public CompletableFuture<Void> checkReplication() {
        DestinationName name = DestinationName.get(topic);
        if (!name.isGlobal()) {
            return CompletableFuture.completedFuture(null);
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] Checking replication status", name);
        }

        Policies policies = null;
        try {
            policies = brokerService.pulsar().getConfigurationCache().policiesCache()
                    .get(AdminResource.path("policies", name.getNamespace()))
                    .orElseThrow(() -> new KeeperException.NoNodeException());
        } catch (Exception e) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new ServerMetadataException(e));
            return future;
        }

        final int newMessageTTLinSeconds = policies.message_ttl_in_seconds;

        Set<String> configuredClusters;
        if (policies.replication_clusters != null) {
            configuredClusters = Sets.newTreeSet(policies.replication_clusters);
        } else {
            configuredClusters = Collections.emptySet();
        }

        String localCluster = brokerService.pulsar().getConfiguration().getClusterName();

        List<CompletableFuture<Void>> futures = Lists.newArrayList();

        // Check for missing replicators
        for (String cluster : configuredClusters) {
            if (cluster.equals(localCluster)) {
                continue;
            }

            if (!replicators.containsKey(cluster)) {
                futures.add(startReplicator(cluster));
            }
        }

        // Check for replicators to be stopped
        replicators.forEach((cluster, replicator) -> {
            // Update message TTL
            replicator.updateMessageTTL(newMessageTTLinSeconds);

            if (!cluster.equals(localCluster)) {
                if (!configuredClusters.contains(cluster)) {
                    futures.add(removeReplicator(cluster));
                }
            }
        });

        return FutureUtil.waitForAll(futures);
    }

    @Override
    public void checkMessageExpiry() {
        DestinationName name = DestinationName.get(topic);
        Policies policies;
        try {
            policies = brokerService.pulsar().getConfigurationCache().policiesCache()
                    .get(AdminResource.path("policies", name.getNamespace()))
                    .orElseThrow(() -> new KeeperException.NoNodeException());
            if (policies.message_ttl_in_seconds != 0) {
                subscriptions.forEach((subName, sub) -> sub.expireMessages(policies.message_ttl_in_seconds));
                replicators.forEach((region, replicator) -> replicator.expireMessages(policies.message_ttl_in_seconds));
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Error getting policies", topic);
            }
        }
    }

    CompletableFuture<Void> startReplicator(String remoteCluster) {
        log.info("[{}] Starting replicator to remote: {}", topic, remoteCluster);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        String name = PersistentReplicator.getReplicatorName(replicatorPrefix, remoteCluster);
        ledger.asyncOpenCursor(name, new OpenCursorCallback() {
            @Override
            public void openCursorComplete(ManagedCursor cursor, Object ctx) {
                String localCluster = brokerService.pulsar().getConfiguration().getClusterName();
                replicators.computeIfAbsent(remoteCluster, r -> new PersistentReplicator(PersistentTopic.this, cursor, localCluster,
                        remoteCluster, brokerService));
                future.complete(null);
            }

            @Override
            public void openCursorFailed(ManagedLedgerException exception, Object ctx) {
                future.completeExceptionally(new PersistenceException(exception));
            }

        }, null);

        return future;
    }

    CompletableFuture<Void> removeReplicator(String remoteCluster) {
        log.info("[{}] Removing replicator to {}", topic, remoteCluster);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        String name = PersistentReplicator.getReplicatorName(replicatorPrefix, remoteCluster);

        replicators.get(remoteCluster).disconnect().thenRun(() -> {

            ledger.asyncDeleteCursor(name, new DeleteCursorCallback() {
                @Override
                public void deleteCursorComplete(Object ctx) {
                    replicators.remove(remoteCluster);
                    future.complete(null);
                }

                @Override
                public void deleteCursorFailed(ManagedLedgerException exception, Object ctx) {
                    log.error("[{}] Failed to delete cursor {}", topic, name);
                    // Connect the producers back
                    replicators.get(remoteCluster).startProducer();
                    future.completeExceptionally(new PersistenceException(exception));
                }
            }, null);

        }).exceptionally(e -> {
            log.error("[{}] Failed to close replication producer {}", topic, name);
            future.completeExceptionally(e);
            return null;
        });

        return future;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this).add("topic", topic).toString();
    }

    @Override
    public ConcurrentOpenHashSet<Producer> getProducers() {
        return producers;
    }

    @Override
    public ConcurrentOpenHashMap<String, PersistentSubscription> getSubscriptions() {
        return subscriptions;
    }

    public PersistentSubscription getPersistentSubscription(String subscriptionName) {
        return subscriptions.get(subscriptionName);
    }

    public BrokerService getBrokerService() {
        return brokerService;
    }

    public ConcurrentOpenHashMap<String, PersistentReplicator> getReplicators() {
        return replicators;
    }

    public PersistentReplicator getPersistentReplicator(String remoteCluster) {
        return replicators.get(remoteCluster);
    }

    @Override
    public String getName() {
        return topic;
    }

    public ManagedLedger getManagedLedger() {
        return ledger;
    }

    public void updateRates(NamespaceStats nsStats, NamespaceBundleStats bundleStats, StatsOutputStream destStatsStream,
            ClusterReplicationMetrics replStats, String namespace) {

        TopicStats topicStats = threadLocalTopicStats.get();
        topicStats.reset();

        replicators.forEach((region, replicator) -> replicator.updateRates());

        nsStats.producerCount += producers.size();
        bundleStats.producerCount += producers.size();
        destStatsStream.startObject(topic);

        producers.forEach(producer -> {
            producer.updateRates();
            PublisherStats publisherStats = producer.getStats();

            topicStats.aggMsgRateIn += publisherStats.msgRateIn;
            topicStats.aggMsgThroughputIn += publisherStats.msgThroughputIn;

            if (producer.isRemote()) {
                topicStats.remotePublishersStats.put(producer.getRemoteCluster(), publisherStats);
            }
        });

        // Creating publishers object for backward compatibility
        destStatsStream.startList("publishers");
        destStatsStream.endList();

        // Start replicator stats
        destStatsStream.startObject("replication");
        nsStats.replicatorCount += topicStats.remotePublishersStats.size();
        replicators.forEach((cluster, replicator) -> {
            // Update replicator cursor state
            replicator.updateCursorState();

            // Update replicator stats
            ReplicatorStats rStat = replicator.getStats();

            // Add incoming msg rates
            PublisherStats pubStats = topicStats.remotePublishersStats.get(replicator.getRemoteCluster());
            if (pubStats != null) {
                rStat.msgRateIn = pubStats.msgRateIn;
                rStat.msgThroughputIn = pubStats.msgThroughputIn;
                rStat.inboundConnection = pubStats.address;
                rStat.inboundConnectedSince = pubStats.connectedSince;
            }

            topicStats.aggMsgRateOut += rStat.msgRateOut;
            topicStats.aggMsgThroughputOut += rStat.msgThroughputOut;

            // Populate replicator specific stats here
            destStatsStream.startObject(cluster);
            destStatsStream.writePair("connected", rStat.connected);
            destStatsStream.writePair("msgRateExpired", rStat.msgRateExpired);
            destStatsStream.writePair("msgRateIn", rStat.msgRateIn);
            destStatsStream.writePair("msgRateOut", rStat.msgRateOut);
            destStatsStream.writePair("msgThroughputIn", rStat.msgThroughputIn);
            destStatsStream.writePair("msgThroughputOut", rStat.msgThroughputOut);
            destStatsStream.writePair("replicationBacklog", rStat.replicationBacklog);
            destStatsStream.writePair("replicationDelayInSeconds", rStat.replicationDelayInSeconds);
            destStatsStream.writePair("inboundConnection", rStat.inboundConnection);
            destStatsStream.writePair("inboundConnectedSince", rStat.inboundConnectedSince);
            destStatsStream.writePair("outboundConnection", rStat.outboundConnection);
            destStatsStream.writePair("outboundConnectedSince", rStat.outboundConnectedSince);
            destStatsStream.endObject();

            nsStats.msgReplBacklog += rStat.replicationBacklog;

            if (replStats.isMetricsEnabled()) {
                String namespaceClusterKey = replStats.getKeyName(namespace, cluster);
                ReplicationMetrics replicationMetrics = replStats.get(namespaceClusterKey);
                boolean update = false;
                if (replicationMetrics == null) {
                    replicationMetrics = ReplicationMetrics.get();
                    update = true;
                }
                replicationMetrics.connected += rStat.connected ? 1 : 0;
                replicationMetrics.msgRateOut += rStat.msgRateOut;
                replicationMetrics.msgThroughputOut += rStat.msgThroughputOut;
                replicationMetrics.msgReplBacklog += rStat.replicationBacklog;
                if (update) {
                    replStats.put(namespaceClusterKey, replicationMetrics);
                }
            }
        });

        // Close replication
        destStatsStream.endObject();

        // Start subscription stats
        destStatsStream.startObject("subscriptions");
        nsStats.subsCount += subscriptions.size();

        subscriptions.forEach((subscriptionName, subscription) -> {
            double subMsgRateOut = 0;
            double subMsgThroughputOut = 0;
            double subMsgRateRedeliver = 0;
            long subUnackedMessages = 0;

            // Start subscription name & consumers
            try {
                destStatsStream.startObject(subscriptionName);
                Object[] consumers = subscription.getConsumers().array();
                nsStats.consumerCount += consumers.length;
                bundleStats.consumerCount += consumers.length;

                destStatsStream.startList("consumers");

                for (Object consumerObj : consumers) {
                    Consumer consumer = (Consumer) consumerObj;
                    consumer.updateRates();

                    ConsumerStats consumerStats = consumer.getStats();
                    subMsgRateOut += consumerStats.msgRateOut;
                    subMsgThroughputOut += consumerStats.msgThroughputOut;
                    subMsgRateRedeliver += consumerStats.msgRateRedeliver;
                    subUnackedMessages += consumerStats.unackedMessages;

                    // Populate consumer specific stats here
                    destStatsStream.startObject();
                    destStatsStream.writePair("address", consumerStats.address);
                    destStatsStream.writePair("consumerName", consumerStats.consumerName);
                    destStatsStream.writePair("availablePermits", consumerStats.availablePermits);
                    destStatsStream.writePair("unackedMessages", consumerStats.unackedMessages);
                    destStatsStream.writePair("blockedConsumerOnUnackedMsgs", consumerStats.blockedConsumerOnUnackedMsgs);
                    destStatsStream.writePair("connectedSince", consumerStats.connectedSince);
                    destStatsStream.writePair("msgRateOut", consumerStats.msgRateOut);
                    destStatsStream.writePair("msgThroughputOut", consumerStats.msgThroughputOut);
                    destStatsStream.writePair("msgRateRedeliver", consumerStats.msgRateRedeliver);
                    destStatsStream.endObject();
                }

                // Close Consumer stats
                destStatsStream.endList();

                // Populate subscription specific stats here
                destStatsStream.writePair("msgBacklog", subscription.getNumberOfEntriesInBacklog());
                destStatsStream.writePair("msgRateExpired", subscription.getExpiredMessageRate());
                destStatsStream.writePair("msgRateOut", subMsgRateOut);
                destStatsStream.writePair("msgThroughputOut", subMsgThroughputOut);
                destStatsStream.writePair("msgRateRedeliver", subMsgRateRedeliver);
                destStatsStream.writePair("unackedMessages", subUnackedMessages);
                destStatsStream.writePair("type", subscription.getTypeString());

                // Close consumers
                destStatsStream.endObject();

                topicStats.aggMsgRateOut += subMsgRateOut;
                topicStats.aggMsgThroughputOut += subMsgThroughputOut;
                nsStats.msgBacklog += subscription.getNumberOfEntriesInBacklog();
            } catch (Exception e) {
                log.error("Got exception when creating consumer stats for subscription {}: {}", subscriptionName,
                        e.getMessage(), e);
            }
        });

        // Close subscription
        destStatsStream.endObject();

        // Remaining dest stats.
        topicStats.averageMsgSize = topicStats.aggMsgRateIn == 0.0 ? 0.0
                : (topicStats.aggMsgThroughputIn / topicStats.aggMsgRateIn);
        destStatsStream.writePair("producerCount", producers.size());
        destStatsStream.writePair("averageMsgSize", topicStats.averageMsgSize);
        destStatsStream.writePair("msgRateIn", topicStats.aggMsgRateIn);
        destStatsStream.writePair("msgRateOut", topicStats.aggMsgRateOut);
        destStatsStream.writePair("msgThroughputIn", topicStats.aggMsgThroughputIn);
        destStatsStream.writePair("msgThroughputOut", topicStats.aggMsgThroughputOut);
        destStatsStream.writePair("storageSize", ledger.getEstimatedBacklogSize());
        destStatsStream.writePair("pendingAddEntriesCount", ((ManagedLedgerImpl) ledger).getPendingAddEntriesCount());

        nsStats.msgRateIn += topicStats.aggMsgRateIn;
        nsStats.msgRateOut += topicStats.aggMsgRateOut;
        nsStats.msgThroughputIn += topicStats.aggMsgThroughputIn;
        nsStats.msgThroughputOut += topicStats.aggMsgThroughputOut;
        nsStats.storageSize += ledger.getEstimatedBacklogSize();

        bundleStats.msgRateIn += topicStats.aggMsgRateIn;
        bundleStats.msgRateOut += topicStats.aggMsgRateOut;
        bundleStats.msgThroughputIn += topicStats.aggMsgThroughputIn;
        bundleStats.msgThroughputOut += topicStats.aggMsgThroughputOut;
        bundleStats.cacheSize += ((ManagedLedgerImpl) ledger).getCacheSize();

        // Close topic object
        destStatsStream.endObject();
    }

    public PersistentTopicStats getStats() {

        PersistentTopicStats stats = new PersistentTopicStats();

        ObjectObjectHashMap<String, PublisherStats> remotePublishersStats = new ObjectObjectHashMap<String, PublisherStats>();

        producers.forEach(producer -> {
            PublisherStats publisherStats = producer.getStats();
            stats.msgRateIn += publisherStats.msgRateIn;
            stats.msgThroughputIn += publisherStats.msgThroughputIn;

            if (producer.isRemote()) {
                remotePublishersStats.put(producer.getRemoteCluster(), publisherStats);
            } else {
                stats.publishers.add(publisherStats);
            }
        });

        stats.averageMsgSize = stats.msgRateIn == 0.0 ? 0.0 : (stats.msgThroughputIn / stats.msgRateIn);

        subscriptions.forEach((name, subscription) -> {
            PersistentSubscriptionStats subStats = subscription.getStats();

            stats.msgRateOut += subStats.msgRateOut;
            stats.msgThroughputOut += subStats.msgThroughputOut;
            stats.subscriptions.put(name, subStats);
        });

        replicators.forEach((cluster, replicator) -> {
            ReplicatorStats replicatorStats = replicator.getStats();

            // Add incoming msg rates
            PublisherStats pubStats = remotePublishersStats.get(replicator.getRemoteCluster());
            if (pubStats != null) {
                replicatorStats.msgRateIn = pubStats.msgRateIn;
                replicatorStats.msgThroughputIn = pubStats.msgThroughputIn;
                replicatorStats.inboundConnection = pubStats.address;
                replicatorStats.inboundConnectedSince = pubStats.connectedSince;
            }

            stats.msgRateOut += replicatorStats.msgRateOut;
            stats.msgThroughputOut += replicatorStats.msgThroughputOut;

            stats.replication.put(replicator.getRemoteCluster(), replicatorStats);
        });

        stats.storageSize = ledger.getEstimatedBacklogSize();

        return stats;
    }

    public PersistentTopicInternalStats getInternalStats() {
        PersistentTopicInternalStats stats = new PersistentTopicInternalStats();

        ManagedLedgerImpl ml = (ManagedLedgerImpl) ledger;
        stats.entriesAddedCounter = ml.getEntriesAddedCounter();
        stats.numberOfEntries = ml.getNumberOfEntries();
        stats.totalSize = ml.getTotalSize();
        stats.currentLedgerEntries = ml.getCurrentLedgerEntries();
        stats.currentLedgerSize = ml.getCurrentLedgerSize();
        stats.lastLedgerCreatedTimestamp = DATE_FORMAT.format(Instant.ofEpochMilli(ml.getLastLedgerCreatedTimestamp()));
        if (ml.getLastLedgerCreationFailureTimestamp() != 0) {
            stats.lastLedgerCreationFailureTimestamp = DATE_FORMAT
                    .format(Instant.ofEpochMilli(ml.getLastLedgerCreationFailureTimestamp()));
        }

        stats.waitingCursorsCount = ml.getWaitingCursorsCount();
        stats.pendingAddEntriesCount = ml.getPendingAddEntriesCount();

        stats.lastConfirmedEntry = ml.getLastConfirmedEntry().toString();
        stats.state = ml.getState().toString();

        stats.ledgers = Lists.newArrayList();
        ml.getLedgersInfo().forEach((id, li) -> {
            LedgerInfo info = new LedgerInfo();
            info.ledgerId = li.getLedgerId();
            info.entries = li.getEntries();
            info.size = li.getSize();
            stats.ledgers.add(info);
        });

        stats.cursors = Maps.newTreeMap();
        ml.getCursors().forEach(c -> {
            ManagedCursorImpl cursor = (ManagedCursorImpl) c;
            CursorStats cs = new CursorStats();
            cs.markDeletePosition = cursor.getMarkDeletedPosition().toString();
            cs.readPosition = cursor.getReadPosition().toString();
            cs.waitingReadOp = cursor.hasPendingReadRequest();
            cs.pendingReadOps = cursor.getPendingReadOpsCount();
            cs.messagesConsumedCounter = cursor.getMessagesConsumedCounter();
            cs.cursorLedger = cursor.getCursorLedger();
            cs.cursorLedgerLastEntry = cursor.getCursorLedgerLastEntry();
            cs.individuallyDeletedMessages = cursor.getIndividuallyDeletedMessages();
            cs.lastLedgerSwitchTimestamp = DATE_FORMAT.format(Instant.ofEpochMilli(cursor.getLastLedgerSwitchTimestamp()));
            cs.state = cursor.getState();
            stats.cursors.put(cursor.getName(), cs);
        });
        return stats;
    }

    public long getBacklogSize() {
        return ledger.getEstimatedBacklogSize();
    }

    public boolean isActive() {
        if (DestinationName.get(topic).isGlobal()) {
            // No local consumers and no local producers
            return !subscriptions.isEmpty() || hasLocalProducers();
        }
        return usageCount.get() != 0 || !subscriptions.isEmpty();
    }

    @Override
    public void checkGC(int gcIntervalInSeconds) {
        if (isActive()) {
            lastActive = System.nanoTime();
        } else {
            if (System.nanoTime() - lastActive > TimeUnit.SECONDS.toNanos(gcIntervalInSeconds)) {
                CompletableFuture<Void> replCloseFuture = new CompletableFuture<>();

                if (DestinationName.get(topic).isGlobal()) {
                    // For global namespace, close repl producers first.
                    // Once all repl producers are closed, we can delete the topic,
                    // provided no remote producers connected to the broker.
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Global topic inactive for {} seconds, closing repl producers.", topic,
                                gcIntervalInSeconds);
                    }
                    closeReplProducersIfNoBacklog().thenRun(() -> {
                        if (hasRemoteProducers()) {
                            if (log.isDebugEnabled()) {
                                log.debug("[{}] Global topic has connected remote producers. Not a candidate for GC",
                                        topic);
                            }
                            replCloseFuture.completeExceptionally(
                                    new TopicBusyException("Topic has connected remote producers"));
                        } else {
                            log.info("[{}] Global topic inactive for {} seconds, closed repl producers", topic,
                                    gcIntervalInSeconds);
                            replCloseFuture.complete(null);
                        }
                    }).exceptionally(e -> {
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] Global topic has replication backlog. Not a candidate for GC", topic);
                        }
                        replCloseFuture.completeExceptionally(e.getCause());
                        return null;
                    });
                } else {
                    replCloseFuture.complete(null);
                }

                replCloseFuture.thenCompose(v -> delete(true))
                        .thenRun(() -> log.info("[{}] Topic deleted successfully due to inactivity", topic))
                        .exceptionally(e -> {
                            if (e.getCause() instanceof TopicBusyException) {
                                // topic became active again
                                if (log.isDebugEnabled()) {
                                    log.debug("[{}] Did not delete busy topic: {}", topic, e.getCause().getMessage());
                                }
                            } else {
                                log.warn("[{}] Inactive topic deletion failed", topic, e);
                            }
                            return null;
                        });
            }
        }
    }

    @Override
    public CompletableFuture<Void> onPoliciesUpdate(Policies data) {
        producers.forEach(Producer::checkPermissions);
        subscriptions.forEach((subName, sub) -> sub.getConsumers().forEach(Consumer::checkPermissions));
        checkMessageExpiry();
        return checkReplication();
    }

    /**
     *
     * @return Backlog quota for topic
     */
    @Override
    public BacklogQuota getBacklogQuota() {
        DestinationName destination = DestinationName.get(this.getName());
        String namespace = destination.getNamespace();
        String policyPath = AdminResource.path("policies", namespace);

        BacklogQuota backlogQuota = brokerService.getBacklogQuotaManager().getBacklogQuota(namespace, policyPath);
        return backlogQuota;
    }

    /**
     *
     * @return quota exceeded status for blocking producer creation
     */
    @Override
    public boolean isBacklogQuotaExceeded(String producerName) {
        BacklogQuota backlogQuota = getBacklogQuota();

        if (backlogQuota != null) {
            BacklogQuota.RetentionPolicy retentionPolicy = backlogQuota.getPolicy();

            if ((retentionPolicy == BacklogQuota.RetentionPolicy.producer_request_hold
                    || retentionPolicy == BacklogQuota.RetentionPolicy.producer_exception)
                    && brokerService.isBacklogExceeded(this)) {
                log.info("[{}] Backlog quota exceeded. Cannot create producer [{}]", this.getName(), producerName);
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    public boolean isOldestMessageExpired(ManagedCursor cursor, long messageTTLInSeconds) {
        MessageImpl msg = null;
        Entry entry = null;
        boolean isOldestMessageExpired = false;
        try {
            entry = cursor.getNthEntry(1, IndividualDeletedEntries.Include);
            if (entry != null) {
                msg = MessageImpl.deserialize(entry.getDataBuffer());
                isOldestMessageExpired = messageTTLInSeconds != 0 && System.currentTimeMillis() > (msg.getPublishTime()
                        + TimeUnit.SECONDS.toMillis((long) (messageTTLInSeconds * MESSAGE_EXPIRY_THRESHOLD)));
            }
        } catch (Exception e) {
            log.warn("[{}] Error while getting the oldest message", topic, e);
        } finally {
            if (entry != null) {
                entry.release();
            }
            if (msg != null) {
                msg.recycle();
            }
        }

        return isOldestMessageExpired;
    }

    /**
     * Clears backlog for all cursors in the topic
     *
     * @return
     */
    public CompletableFuture<Void> clearBacklog() {
        log.info("[{}] Clearing backlog on all cursors in the topic.", topic);
        List<CompletableFuture<Void>> futures = Lists.newArrayList();
        List<String> cursors = getSubscriptions().keys();
        cursors.addAll(getReplicators().keys());
        for (String cursor : cursors) {
            futures.add(clearBacklog(cursor));
        }
        return FutureUtil.waitForAll(futures);
    }

    /**
     * Clears backlog for a given cursor in the topic.
     * <p>
     * Note: For a replication cursor, just provide the remote cluster name
     * </p>
     *
     * @param cursorName
     * @return
     */
    public CompletableFuture<Void> clearBacklog(String cursorName) {
        log.info("[{}] Clearing backlog for cursor {} in the topic.", topic, cursorName);
        PersistentSubscription sub = getPersistentSubscription(cursorName);
        if (sub != null) {
            return sub.clearBacklog();
        }

        PersistentReplicator repl = getPersistentReplicator(cursorName);
        if (repl != null) {
            return repl.clearBacklog();
        }

        return FutureUtil.failedFuture(new BrokerServiceException("Cursor not found"));
    }

    private static final Logger log = LoggerFactory.getLogger(PersistentTopic.class);
}
