/*
 * Copyright 2017-present Open Networking Foundation
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
package org.onosproject.p4runtime.ctl;

import com.google.common.testing.EqualsTester;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.util.ImmutableByteSequence;
import org.onosproject.net.DeviceId;
import org.onosproject.net.pi.runtime.PiControlMetadata;
import org.onosproject.net.pi.model.PiControlMetadataId;
import org.onosproject.net.pi.runtime.PiPacketOperation;

import static org.onlab.util.ImmutableByteSequence.copyFrom;
import static org.onlab.util.ImmutableByteSequence.fit;
import static org.onosproject.net.pi.model.PiPacketOperationType.PACKET_OUT;
import static org.onosproject.net.pi.model.PiPacketOperationType.PACKET_IN;

/**
 * Test for DefaultPacketIn class.
 */
public class DefaultPacketInTest {

    private static final int DEFAULT_ORIGINAL_VALUE = 255;
    private static final int DEFAULT_BIT_WIDTH = 9;

    private final DeviceId deviceId = DeviceId.deviceId("dummy:1");
    private final DeviceId sameDeviceId = DeviceId.deviceId("dummy:1");
    private final DeviceId deviceId2 = DeviceId.deviceId("dummy:2");
    private final DeviceId nullDeviceId = null;

    private PiPacketOperation packetOperation;
    private PiPacketOperation packetOperation2;
    private PiPacketOperation nullPacketOperation = null;

    private DefaultPacketIn packetIn;
    private DefaultPacketIn sameAsPacketIn;
    private DefaultPacketIn packetIn2;
    private DefaultPacketIn packetIn3;

    /**
     * Setup method for packetOperation and packetOperation2.
     * @throws ImmutableByteSequence.ByteSequenceTrimException if byte sequence cannot be trimmed
     */
    @Before
    public void setup() throws ImmutableByteSequence.ByteSequenceTrimException {

        packetOperation = PiPacketOperation.builder()
                .forDevice(deviceId)
                .withData(ImmutableByteSequence.ofOnes(512))
                .withType(PACKET_OUT)
                .withMetadata(PiControlMetadata.builder()
                                      .withId(PiControlMetadataId.of("egress_port"))
                                      .withValue(fit(copyFrom(DEFAULT_ORIGINAL_VALUE), DEFAULT_BIT_WIDTH))
                                      .build())
                .build();

        packetOperation2 = PiPacketOperation.builder()
                .forDevice(deviceId2)
                .withData(ImmutableByteSequence.ofOnes(512))
                .withType(PACKET_IN)
                .withMetadata(PiControlMetadata.builder()
                                      .withId(PiControlMetadataId.of("ingress_port"))
                                      .withValue(fit(copyFrom(DEFAULT_ORIGINAL_VALUE), DEFAULT_BIT_WIDTH))
                                      .build())
                .build();

        packetIn = new DefaultPacketIn(deviceId, packetOperation);
        sameAsPacketIn = new DefaultPacketIn(sameDeviceId, packetOperation);
        packetIn2 = new DefaultPacketIn(deviceId2, packetOperation);
        packetIn3 = new DefaultPacketIn(deviceId, packetOperation2);
    }

    /**
     * tearDown method for packetOperation and packetOperation2.
     */
    @After
    public void tearDown() {
        packetOperation = null;
        packetOperation2 = null;

        packetIn = null;
        sameAsPacketIn = null;
        packetIn2 = null;
        packetIn3 = null;
    }


    /**
     * Tests constructor with null object as a DeviceId parameter.
     */
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullDeviceId() {

        new DefaultPacketIn(nullDeviceId, packetOperation);
    }

    /**
     * Tests constructor with null object as PacketOperation parameter.
     */
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullPacketOperation() {

        new DefaultPacketIn(deviceId, nullPacketOperation);
    }

    /**
     * Test for deviceId method.
     */
    @Test
    public void deviceId() {
        new EqualsTester()
                .addEqualityGroup(deviceId, packetIn.deviceId(), sameAsPacketIn.deviceId())
                .addEqualityGroup(packetIn2)
                .testEquals();
    }

    /**
     * Test for packetOperation method.
     */
    @Test
    public void packetOperation() {
        new EqualsTester()
                .addEqualityGroup(packetOperation, packetIn.packetOperation())
                .addEqualityGroup(packetIn3.packetOperation())
                .testEquals();
    }

    /**
     * Checks the operation of equals(), hashCode() and toString() methods.
     */
    @Test
    public void testEquals() {
        new EqualsTester()
                .addEqualityGroup(packetIn, sameAsPacketIn)
                .addEqualityGroup(packetIn2)
                .addEqualityGroup(packetIn3)
                .testEquals();
    }
}