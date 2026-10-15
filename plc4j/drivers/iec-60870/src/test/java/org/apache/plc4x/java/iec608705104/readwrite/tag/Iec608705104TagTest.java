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
package org.apache.plc4x.java.iec608705104.readwrite.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.types.PlcValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Iec608705104TagTest {

    @Test
    void parsesPlainAddress() {
        Iec608705104Tag tag = Iec608705104Tag.of("1/100");
        assertEquals(1, tag.getAsduAddress());
        assertEquals(100, tag.getObjectAddress());
        assertTrue(tag.getDataType().isEmpty());
        assertFalse(tag.isWildcard());
        assertEquals("1/100", tag.getAddressString());
        assertNull(tag.getPlcValueType(), "untyped tags let PLC4X keep the Java type of a written value");
    }

    @Test
    void parsesLimits() {
        Iec608705104Tag tag = Iec608705104Tag.of("65535/16777215");
        assertEquals(65535, tag.getAsduAddress());
        assertEquals(16777215, tag.getObjectAddress());
        Iec608705104Tag zero = Iec608705104Tag.of("0/0");
        assertEquals(0, zero.getAsduAddress());
        assertEquals(0, zero.getObjectAddress());
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals(new Iec608705104Tag(1, 2), Iec608705104Tag.of("  1/2 "));
    }

    @Test
    void parsesTypeSuffixByNameAndMnemonic() {
        assertEquals(Iec608705104DataType.FLOAT, Iec608705104Tag.of("1/300:FLOAT").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.FLOAT, Iec608705104Tag.of("1/300:c_se_nc_1").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.SCALED, Iec608705104Tag.of("1/310:scaled").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.SCALED, Iec608705104Tag.of("1/310:C_SE_NB_1").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.NORMALIZED, Iec608705104Tag.of("1/320:NORMALIZED").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.NORMALIZED, Iec608705104Tag.of("1/320:C_SE_NA_1").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.BOOL, Iec608705104Tag.of("1/400:BOOL").getDataType().orElseThrow());
        assertEquals(Iec608705104DataType.BOOL, Iec608705104Tag.of("1/400:C_SC_NA_1").getDataType().orElseThrow());
    }

    @Test
    void typedTagReportsValueTypeAndCanonicalAddress() {
        Iec608705104Tag tag = Iec608705104Tag.of("1/310:c_se_nb_1");
        assertEquals(PlcValueType.INT, tag.getPlcValueType());
        assertEquals("1/310:C_SE_NB_1", tag.getAddressString(), "canonical form uses the IEC mnemonic");
    }

    @Test
    void parsesWildcards() {
        Iec608705104Tag station = Iec608705104Tag.of("1/*");
        assertEquals(1, station.getAsduAddress());
        assertEquals(Iec608705104Tag.WILDCARD, station.getObjectAddress());
        assertTrue(station.isWildcard());
        assertEquals("1/*", station.getAddressString());

        Iec608705104Tag everything = Iec608705104Tag.of("*/*");
        assertTrue(everything.matches(7, 12345));
        assertEquals("*/*", everything.getAddressString());
    }

    @Test
    void matchesRespectsBothParts() {
        Iec608705104Tag exact = Iec608705104Tag.of("1/100");
        assertTrue(exact.matches(1, 100));
        assertFalse(exact.matches(1, 101));
        assertFalse(exact.matches(2, 100));

        Iec608705104Tag anyStation = Iec608705104Tag.of("*/100");
        assertTrue(anyStation.matches(5, 100));
        assertFalse(anyStation.matches(5, 101));

        assertTrue(Iec608705104Tag.of("1/100:FLOAT").matches(1, 100), "the type suffix does not affect matching");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "*", "1", "1/", "/1", "1/2/3", "1/2.3.4", "65536/1", "1/16777216", "1/-1",
        "1/100:", "1/100:TRIPLE", "1 /100", "a/b", "000001/1", "1/100:FLOAT:BOOL",
        // options: unknown, not for this type, out of range, missing or superfluous value
        "1/100:FLOAT,fast", "1/100:FLOAT,qu=1", "1/100:BITSTRING,sbo", "1/100:BOOL,qu=32",
        "1/100:FLOAT,ql=128", "1/100:BOOL,qu", "1/100:BOOL,sbo=1", "1/100:P_ME_NC_1,time",
        "1/100:C_CS_NA_1,time", "1/100,sbo", "1/100:FLOAT,", "1/100:P_ME_NC_1,kpa=huge"
    })
    void rejectsInvalidAddresses(String address) {
        assertThrows(PlcInvalidTagException.class, () -> Iec608705104Tag.of(address));
    }

    @Test
    void parsesAllTypesByNameAndMnemonic() {
        for (Iec608705104DataType type : Iec608705104DataType.values()) {
            assertEquals(type, Iec608705104Tag.of("1/1:" + type.name()).getDataType().orElseThrow());
            Iec608705104Tag byMnemonic = Iec608705104Tag.of("1/1:" + type.getMnemonic());
            assertEquals(type, byMnemonic.getDataType().orElseThrow());
            assertFalse(byMnemonic.isTimeTagged());
            if (type.hasTimeTaggedVariant()) {
                Iec608705104Tag timeTagged = Iec608705104Tag.of("1/1:" + type.getMnemonic(true).toLowerCase());
                assertEquals(type, timeTagged.getDataType().orElseThrow());
                assertTrue(timeTagged.isTimeTagged());
                assertEquals(timeTagged, Iec608705104Tag.of("1/1:" + type.name() + ",time"));
            }
        }
    }

    @Test
    void parsesOptions() {
        Iec608705104Tag tag = Iec608705104Tag.of("1/500:DOUBLE,SBO,qu=short");
        assertEquals(Iec608705104DataType.DOUBLE, tag.getDataType().orElseThrow());
        assertEquals(true, tag.getSelectBeforeOperate().orElseThrow());
        assertEquals(1, tag.getQualifierOfCommand().orElseThrow());
        assertEquals("1/500:C_DC_NA_1,sbo,qu=1", tag.getAddressString());

        Iec608705104Tag direct = Iec608705104Tag.of("1/300:C_SE_TC_1,direct,ql=7");
        assertEquals(false, direct.getSelectBeforeOperate().orElseThrow());
        assertEquals(7, direct.getQualifierOfSetPoint().orElseThrow());
        assertTrue(direct.isTimeTagged());
        assertEquals("1/300:C_SE_TC_1,direct,ql=7", direct.getAddressString());

        assertEquals(4, Iec608705104Tag.of("1/800:P_ME_NC_1,kpa=high").getKindOfParameter().orElseThrow());
        assertEquals(2, Iec608705104Tag.of("1/0:C_CI_NA_1,frz=freeze_reset").getFreeze().orElseThrow());
        assertTrue(Iec608705104Tag.of("1/800:P_AC_NA_1,deact").isDeactivate());

        Iec608705104Tag plain = Iec608705104Tag.of("1/300:FLOAT");
        assertTrue(plain.getSelectBeforeOperate().isEmpty(), "connection default applies");
        assertTrue(plain.getQualifierOfSetPoint().isEmpty());
    }

    @Test
    void valueTypesForWriting() {
        assertEquals(PlcValueType.BOOL, Iec608705104Tag.of("1/1:BOOL").getPlcValueType());
        assertNull(Iec608705104Tag.of("1/1:DOUBLE").getPlcValueType(), "boolean or string");
        assertNull(Iec608705104Tag.of("1/1:STEP").getPlcValueType(), "any whole number");
        assertNull(Iec608705104Tag.of("1/1:C_CS_NA_1").getPlcValueType(), "date/time or anything");
        assertEquals(PlcValueType.REAL, Iec608705104Tag.of("1/1:P_ME_NC_1").getPlcValueType());
    }

    @Test
    void handlerDelegatesToParser() {
        assertEquals(new Iec608705104Tag(1, 100), new Iec608705104TagHandler().parseTag("1/100"));
        assertThrows(PlcInvalidTagException.class, () -> new Iec608705104TagHandler().parseTag("*"));
    }

    @Test
    void equalityIncludesType() {
        assertEquals(Iec608705104Tag.of("1/300"), Iec608705104Tag.of("1/300"));
        assertFalse(Iec608705104Tag.of("1/300").equals(Iec608705104Tag.of("1/300:FLOAT")));
    }

}
