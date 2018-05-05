/*
 * Copyright 2018-present Open Networking Foundation
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
package org.onosproject.openstacknode.codec;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onlab.packet.IpAddress;
import org.onosproject.codec.CodecContext;
import org.onosproject.codec.JsonCodec;
import org.onosproject.net.DeviceId;
import org.onosproject.openstacknode.api.NodeState;
import org.onosproject.openstacknode.api.OpenstackNode;
import org.onosproject.openstacknode.impl.DefaultOpenstackNode;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onlab.util.Tools.nullIsIllegal;
import static org.onosproject.openstacknode.api.Constants.DATA_IP;
import static org.onosproject.openstacknode.api.Constants.GATEWAY;
import static org.onosproject.openstacknode.api.Constants.HOST_NAME;
import static org.onosproject.openstacknode.api.Constants.MANAGEMENT_IP;
import static org.onosproject.openstacknode.api.Constants.UPLINK_PORT;
import static org.onosproject.openstacknode.api.Constants.VLAN_INTF_NAME;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Openstack node codec used for serializing and de-serializing JSON string.
 */
public final class OpenstackNodeCodec extends JsonCodec<OpenstackNode> {

    private final Logger log = getLogger(getClass());

    private static final String TYPE = "type";
    private static final String INTEGRATION_BRIDGE = "integrationBridge";
    private static final String STATE = "state";

    private static final String MISSING_MESSAGE = " is required in OpenstackNode";

    @Override
    public ObjectNode encode(OpenstackNode node, CodecContext context) {
        checkNotNull(node, "Openstack node cannot be null");

        ObjectNode result = context.mapper().createObjectNode()
                .put(HOST_NAME, node.hostname())
                .put(TYPE, node.type().name())
                .put(MANAGEMENT_IP, node.managementIp().toString())
                .put(INTEGRATION_BRIDGE, node.intgBridge().toString())
                .put(STATE, node.state().name());

        OpenstackNode.NodeType type = node.type();

        if (type == OpenstackNode.NodeType.GATEWAY) {
            result.put(UPLINK_PORT, node.uplinkPort());
        }

        if (node.vlanIntf() != null) {
            result.put(VLAN_INTF_NAME, node.vlanIntf());
        }

        if (node.dataIp() != null) {
            result.put(DATA_IP, node.dataIp().toString());
        }

        // TODO: need to find a way to not refer to ServiceDirectory from
        // DefaultOpenstackNode

        return result;
    }

    @Override
    public OpenstackNode decode(ObjectNode json, CodecContext context) {
        if (json == null || !json.isObject()) {
            return null;
        }

        String hostname = nullIsIllegal(json.get(HOST_NAME).asText(),
                HOST_NAME + MISSING_MESSAGE);
        String type = nullIsIllegal(json.get(TYPE).asText(),
                TYPE + MISSING_MESSAGE);
        String mIp = nullIsIllegal(json.get(MANAGEMENT_IP).asText(),
                MANAGEMENT_IP + MISSING_MESSAGE);
        String iBridge = nullIsIllegal(json.get(INTEGRATION_BRIDGE).asText(),
                INTEGRATION_BRIDGE + MISSING_MESSAGE);

        DefaultOpenstackNode.Builder nodeBuilder = DefaultOpenstackNode.builder()
                .hostname(hostname)
                .type(OpenstackNode.NodeType.valueOf(type))
                .managementIp(IpAddress.valueOf(mIp))
                .intgBridge(DeviceId.deviceId(iBridge))
                .state(NodeState.INIT);

        if (type.equals(GATEWAY)) {
            nodeBuilder.uplinkPort(nullIsIllegal(json.get(UPLINK_PORT).asText(),
                    UPLINK_PORT + MISSING_MESSAGE));
        }
        if (json.get(VLAN_INTF_NAME) != null) {
            nodeBuilder.vlanIntf(json.get(VLAN_INTF_NAME).asText());
        }
        if (json.get(DATA_IP) != null) {
            nodeBuilder.dataIp(IpAddress.valueOf(json.get(DATA_IP).asText()));
        }

        log.trace("node is {}", nodeBuilder.build().toString());

        return nodeBuilder.build();
    }
}
