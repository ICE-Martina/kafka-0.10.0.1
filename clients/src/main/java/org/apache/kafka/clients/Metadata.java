/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class encapsulating some of the logic around metadata.
 * <p>
 * This class is shared by the client thread (for partitioning) and the background sender thread.
 * <p>
 * Metadata is maintained for only a subset of topics, which can be added to over time. When we request metadata for a
 * topic we don't have any metadata for it will trigger a metadata update.
 * Metadata中的字段可以由主线程读,Sender线程更新,因此它必须是线程安全的,这也是Metadata类中所有方法都使用synchronized的原因
 */
public final class Metadata {

    private static final Logger log = LoggerFactory.getLogger(Metadata.class);

    /**
     * 两次发出更新Cluster保存元数据信息的最小时间差,默认为100毫秒,这是为了防止更新操作过于频繁而造成网络阻塞和增加服务端压力
     * 在kafka中与重试相关的操作中,都有这种"避退(backoff)时间"的设计
     */
    private final long refreshBackoffMs;
    /**
     * 每隔多久更新一次元数据,默认是300*1000(5分钟)
     */
    private final long metadataExpireMs;
    /**
     * 表示kafka集群元数据的版本,kafka集群元数据每更新一次,version字段的值+1
     * 通过新旧版本号的对比,可以判断集群元数据是否更新完成
     */
    private int version;
    /**
     * 记录上一次更新元数据的时间戳(也包含更新失败的情况)
     */
    private long lastRefreshMs;
    /**
     * 记录上一次成功更新元数据的时间戳,如果每次都成功,则lastSuccessfulRefreshMs=lastRefreshMs
     * 否则 lastRefreshMs>lastSuccessfulRefreshMs
     */
    private long lastSuccessfulRefreshMs;
    /**
     * 记录Kafka集群的元数据
     */
    private Cluster cluster;
    /**
     * 标识是否需要强制更新Cluster,这是触发Sender线程更新集群元数据的条件之一
     */
    private boolean needUpdate;
    /**
     * 记录了当前已知的所有Topic,在cluster字段中记录了Topic最新的元数据
     */
    private final Set<String> topics;
    /**
     * 监听MetaData更新的监听器集合,自定义MetaData监听实现MetaData.Listener.onMetaDataUpdate()方法即可
     * 在更新MetaData的cluster字段之前,会通过listeners集合中全部listeners对象
     */
    private final List<Listener> listeners;
    /**
     * 是否需要更新全部Topic的元数据,一般情况下,KafkaProducer只维护它用到的Topic的元数据,是集群中全部Topic的子集
     */
    private boolean needMetadataForAllTopics;

    /**
     * Create a metadata instance with reasonable defaults
     */
    public Metadata() {
        this(100L, 60 * 60 * 1000L);
    }

    /**
     * Create a new Metadata instance
     *
     * @param refreshBackoffMs The minimum amount of time that must expire between metadata refreshes to avoid busy
     *                         polling
     * @param metadataExpireMs The maximum amount of time that metadata can be retained without refresh
     */
    public Metadata(long refreshBackoffMs, long metadataExpireMs) {
        this.refreshBackoffMs = refreshBackoffMs;
        this.metadataExpireMs = metadataExpireMs;
        this.lastRefreshMs = 0L;
        this.lastSuccessfulRefreshMs = 0L;
        this.version = 0;
        this.cluster = Cluster.empty();
        this.needUpdate = false;
        this.topics = new HashSet<String>();
        this.listeners = new ArrayList<>();
        this.needMetadataForAllTopics = false;
    }

    /**
     * Get the current cluster info without blocking
     */
    public synchronized Cluster fetch() {
        return this.cluster;
    }

    /**
     * Add the topic to maintain in the metadata
     */
    public synchronized void add(String topic) {
        topics.add(topic);
    }

    /**
     * The next time to update the cluster info is the maximum of the time the current info will expire and the time the
     * current info can be updated (i.e. backoff time has elapsed); If an update has been request then the expiry time
     * is now
     */
    public synchronized long timeToNextUpdate(long nowMs) {
        long timeToExpire = needUpdate ? 0 : Math.max(this.lastSuccessfulRefreshMs + this.metadataExpireMs - nowMs, 0);
        long timeToAllowUpdate = this.lastRefreshMs + this.refreshBackoffMs - nowMs;
        return Math.max(timeToExpire, timeToAllowUpdate);
    }

    /**
     * Request an update of the current cluster metadata info, return the current version before the update
     * /**
     * * Request an update of the current cluster metadata info, return the current version before the update
     * * 此方法主线程用
     * * 将needUpdate字段的值修改为true,这样当Sender线程运行时会更新MetaData记录的集群元数据
     * * 然后返回version字段的值
     */

    public synchronized int requestUpdate() {
        //将needUpdate修改为true,表示需要强制更新Cluster,
        this.needUpdate = true;
        //返回当前version版本号
        return this.version;
    }

    /**
     * Check whether an update has been explicitly requested.
     *
     * @return true if an update was requested, false otherwise
     */
    public synchronized boolean updateRequested() {
        return this.needUpdate;
    }

    /**
     * Wait for metadata update until the current version is larger than the last version we know of
     * /**
     * * Wait for metadata update until the current version is larger than the last version we know of
     * 主线程用
     * * 通过version字段的的版本号来判断元数据是否更新完成,更新未完成则阻塞等待
     */
    public synchronized void awaitUpdate(final int lastVersion, final long maxWaitMs) throws InterruptedException {
        if (maxWaitMs < 0) {
            throw new IllegalArgumentException("Max time to wait for metadata updates should not be < 0 milli seconds");
        }
        long begin = System.currentTimeMillis();
        long remainingWaitMs = maxWaitMs;
        //通过比较版本号来比较集群元数据是否更新完成
        while (this.version <= lastVersion) {
            if (remainingWaitMs != 0)
                //从下面这行代码可以看出,主线程与Sender线程通过wait/notify来进行同步
                // 更新元数据的操作则交给Sender线程去完成
                wait(remainingWaitMs);
            long elapsed = System.currentTimeMillis() - begin;
            if (elapsed >= maxWaitMs)
                throw new TimeoutException("Failed to update metadata after " + maxWaitMs + " ms.");
            remainingWaitMs = maxWaitMs - elapsed;
        }
    }

    /**
     * Replace the current set of topics maintained to the one provided
     *
     * @param topics
     */
    public synchronized void setTopics(Collection<String> topics) {
        if (!this.topics.containsAll(topics))
            requestUpdate();
        this.topics.clear();
        this.topics.addAll(topics);
    }

    /**
     * Get the list of topics we are currently maintaining metadata for
     */
    public synchronized Set<String> topics() {
        return new HashSet<String>(this.topics);
    }

    /**
     * Check if a topic is already in the topic set.
     *
     * @param topic topic to check
     * @return true if the topic exists, false otherwise
     */
    public synchronized boolean containsTopic(String topic) {
        return this.topics.contains(topic);
    }

    /**
     * Sender线程操作
     * Update the cluster metadata
     */
    public synchronized void update(Cluster cluster, long now) {
        this.needUpdate = false;
        this.lastRefreshMs = now;
        this.lastSuccessfulRefreshMs = now;
        this.version += 1;

        for (Listener listener : listeners)
            listener.onMetadataUpdate(cluster);

        // Do this after notifying listeners as subscribed topics' list can be changed by listeners
        this.cluster = this.needMetadataForAllTopics ? getClusterForCurrentTopics(cluster) : cluster;
        //唤醒所有阻塞在Producer wait()上的线程(此时元数据已更新完成)
        notifyAll();
        log.debug("Updated cluster metadata version {} to {}", this.version, this.cluster);
    }

    /**
     * Record an attempt to update the metadata that failed. We need to keep track of this
     * to avoid retrying immediately.
     */
    public synchronized void failedUpdate(long now) {
        this.lastRefreshMs = now;
    }

    /**
     * @return The current metadata version
     */
    public synchronized int version() {
        return this.version;
    }

    /**
     * The last time metadata was successfully updated.
     */
    public synchronized long lastSuccessfulUpdate() {
        return this.lastSuccessfulRefreshMs;
    }

    /**
     * The metadata refresh backoff in ms
     */
    public long refreshBackoff() {
        return refreshBackoffMs;
    }

    /**
     * Set state to indicate if metadata for all topics in Kafka cluster is required or not.
     *
     * @param needMetadataForAllTopics boolean indicating need for metadata of all topics in cluster.
     */
    public synchronized void needMetadataForAllTopics(boolean needMetadataForAllTopics) {
        this.needMetadataForAllTopics = needMetadataForAllTopics;
    }

    /**
     * Get whether metadata for all topics is needed or not
     */
    public synchronized boolean needMetadataForAllTopics() {
        return this.needMetadataForAllTopics;
    }

    /**
     * Add a Metadata listener that gets notified of metadata updates
     */
    public synchronized void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    /**
     * Stop notifying the listener of metadata updates
     */
    public synchronized void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    /**
     * MetadataUpdate Listener
     */
    public interface Listener {
        void onMetadataUpdate(Cluster cluster);
    }

    private Cluster getClusterForCurrentTopics(Cluster cluster) {
        Set<String> unauthorizedTopics = new HashSet<>();
        Collection<PartitionInfo> partitionInfos = new ArrayList<>();
        List<Node> nodes = Collections.emptyList();
        if (cluster != null) {
            unauthorizedTopics.addAll(cluster.unauthorizedTopics());
            unauthorizedTopics.retainAll(this.topics);

            for (String topic : this.topics) {
                partitionInfos.addAll(cluster.partitionsForTopic(topic));
            }
            nodes = cluster.nodes();
        }
        return new Cluster(nodes, partitionInfos, unauthorizedTopics);
    }
}
