/*
 * Copyright 2015-present Open Networking Foundation
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
package org.onosproject.net.flowobjective;

import com.google.common.annotations.Beta;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

/**
 * Represents a description of which types of traffic need to
 * be forwarded through the device. A forwarding objective may
 * result in multiple rules at the device. There are two main types
 * of forwarding objectives:
 *代表需要通过设备转发哪些类型的流量的描述。 转发目标可能会导致设备出现多条规则。 转发目标主要有两种类型：
 *  - Versatile,单片
 *  - Specific,分解
 *
 * A versatile forwarding objective represents a composite rule that matches
 * two or more header fields. The use of versatile usually indicates that this
 * rule should be inserted in its entirety into the ACL table. Although,
 * drivers for some devices are free to implement this differently.
 * 多功能转发对象表示与两个或更多包头字段匹配的组合规则。 通用的使用方式通常是应
 * 将此规则完整地插入到ACL表中。 当然，一些设备的驱动程序可以自由地以不同方式实现。
 *
 * A specific forwarding objective represents a specific rule matching one or
 * more header fields. The installation of this rule may result in several rules
 * at the device. For example, one per table type.
 * 一个特定的转发目标代表一个匹配一个或多个头字段的特定规则。 此规则的安装可能会导致设备出现若干
 * 规则。 例如，每个表类型一个。
 */
@Beta
public interface ForwardingObjective extends Objective {

    /**
     * Represents whether this objective is monolithic or
     * may be broken down into parts.
     */
    enum Flag {
        /**
         * A decomposable objective.
         * 分解对象
         */
        SPECIFIC,

        /**
         * A monolithic objective.
         * 单片对象
         */
        VERSATILE
    }

    /**
     * Obtain the selector for this objective.
     *
     * @return a traffic selector
     */
    TrafficSelector selector();

    /**
     * Obtain the traffic treatment for this objective. Mutually exclusive with
     * 'treatment'.
     *
     * @return an integer
     */
    Integer nextId();

    /**
     * A traffic treatment for this forwarding objective. Mutually exclusive
     * with a nextId.
     *
     * @return a traffic treatment
     */
    TrafficTreatment treatment();

    /**
     * Obtain the type of this objective.
     *
     * @return a flag type
     */
    Flag flag();

    /**
     * Auxiliary optional information provided to the device driver. Typically
     * conveys information about selectors (matches) that are intended to
     * use this Forwarding Objective.
     *
     * @return a selector intended to pass meta information to the device driver.
     *         Value may be null if no meta information is provided.
     */
    TrafficSelector meta();

    /**
     * Returns a new builder set to create a copy of this objective.
     *
     * @return new builder
     */
    @Override
    Builder copy();

    /**
     * A forwarding objective builder.
     */
    interface Builder extends Objective.Builder {

        /**
         * Assigns a selector to the forwarding objective.
         *
         * @param selector a traffic selector
         * @return a forwarding objective builder
         */
        Builder withSelector(TrafficSelector selector);

        /**
         * Assigns a next step to the forwarding objective.
         *
         * @param nextId a next objective id.
         * @return a forwarding objective builder
         */
        Builder nextStep(int nextId);

        /**
         * Assigns the treatment for this forwarding objective.
         *
         * @param treatment a traffic treatment
         * @return a forwarding objective
         */
        Builder withTreatment(TrafficTreatment treatment);

        /**
         * Assigns the flag to the forwarding objective.
         *
         * @param flag a flag
         * @return a forwarding objective builder
         */
        Builder withFlag(Flag flag);

        /**
         * Set meta information related to this forwarding objective.
         *
         * @param selector match conditions
         * @return an objective builder
         */
        Builder withMeta(TrafficSelector selector);

        /**
         * Assigns an application id.
         *
         * @param appId an application id
         * @return a filtering builder
         */
        @Override
        Builder fromApp(ApplicationId appId);

        /**
         * Sets the priority for this objective.
         *
         * @param priority an integer
         * @return an objective builder
         */
        @Override
        Builder withPriority(int priority);

        /**
         * Makes the filtering objective permanent.
         *
         * @return an objective builder
         */
        @Override
        Builder makePermanent();

        /**
         * Builds the forwarding objective that will be added.
         *
         * @return a forwarding objective
         */
        @Override
        ForwardingObjective add();

        /**
         * Builds the forwarding objective that will be removed.
         *
         * @return a forwarding objective.
         */
        @Override
        ForwardingObjective remove();

        /**
         * Builds the forwarding objective that will be added.
         * The context will be used to notify the calling application.
         *
         * @param context an objective context
         * @return a forwarding objective
         */
        @Override
        ForwardingObjective add(ObjectiveContext context);

        /**
         * Builds the forwarding objective that will be removed.
         * The context will be used to notify the calling application.
         *
         * @param context an objective context
         * @return a forwarding objective
         */
        @Override
        ForwardingObjective remove(ObjectiveContext context);
    }
}
