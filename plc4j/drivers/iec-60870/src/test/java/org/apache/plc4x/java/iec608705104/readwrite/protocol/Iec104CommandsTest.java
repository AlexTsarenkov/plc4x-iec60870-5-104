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
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104DataType;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;
import org.apache.plc4x.java.spi.generation.ByteOrder;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.apache.plc4x.java.spi.generation.WriteBufferByteBased;
import org.apache.plc4x.java.spi.values.PlcBOOL;
import org.apache.plc4x.java.spi.values.PlcDINT;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcREAL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the bytes of every ASDU the driver sends against encodings worked out by hand
 * from IEC 60870-5-101/104. The expected strings are the ASDU only:
 * type id, VSQ, COT, originator, common address (2, little endian), IOA (3, little endian),
 * then the information elements.
 */
class Iec104CommandsTest {

    private static final int ORIGINATOR = 0;

    @Test
    void floatSetPoint() throws Exception {
        ASDU asdu = Iec104Commands.createWrite(Iec608705104Tag.of("1/300"), Iec608705104DataType.FLOAT,
            PlcREAL.of(42.5f), ORIGINATOR);
        // C_SE_NC_1=0x32, 1 object, COT=6 act, OA 0, CA 1, IOA 300, 42.5f, QOS 0
        assertEquals("3201060001002c010000002a4200", hex(asdu));
    }

    @Test
    void scaledSetPoint() throws Exception {
        ASDU asdu = Iec104Commands.createWrite(Iec608705104Tag.of("1/310"), Iec608705104DataType.SCALED,
            PlcDINT.of(-1234), ORIGINATOR);
        // C_SE_NB_1=0x31, IOA 310=0x136, -1234 = 0xFB2E
        assertEquals("3101060001003601002efb00", hex(asdu));
    }

    @Test
    void normalizedSetPoint() throws Exception {
        ASDU asdu = Iec104Commands.createWrite(Iec608705104Tag.of("1/320"), Iec608705104DataType.NORMALIZED,
            PlcLREAL.of(-0.5), ORIGINATOR);
        // C_SE_NA_1=0x30, IOA 320=0x140, -0.5 = -16384 = 0xC000
        assertEquals("30010600010040010000c000", hex(asdu));
    }

    @Test
    void singleCommand() throws Exception {
        ASDU on = Iec104Commands.createWrite(Iec608705104Tag.of("1/400"), Iec608705104DataType.BOOL,
            PlcBOOL.of(true), ORIGINATOR);
        // C_SC_NA_1=0x2D, IOA 400=0x190, SCO: S/E=0, QU=0, SCS=1
        assertEquals("2d010600010090010001", hex(on));
        ASDU off = Iec104Commands.createWrite(Iec608705104Tag.of("1/400"), Iec608705104DataType.BOOL,
            PlcBOOL.of(false), ORIGINATOR);
        assertEquals("2d010600010090010000", hex(off));
    }

    @Test
    void generalInterrogation() throws Exception {
        // C_IC_NA_1=0x64, COT=6, CA=65535 (broadcast), IOA 0, QOI=20
        assertEquals("64010600ffff00000014", hex(Iec104Commands.createGeneralInterrogation(65535, 0)));
    }

    @Test
    void readCommand() throws Exception {
        // C_RD_NA_1=0x66, COT=5 (request), CA 1, IOA 100, no information elements
        assertEquals("660105000100640000", hex(Iec104Commands.createRead(Iec608705104Tag.of("1/100"), 0)));
    }

    @Test
    void originatorAddressIsUsed() throws Exception {
        assertEquals("660105070100640000", hex(Iec104Commands.createRead(Iec608705104Tag.of("1/100"), 7)));
    }

    @Test
    void completeFrameRoundTrip() throws Exception {
        ASDU asdu = Iec104Commands.createWrite(Iec608705104Tag.of("1/300"), Iec608705104DataType.FLOAT,
            PlcREAL.of(42.5f), ORIGINATOR);
        // N(S)=3, N(R)=5
        APDU frame = new APDUIFormat(Iec104LinkLayer.toWire(3), Iec104LinkLayer.toWire(5), asdu);
        byte[] bytes = serialize(frame);
        assertEquals("681206000a00" + "3201060001002c010000002a4200", Hex.encodeHexString(bytes));
        APDU parsed = APDU.staticParse(new ReadBufferByteBased(bytes, ByteOrder.LITTLE_ENDIAN));
        assertEquals(3, Iec104LinkLayer.fromWire(parsed.getCommand()));
        assertEquals(5, Iec104LinkLayer.fromWire(((APDUIFormat) parsed).getReceiveSequenceNo()));
        assertEquals(asdu.getTypeIdentification(), ((APDUIFormat) parsed).getAsdu().getTypeIdentification());
    }

    @Test
    void resolvesDataTypeFromSuffixOrValue() {
        assertEquals(Iec608705104DataType.SCALED,
            Iec104Commands.resolveDataType(Iec608705104Tag.of("1/1:SCALED"), PlcBOOL.of(true)));
        assertEquals(Iec608705104DataType.BOOL,
            Iec104Commands.resolveDataType(Iec608705104Tag.of("1/1"), PlcBOOL.of(true)));
        assertEquals(Iec608705104DataType.FLOAT,
            Iec104Commands.resolveDataType(Iec608705104Tag.of("1/1"), PlcDINT.of(5)));
        assertEquals(Iec608705104DataType.FLOAT,
            Iec104Commands.resolveDataType(Iec608705104Tag.of("1/1"), PlcLREAL.of(5.5)));
    }

    @Test
    void rejectsValuesTheCommandCannotCarry() {
        Iec608705104Tag tag = Iec608705104Tag.of("1/1");
        assertThrows(IllegalArgumentException.class,
            () -> Iec104Commands.createWrite(tag, Iec608705104DataType.NORMALIZED, PlcLREAL.of(1.0), 0));
        assertThrows(IllegalArgumentException.class,
            () -> Iec104Commands.createWrite(tag, Iec608705104DataType.SCALED, PlcLREAL.of(1.5), 0));
        assertThrows(IllegalArgumentException.class,
            () -> Iec104Commands.createWrite(tag, Iec608705104DataType.SCALED, PlcDINT.of(40000), 0));
        assertThrows(IllegalArgumentException.class,
            () -> Iec104Commands.createWrite(tag, Iec608705104DataType.BOOL, PlcDINT.of(2), 0));
        assertThrows(IllegalArgumentException.class,
            () -> Iec104Commands.createWrite(tag, Iec608705104DataType.FLOAT, PlcLREAL.of(Double.NaN), 0));
        assertThrows(IllegalArgumentException.class,
            () -> Iec104Commands.createWrite(tag, Iec608705104DataType.FLOAT, PlcLREAL.of(1e300), 0));
    }

    @Test
    void singleCommandAcceptsZeroAndOne() throws Exception {
        Iec608705104Tag tag = Iec608705104Tag.of("1/400");
        assertEquals("2d010600010090010001", hex(Iec104Commands.createWrite(tag, Iec608705104DataType.BOOL, PlcDINT.of(1), 0)));
        assertEquals("2d010600010090010000", hex(Iec104Commands.createWrite(tag, Iec608705104DataType.BOOL, PlcDINT.of(0), 0)));
    }

    private static String hex(ASDU asdu) throws Exception {
        WriteBufferByteBased buffer = new WriteBufferByteBased(asdu.getLengthInBytes(), ByteOrder.LITTLE_ENDIAN);
        asdu.serialize(buffer);
        return Hex.encodeHexString(buffer.getBytes());
    }

    private static byte[] serialize(APDU apdu) throws Exception {
        WriteBufferByteBased buffer = new WriteBufferByteBased(apdu.getLengthInBytes(), ByteOrder.LITTLE_ENDIAN);
        apdu.serialize(buffer);
        return buffer.getBytes();
    }

}
