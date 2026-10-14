/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.plc4x.java.iec608705104.readwrite.protocol;

import org.apache.commons.codec.binary.Hex;
import org.apache.plc4x.java.iec608705104.readwrite.APDU;
import org.apache.plc4x.java.iec608705104.readwrite.APDUIFormat;
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.spi.generation.ByteOrder;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class Iec104FrameDecoderTest {

    /**
     * Answer of lib60870 (c104 test server) to a general interrogation, captured in CI:
     * M_ME_NC_1, SQ=1, two objects starting at IOA 100: 12.5 and 55.43.
     * The unmodified generated parser fails on it.
     */
    private static final String SEQUENCE_FRAME = "6817080002000d8214000100640000000048410052b85d4200";

    @Test
    void expandsSequenceIntoExplicitAddresses() throws Exception {
        byte[] expanded = Iec104FrameDecoder.expandSequence(Hex.decodeHex(SEQUENCE_FRAME));
        assertEquals("681a08000200" + "0d0214000100" + "640000" + "0000484100" + "650000" + "52b85d4200",
            Hex.encodeHexString(expanded));
    }

    @Test
    void parsesSequenceFrame() throws Exception {
        APDU apdu = Iec104FrameDecoder.parse(new ReadBufferByteBased(Hex.decodeHex(SEQUENCE_FRAME), ByteOrder.LITTLE_ENDIAN));
        ASDU asdu = ((APDUIFormat) apdu).getAsdu();
        assertEquals(TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER, asdu.getTypeIdentification());
        assertEquals(2, asdu.getInformationObjects().size());
        InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER first =
            (InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER) asdu.getInformationObjects().get(0);
        InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER second =
            (InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER) asdu.getInformationObjects().get(1);
        assertEquals(100, first.getAddress());
        assertEquals(12.5f, first.getValue());
        assertEquals(101, second.getAddress());
        assertEquals(55.43f, second.getValue(), 1e-4);
        assertEquals(4, Iec104LinkLayer.fromWire(apdu.getCommand()), "sequence number unchanged");
    }

    @Test
    void leavesOtherFramesAlone() throws Exception {
        byte[] withAddresses = Hex.decodeHex("681206000a003201060001002c010000002a4200");
        assertSame(withAddresses, Iec104FrameDecoder.expandSequence(withAddresses));
        byte[] testFrame = Hex.decodeHex("680443000000");
        assertSame(testFrame, Iec104FrameDecoder.expandSequence(testFrame));
        byte[] supervisory = Hex.decodeHex("680401000a00");
        assertSame(supervisory, Iec104FrameDecoder.expandSequence(supervisory));
    }

    @Test
    void parsesNormalFramesUnchanged() throws Exception {
        APDU apdu = Iec104FrameDecoder.parse(new ReadBufferByteBased(
            Hex.decodeHex("681206000a003201060001002c010000002a4200"), ByteOrder.LITTLE_ENDIAN));
        assertEquals(300, ((APDUIFormat) apdu).getAsdu().getInformationObjects().get(0).getAddress());
    }

    @Test
    void leavesInconsistentSequenceToTheParser() throws Exception {
        // SQ=1 with 3 objects, but 10 bytes of elements: not divisible.
        byte[] broken = Hex.decodeHex("6817080002000d8314000100640000000048410052b85d4200");
        assertSame(broken, Iec104FrameDecoder.expandSequence(broken));
    }

}
