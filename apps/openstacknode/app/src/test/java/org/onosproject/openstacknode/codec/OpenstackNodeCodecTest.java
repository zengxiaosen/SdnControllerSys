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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.IpAddress;
import org.onosproject.codec.CodecContext;
import org.onosproject.codec.JsonCodec;
import org.onosproject.codec.impl.CodecManager;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.openstacknode.api.NodeState;
import org.onosproject.openstacknode.api.OpenstackNode;
import org.onosproject.openstacknode.impl.DefaultOpenstackNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.onosproject.net.NetTestTools.APP_ID;
import static org.onosproject.openstacknode.codec.OpenstackNodeJsonMatcher.matchesOpenstackNode;

/**
 * Unit tests for OpenstackNode codec.
 */
public class OpenstackNodeCodecTest {
    MockCodecContext context;
    JsonCodec<OpenstackNode> openstackNodeCodec;
    final CoreService mockCoreService = createMock(CoreService.class);
    private static final String REST_APP_ID = "org.onosproject.rest";

    @Before
    public void setUp() {
        context = new MockCodecContext();
        openstackNodeCodec = new OpenstackNodeCodec();
        assertThat(openstackNodeCodec, notNullValue());

        expect(mockCoreService.registerApplication(REST_APP_ID))
                .andReturn(APP_ID).anyTimes();
        replay(mockCoreService);
        context.registerService(CoreService.class, mockCoreService);
    }

    @Test
    public void testOpenstackNodeEncode() {
        OpenstackNode node = DefaultOpenstackNode.builder()
                                .hostname("compute")
                                .type(OpenstackNode.NodeType.COMPUTE)
                                .state(NodeState.INIT)
                                .managementIp(IpAddress.valueOf("10.10.10.1"))
                                .intgBridge(DeviceId.deviceId("br-int"))
                                .vlanIntf("vxlan")
                                .dataIp(IpAddress.valueOf("20.20.20.2"))
                                .build();

        ObjectNode nodeJson = openstackNodeCodec.encode(node, context);
        assertThat(nodeJson, matchesOpenstackNode(node));
    }

    @Test
    public void testOpenstackNodeDecode() throws IOException {
        OpenstackNode node = getOpenstackNode("OpenstackNode.json");

        assertThat(node.hostname(), is("compute-01"));
        assertThat(node.type().name(), is("COMPUTE"));
        assertThat(node.managementIp().toString(), is("172.16.130.4"));
        assertThat(node.dataIp().toString(), is("172.16.130.4"));
        assertThat(node.intgBridge().toString(), is("of:00000000000000a1"));
        assertThat(node.vlanIntf(), is("eth2"));
    }

    /**
     * Reads in an openstack node from the given resource and decodes it.
     *
     * @param resourceName resource to use to read the JSON for the rule
     * @return decoded openstack node
     * @throws IOException if processing the resource fails
     */
    private OpenstackNode getOpenstackNode(String resourceName) throws IOException {
        InputStream jsonStream = OpenstackNodeCodecTest.class.getResourceAsStream(resourceName);
        JsonNode json = context.mapper().readTree(jsonStream);
        MatcherAssert.assertThat(json, notNullValue());
        OpenstackNode node = openstackNodeCodec.decode((ObjectNode) json, context);
        assertThat(node, notNullValue());
        return node;
    }

    /**
     * Mock codec context for use in codec unit tests.
     */
    private class MockCodecContext implements CodecContext {
        private final ObjectMapper mapper = new ObjectMapper();
        private final CodecManager manager = new CodecManager();
        private final Map<Class<?>, Object> services = new HashMap<>();

        /**
         * Constructs a new mock codec context.
         */
        public MockCodecContext() {
            manager.activate();
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> JsonCodec<T> codec(Class<T> entityClass) {
            return manager.getCodec(entityClass);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getService(Class<T> serviceClass) {
            return (T) services.get(serviceClass);
        }

        // for registering mock services
        public <T> void registerService(Class<T> serviceClass, T impl) {
            services.put(serviceClass, impl);
        }
    }
}
