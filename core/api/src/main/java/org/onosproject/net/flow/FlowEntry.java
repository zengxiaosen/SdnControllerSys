/*
 * Copyright 2014-present Open Networking Foundation
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
package org.onosproject.net.flow;


import java.util.concurrent.TimeUnit;

/**
 * Represents a generalized match &amp; action pair to be applied to
 * an infrastructure device.
 * 表示要应用于基础架构设备的广义匹配操作对。
 */
public interface FlowEntry extends FlowRule {


    enum FlowEntryState {

        /**
         * Indicates that this rule has been submitted for addition.
         * Not necessarily in the flow table.
         */
        PENDING_ADD,

        /**
         * Rule has been added which means it is in the flow table.
         */
        ADDED,

        /**
         * Flow has been marked for removal, might still be in flow table.
         */
        PENDING_REMOVE,

        /**
         * Flow has been removed from flow table and can be purged.
         */
        REMOVED,

        /**
         * Indicates that the installation of this flow has failed.
         */
        FAILED
    }

    /**
     * Returns the flow entry state.
     *
     * @return flow entry state
     */
    FlowEntryState state();

    /**
     * Returns the number of seconds this flow rule has been applied.
     *
     * @return number of seconds
     */
    long life();

    enum FlowLiveType {

        /**
         * Indicates that this rule has been submitted for addition immediately.
         * Not necessarily collecting flow stats.
         * 表示此规则已立即提交添加。 不一定收集流量统计。
         */
        IMMEDIATE,

        /**
         * Indicates that this rule has been submitted for a short time.
         * Collecting flow stats every SHORT interval, defined by the implementation.
         * 表示此规则已提交很短时间。
         * 每个SHORT间隔收集流量统计信息，由实施定义。
         */
        SHORT,

        /**
         * Indicates that this rule has been submitted for a mid time.
         * Collecting flow stats every MID interval, defined by the implementation.
         * 表示此规则已在中间时间提交。
         * 每个MID间隔收集流量统计数据，由实施定义。
         */
        MID,

        /**
         * Indicates that this rule has been submitted for a long time.
         * Collecting flow stats every LONG interval, defined by the implementation.
         * 表示此规则已提交很长时间。
         * 每个LONG间隔收集流量统计信息，由实施定义。
         */
        LONG,

        /**
         * Indicates that this rule has been submitted for UNKNOWN or ERROR.
         * Not necessarily collecting flow stats.
         * 表示此规则已提交给UNKNOWN或ERROR。
         * 不一定收集流量统计。
         */
        UNKNOWN
    }

    /**
     * Gets the flow live type for this entry.
     *
     * @return flow live type
     */
    FlowLiveType liveType();

    /**
     * Returns the time this flow rule has been applied.
     *
     * @param unit time unit the result will be converted to
     * @return time in the requested {@link TimeUnit}
     */
    long life(TimeUnit unit);

    /**
     * Returns the number of packets this flow rule has matched.
     *
     * @return number of packets
     */
    long packets();

    /**
     * Returns the number of bytes this flow rule has matched.
     *
     * @return number of bytes
     */
    long bytes();

    // TODO: consider removing this attribute
    /**
     * When this flow entry was last deemed active.
     * @return epoch time of last activity
     */
    long lastSeen();

    /**
     * Indicates the error type.
     * @return an integer value of the error
     */
    int errType();

    /**
     * Indicates the error code.
     * @return an integer value of the error
     */
    int errCode();

}
