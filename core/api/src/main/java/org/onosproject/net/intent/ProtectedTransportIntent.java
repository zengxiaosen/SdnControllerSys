/*
 * Copyright 2016-present Open Networking Laboratory
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
package org.onosproject.net.intent;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;
import javax.annotation.concurrent.Immutable;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.NetworkResource;
import org.onosproject.net.ResourceGroup;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

import com.google.common.annotations.Beta;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

/**
 * Intent to create a protected linear path.
 */
@Immutable
@Beta
public final class ProtectedTransportIntent extends ConnectivityIntent {

    private final DeviceId one;
    private final DeviceId two;
    // Do we want to be able to explicitly specify VLANs?
    //private final Optional<VlanId> oneVid;
    //private final Optional<VlanId> twoVid;

    // TODO consider removing selector and treatment from signature
    /**
     * Creates {@link ProtectedTransportIntent}.
     *
     * @param one          transport endpoint device
     * @param two          transport endpoint device
     * @param appId        application identifier
     * @param key          key of the intent
     * @param resources    resources
     * @param selector     traffic selector
     * @param treatment    treatment
     * @param constraints  optional list of constraints
     * @param priority     priority to use for flows generated by this intent
     */
    private ProtectedTransportIntent(DeviceId one,
                                     DeviceId two,
                                     ApplicationId appId, Key key,
                                     Collection<NetworkResource> resources,
                                     TrafficSelector selector,
                                     TrafficTreatment treatment,
                                     List<Constraint> constraints,
                                     int priority,
                                     ResourceGroup resourceGroup) {
        super(appId, key, resources, selector, treatment, constraints,
              priority, resourceGroup);

        this.one = checkNotNull(one, "one cannot be null");
        this.two = checkNotNull(two, "two cannot be null");
    }

    /**
     * Returns the transport endpoint device one.
     *
     * @return transport endpoint device id
     */
    public DeviceId one() {
        return one;
    }

    /**
     * Returns the transport endpoint device two.
     *
     * @return transport endpoint device id
     */
    public DeviceId two() {
        return two;
    }

    /**
     * Returns a new {@link ProtectedTransportIntent} builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ProtectedTransportIntent}.
     */
    public static final class Builder extends ConnectivityIntent.Builder {
        private DeviceId one;
        private DeviceId two;

        private Builder() {
            // Hide constructor
            resources = ImmutableList.of();
        }

        // TODO remove these overrides after Builder refactoring
        @Override
        public Builder appId(ApplicationId appId) {
            return (Builder) super.appId(appId);
        }

        @Override
        public Builder key(Key key) {
            return (Builder) super.key(key);
        }

        @Override
        public Builder selector(TrafficSelector selector) {
            return (Builder) super.selector(selector);
        }

        @Override
        public Builder treatment(TrafficTreatment treatment) {
            return (Builder) super.treatment(treatment);
        }

        @Override
        public Builder constraints(List<Constraint> constraints) {
            return (Builder) super.constraints(constraints);
        }

        @Override
        public Builder priority(int priority) {
            return (Builder) super.priority(priority);
        }

        @Override
        public Builder resourceGroup(ResourceGroup resourceGroup) {
            return (Builder) super.resourceGroup(resourceGroup);
        }

        /**
         * Sets the transport endpoint device one.
         *
         * @param one transport endpoint device
         * @return this builder
         */
        public Builder one(DeviceId one) {
            this.one = checkNotNull(one);
            return this;
        }

        /**
         * Sets the transport endpoint device two.
         *
         * @param two transport endpoint device
         * @return this builder
         */
        public Builder two(DeviceId two) {
            this.two = checkNotNull(two);
            return this;
        }


        /**
         * Builds a point to point intent from the accumulated parameters.
         *
         * @return point to point intent
         */
        public ProtectedTransportIntent build() {

            return new ProtectedTransportIntent(one,
                                                two,
                                                appId,
                                                key,
                                                resources,
                                                selector,
                                                treatment,
                                                constraints,
                                                priority,
                                                resourceGroup
            );
        }

    }
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(getClass())
                .add("id", id())
                .add("key", key())
                .add("appId", appId())
                .add("priority", priority())
                .add("resources", resources())
                .add("selector", selector())
                .add("treatment", treatment())
                .add("one", one())
                .add("two", two())
                .add("constraints", constraints())
                .add("resourceGroup", resourceGroup())
                .toString();
    }


    // For serializer
    private ProtectedTransportIntent() {
        this.one = null;
        this.two = null;
    }
}