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
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.iec608705104.readwrite.APDU;
import org.apache.plc4x.java.iec608705104.readwrite.APDUIFormat;
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104DataType;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;
import org.apache.plc4x.java.spi.generation.ByteOrder;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.apache.plc4x.java.spi.generation.WriteBufferByteBased;
import org.apache.plc4x.java.spi.values.PlcBOOL;
import org.apache.plc4x.java.spi.values.PlcDATE_AND_TIME;
import org.apache.plc4x.java.spi.values.PlcDINT;
import org.apache.plc4x.java.spi.values.PlcLINT;
import org.apache.plc4x.java.spi.values.PlcLREAL;
import org.apache.plc4x.java.spi.values.PlcREAL;
import org.apache.plc4x.java.spi.values.PlcSTRING;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the bytes of every ASDU the driver sends against encodings worked out by hand
 * from IEC 60870-5-101/104. The expected strings are the ASDU only:
 * type id, VSQ, COT, originator, common address (2, little endian), IOA (3, little endian),
 * then the information elements.
 *
 * <p>"Now" is fixed to 2026-10-15T09:30:15.250Z, a Thursday. As CP56Time2a in UTC:
 * milliseconds 15250 = {@code 92 3b}, minutes 30 = {@code 1e}, hour 9 = {@code 09},
 * day of week 4 and day 15 = {@code 8f}, month 10 = {@code 0a}, year 26 = {@code 1a}.
 */
class Iec104CommandsTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:30:15.250Z");
    private static final String NOW_CP56 = "923b1e098f0a1a";
    private static final Iec104Time TIME = new Iec104Time(ZoneOffset.UTC, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Iec104Commands.Context execute() {
        return new Iec104Commands.Context(0, false, TIME, 7);
    }

    private static Iec104Commands.Context select() {
        return new Iec104Commands.Context(0, true, TIME, 7);
    }

    private static String write(String address, PlcValue value) throws Exception {
        return write(address, value, execute());
    }

    private static String write(String address, PlcValue value, Iec104Commands.Context context) throws Exception {
        Iec608705104Tag tag = Iec608705104Tag.of(address);
        return hex(Iec104Commands.createWrite(tag, Iec104Commands.resolveDataType(tag, value), value, context));
    }

    // -- set points and single commands -----------------------------------------------------

    @Test
    void floatSetPoint() throws Exception {
        // C_SE_NC_1=0x32, 1 object, COT=6 act, OA 0, CA 1, IOA 300, 42.5f, QOS 0
        assertEquals("3201060001002c010000002a4200", write("1/300", PlcREAL.of(42.5f)));
    }

    @Test
    void scaledSetPoint() throws Exception {
        // C_SE_NB_1=0x31, IOA 310=0x136, -1234 = 0xFB2E
        assertEquals("3101060001003601002efb00", write("1/310:SCALED", PlcDINT.of(-1234)));
    }

    @Test
    void normalizedSetPoint() throws Exception {
        // C_SE_NA_1=0x30, IOA 320=0x140, -0.5 = -16384 = 0xC000
        assertEquals("30010600010040010000c000", write("1/320:NORMALIZED", PlcLREAL.of(-0.5)));
    }

    @Test
    void setPointQualifierAndSelect() throws Exception {
        // QOS = S/E (bit 7) | QL 5
        assertEquals("3201060001002c010000002a4285", write("1/300:FLOAT,ql=5", PlcREAL.of(42.5f), select()));
        assertEquals("3201060001002c010000002a4205", write("1/300:FLOAT,ql=5", PlcREAL.of(42.5f)));
    }

    @Test
    void singleCommand() throws Exception {
        // C_SC_NA_1=0x2D, IOA 400=0x190, SCO: S/E=0, QU=0, SCS=1
        assertEquals("2d010600010090010001", write("1/400", PlcBOOL.of(true)));
        assertEquals("2d010600010090010000", write("1/400", PlcBOOL.of(false)));
        assertEquals("2d010600010090010001", write("1/400:BOOL", PlcDINT.of(1)));
    }

    @Test
    void singleCommandQualifierAndSelect() throws Exception {
        // SCO = S/E (bit 7) | QU 2 (long pulse, bits 6..2) | SCS 1 = 0x80 | 0x08 | 0x01
        assertEquals("2d010600010090010089", write("1/400:BOOL,qu=long", PlcBOOL.of(true), select()));
        assertEquals("2d01060001009001000d", write("1/400:BOOL,qu=3", PlcBOOL.of(true)));
    }

    // -- double, step, bitstring -------------------------------------------------------------

    @Test
    void doubleCommand() throws Exception {
        // C_DC_NA_1=0x2E, IOA 500=0x1F4, DCS 2 = on, 1 = off
        assertEquals("2e0106000100f4010002", write("1/500:C_DC_NA_1", PlcBOOL.of(true)));
        assertEquals("2e0106000100f4010001", write("1/500:DOUBLE", PlcBOOL.of(false)));
        assertEquals("2e0106000100f4010002", write("1/500:DOUBLE", new PlcSTRING("on")));
        // QU 1 (short pulse) = 0x04, select = 0x80
        assertEquals("2e0106000100f4010086", write("1/500:DOUBLE,qu=short", PlcBOOL.of(true), select()));
    }

    @Test
    void doubleCommandRejectsNumbers() {
        // 1 would mean "off" on the wire, but "on" for a boolean-minded caller.
        assertThrows(IllegalArgumentException.class, () -> write("1/500:DOUBLE", PlcDINT.of(1)));
        assertThrows(IllegalArgumentException.class, () -> write("1/500:DOUBLE", new PlcSTRING("maybe")));
    }

    @Test
    void stepCommand() throws Exception {
        // C_RC_NA_1=0x2F, IOA 600=0x258, RCS 2 = higher, 1 = lower; one command = one step
        assertEquals("2f010600010058020002", write("1/600:STEP", PlcDINT.of(3)));
        assertEquals("2f010600010058020001", write("1/600:C_RC_NA_1", PlcDINT.of(-1)));
        assertEquals(3, Iec104Commands.steps(PlcDINT.of(3)));
        assertEquals(-2, Iec104Commands.steps(PlcLREAL.of(-2.0)));
    }

    @Test
    void stepCommandRejectsZeroFractionsAndTooManySteps() {
        assertThrows(IllegalArgumentException.class, () -> Iec104Commands.steps(PlcDINT.of(0)));
        assertThrows(IllegalArgumentException.class, () -> Iec104Commands.steps(PlcLREAL.of(1.5)));
        assertThrows(IllegalArgumentException.class, () -> Iec104Commands.steps(PlcDINT.of(Iec104Commands.MAX_STEPS + 1)));
    }

    @Test
    void bitstringCommand() throws Exception {
        // C_BO_NA_1=0x33, IOA 700=0x2BC, bits little endian
        assertEquals("330106000100bc020078563412", write("1/700:BITSTRING", PlcDINT.of(0x12345678)));
        assertEquals("330106000100bc0200ffffffff", write("1/700:C_BO_NA_1", PlcDINT.of(-1)));
        assertEquals("330106000100bc0200ffffffff", write("1/700:C_BO_NA_1", PlcLINT.of(0xFFFFFFFFL)));
        assertThrows(IllegalArgumentException.class, () -> write("1/700:C_BO_NA_1", PlcLINT.of(0x100000000L)));
    }

    // -- with time tag -------------------------------------------------------------------------

    @Test
    void timeTaggedCommands() throws Exception {
        // C_SC_TA_1=0x3A, C_DC_TA_1=0x3B, C_RC_TA_1=0x3C, C_SE_TA_1=0x3D, C_SE_TB_1=0x3E,
        // C_SE_TC_1=0x3F, C_BO_TA_1=0x40: the elements of the plain command, then CP56Time2a.
        assertEquals("3a010600010090010001" + NOW_CP56, write("1/400:C_SC_TA_1", PlcBOOL.of(true)));
        assertEquals("3b0106000100f4010002" + NOW_CP56, write("1/500:C_DC_TA_1", PlcBOOL.of(true)));
        assertEquals("3c010600010058020002" + NOW_CP56, write("1/600:C_RC_TA_1", PlcDINT.of(1)));
        assertEquals("3d010600010040010000c000" + NOW_CP56, write("1/320:C_SE_TA_1", PlcLREAL.of(-0.5)));
        assertEquals("3e0106000100360100" + "2efb00" + NOW_CP56, write("1/310:C_SE_TB_1", PlcDINT.of(-1234)));
        assertEquals("3f01060001002c010000002a4200" + NOW_CP56, write("1/300:C_SE_TC_1", PlcREAL.of(42.5f)));
        assertEquals("400106000100bc020078563412" + NOW_CP56, write("1/700:C_BO_TA_1", PlcDINT.of(0x12345678)));
    }

    @Test
    void timeOptionEqualsTimeTaggedMnemonic() throws Exception {
        assertEquals(write("1/300:C_SE_TC_1,sbo", PlcREAL.of(1f), select()),
            write("1/300:FLOAT,time,sbo", PlcREAL.of(1f), select()));
    }

    // -- parameters ------------------------------------------------------------------------------

    @Test
    void parameterOfMeasuredValues() throws Exception {
        // P_ME_NC_1=0x70, IOA 800=0x320, 1.5f, QPM: POP 0, LPC 0, KPA 1 (threshold)
        assertEquals("7001060001002003000000c03f01", write("1/800:P_ME_NC_1", PlcREAL.of(1.5f)));
        // KPA 4 (high limit)
        assertEquals("7001060001002003000000c03f04", write("1/800:PARAM_FLOAT,kpa=high", PlcREAL.of(1.5f)));
        // P_ME_NB_1=0x6F scaled, P_ME_NA_1=0x6E normalized
        assertEquals("6f0106000100200300640001", write("1/800:P_ME_NB_1", PlcDINT.of(100)));
        assertEquals("6e010600010020030000c001", write("1/800:P_ME_NA_1", PlcLREAL.of(-0.5)));
    }

    @Test
    void parameterActivation() throws Exception {
        // P_AC_NA_1=0x71, QPA 3, COT 6 activation / 8 deactivation
        assertEquals("71010600010020030003", write("1/800:P_AC_NA_1", PlcDINT.of(3)));
        assertEquals("71010800010020030003", write("1/800:P_AC_NA_1,deact", PlcDINT.of(3)));
    }

    // -- system commands ---------------------------------------------------------------------------

    @Test
    void interrogationAndCounterInterrogation() throws Exception {
        // C_IC_NA_1=0x64, IOA 0, QOI 21 = group 1
        assertEquals("64010600010000000015", write("1/0:C_IC_NA_1", PlcDINT.of(21)));
        // C_CI_NA_1=0x65, QCC: FRZ (bits 7..6), RQT (bits 5..0) = 5 general
        assertEquals("65010600010000000005", write("1/0:C_CI_NA_1", PlcDINT.of(5)));
        assertEquals("65010600010000000045", write("1/0:C_CI_NA_1,frz=freeze", PlcDINT.of(5)));
    }

    @Test
    void clockSynchronisation() throws Exception {
        // C_CS_NA_1=0x67, IOA 0, CP56Time2a of now for a value that is not a date ...
        assertEquals("670106000100000000" + NOW_CP56, write("1/0:C_CS_NA_1", PlcDINT.of(0)));
        // ... or of the given time: 2026-01-02 03:04:05.006, a Friday (5)
        PlcValue value = new PlcDATE_AND_TIME(LocalDateTime.parse("2026-01-02T03:04:05.006"));
        String expected = "670106000100000000" + Hex.encodeHexString(new byte[]{
            (byte) 0x8e, 0x13, 0x04, 0x03, (byte) (5 << 5 | 2), 0x01, 0x1a});
        String actual = write("1/0:C_CS_NA_1", value);
        // PlcDATE_AND_TIME may drop the milliseconds; compare everything after them.
        assertEquals(expected.substring(22), actual.substring(22));
    }

    @Test
    void testCommands() throws Exception {
        // C_TS_NA_1=0x68, fixed test pattern 0x55AA little endian
        assertEquals("680106000100000000aa55", write("1/0:C_TS_NA_1", PlcDINT.of(0)));
        // C_TS_TA_1=0x6B, test sequence counter 7, then CP56Time2a
        assertEquals("6b0106000100000000" + "0700" + NOW_CP56, write("1/0:C_TS_TA_1", PlcDINT.of(0)));
        assertEquals(write("1/0:C_TS_TA_1", PlcDINT.of(0)), write("1/0:TEST,time", PlcDINT.of(0)));
    }

    @Test
    void resetProcessAndDelayAcquisition() throws Exception {
        // C_RP_NA_1=0x69, QRP 1
        assertEquals("69010600010000000001", write("1/0:C_RP_NA_1", PlcDINT.of(1)));
        // C_CD_NA_1=0x6A, CP16Time2a 250 ms
        assertEquals("6a0106000100000000fa00", write("1/0:C_CD_NA_1", PlcDINT.of(250)));
        assertThrows(IllegalArgumentException.class, () -> write("1/0:C_CD_NA_1", PlcDINT.of(60000)));
    }

    // -- general -------------------------------------------------------------------------------------

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
        assertEquals("2d010607010090010001",
            write("1/400", PlcBOOL.of(true), new Iec104Commands.Context(7, false, TIME, 0)));
    }

    @Test
    void completeFrameRoundTrip() throws Exception {
        ASDU asdu = Iec104Commands.createWrite(Iec608705104Tag.of("1/300"), Iec608705104DataType.FLOAT,
            PlcREAL.of(42.5f), execute());
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
    void timeTaggedCommandRoundTrip() throws Exception {
        // The station mirrors the command as ACT_CON; the driver must be able to parse it.
        String asdu = write("1/300:C_SE_TC_1", PlcREAL.of(42.5f));
        byte[] frame = Hex.decodeHex("6819" + "00000000" + asdu);
        APDU parsed = APDU.staticParse(new ReadBufferByteBased(frame, ByteOrder.LITTLE_ENDIAN));
        ASDU parsedAsdu = ((APDUIFormat) parsed).getAsdu();
        assertEquals(asdu, hex(parsedAsdu));
    }

    @Test
    void resolvesDataTypeFromTypeOrValue() {
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
    void selectIsOnlyPossibleWhereTheCommandHasTheBit() {
        assertTrue(Iec104Commands.supportsSelect(Iec608705104DataType.BOOL));
        assertTrue(Iec104Commands.supportsSelect(Iec608705104DataType.DOUBLE));
        assertTrue(Iec104Commands.supportsSelect(Iec608705104DataType.STEP));
        assertTrue(Iec104Commands.supportsSelect(Iec608705104DataType.FLOAT));
        assertFalse(Iec104Commands.supportsSelect(Iec608705104DataType.BITSTRING));
        assertFalse(Iec104Commands.supportsSelect(Iec608705104DataType.CLOCK_SYNC));
    }

    @Test
    void rejectsValuesTheCommandCannotCarry() {
        assertThrows(IllegalArgumentException.class, () -> write("1/1:NORMALIZED", PlcLREAL.of(1.0)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:SCALED", PlcLREAL.of(1.5)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:SCALED", PlcDINT.of(40000)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:BOOL", PlcDINT.of(2)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:FLOAT", PlcLREAL.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:FLOAT", PlcLREAL.of(1e300)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:P_AC_NA_1", PlcDINT.of(256)));
        assertThrows(IllegalArgumentException.class, () -> write("1/1:C_CI_NA_1", PlcDINT.of(64)));
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
