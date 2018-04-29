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
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.TopicPartition;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leader消费者在收到JoinGroupResponse后,会按照其中指定的分区分配策略进行分区分配
 * 每个分区分配策略就是一个PartitionAssignor接口的实现
 * PartitionAssignor定义了两个内部类,Subscription Assignment
 * 进行分区分配需要两方面的数据:Metadata中记录的集群元数据和每个Member的订阅信息
 * 为了用户增强对分配结果的控制,就将用户订阅信息和一些影响分配的用户自定义信息封装成Subscription
 * <p>
 * This interface is used to define custom partition assignment for use in
 * {@link org.apache.kafka.clients.consumer.KafkaConsumer}. Members of the consumer group subscribe
 * to the topics they are interested in and forward their subscriptions to a Kafka broker serving
 * as the group coordinator. The coordinator selects one member to perform the group assignment and
 * propagates the subscriptions of all members to it. Then {@link #assign(Cluster, Map)} is called
 * to perform the assignment and the results are forwarded back to each respective members
 * <p>
 * In some cases, it is useful to forward additional metadata to the assignor in order to make
 * assignment decisions. For this, you can override {@link #subscription(Set)} and provide custom
 * userData in the returned Subscription. For example, to have a rack-aware assignor, an implementation
 * can use this user data to forward the rackId belonging to each member.
 */
public interface PartitionAssignor {

    /**
     * 添加用户自定义数据,在创建JoinGroupRequest的时候会用到此方法
     * Return a serializable object representing the local member's subscription. This can include
     * additional information as well (e.g. local host/rack information) which can be leveraged in
     * {@link #assign(Cluster, Map)}.
     *
     * @param topics Topics subscribed to through {@link org.apache.kafka.clients.consumer.KafkaConsumer#subscribe(java.util.Collection)}
     *               and variants
     * @return Non-null subscription with optional user data
     */
    Subscription subscription(Set<String> topics);

    /**
     * 需要子类实现,完成Partition分配的抽象方法
     * Perform the group assignment given the member subscriptions and current cluster metadata.
     *
     * @param metadata      Current topic/broker metadata known by consumer
     * @param subscriptions Subscriptions from all members provided through {@link #subscription(Set)}
     * @return A map from the members to their respective assignment. This should have one entry
     * for all members who in the input subscription map.
     */
    Map<String, Assignment> assign(Cluster metadata, Map<String, Subscription> subscriptions);


    /**
     * 在每个消费者收到Leader分配结果时的回调函数,此调用发生在解析SyncGroupResponse之后
     * Callback which is invoked when a group member receives its assignment from the leader.
     *
     * @param assignment The local member's assignment as provided by the leader in {@link #assign(Cluster, Map)}
     */
    void onAssignment(Assignment assignment);


    /**
     * Unique name for this assignor (e.g. "range" or "roundrobin")
     *
     * @return non-null unique name
     */
    String name();

    class Subscription {
        /**
         * 表示某Member订阅的Topic集合
         */
        private final List<String> topics;
        /**
         * 用户自定义数据 (可以是每个消费者的权重)
         */
        private final ByteBuffer userData;

        public Subscription(List<String> topics, ByteBuffer userData) {
            this.topics = topics;
            this.userData = userData;
        }

        public Subscription(List<String> topics) {
            this(topics, ByteBuffer.wrap(new byte[0]));
        }

        public List<String> topics() {
            return topics;
        }

        public ByteBuffer userData() {
            return userData;
        }

        @Override
        public String toString() {
            return "Subscription(" +
                    "topics=" + topics +
                    ')';
        }
    }

    /**
     * 保存了分区的分配结果
     */
    class Assignment {
        /**
         * 表示分配给某消费者的TopicPartition集合
         */
        private final List<TopicPartition> partitions;
        /**
         * 用户自定义数据
         */
        private final ByteBuffer userData;

        public Assignment(List<TopicPartition> partitions, ByteBuffer userData) {
            this.partitions = partitions;
            this.userData = userData;
        }

        public Assignment(List<TopicPartition> partitions) {
            this(partitions, ByteBuffer.wrap(new byte[0]));
        }

        public List<TopicPartition> partitions() {
            return partitions;
        }

        public ByteBuffer userData() {
            return userData;
        }

        @Override
        public String toString() {
            return "Assignment(" +
                    "partitions=" + partitions +
                    ')';
        }
    }

}
