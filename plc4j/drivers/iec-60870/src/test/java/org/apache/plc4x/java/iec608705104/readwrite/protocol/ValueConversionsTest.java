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

import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.iec608705104.readwrite.NormalizedValue;
import org.apache.plc4x.java.iec608705104.readwrite.ScaledValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueConversionsTest {

    @Test
    void normalizedFromRawCoversTheWholeRange() {
        assertEquals(0.0, ValueConversions.normalizedFromRaw(0x0000));
        assertEquals(0.5, ValueConversions.normalizedFromRaw(0x4000));
        assertEquals(-0.5, ValueConversions.normalizedFromRaw(0xC000));
        assertEquals(-1.0, ValueConversions.normalizedFromRaw(0x8000));
        assertEquals(32767 / 32768.0, ValueConversions.normalizedFromRaw(0x7FFF));
    }

    @Test
    void normalizedToRawIsTheInverse() {
        for (int raw = 0; raw <= 0xFFFF; raw += 257) {
            assertEquals(raw, ValueConversions.normalizedToRaw(ValueConversions.normalizedFromRaw(raw)));
        }
    }

    @Test
    void normalizedToRawRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ValueConversions.normalizedToRaw(1.0));
        assertThrows(IllegalArgumentException.class, () -> ValueConversions.normalizedToRaw(-1.0001));
        assertThrows(IllegalArgumentException.class, () -> ValueConversions.normalizedToRaw(Double.NaN));
        assertEquals(0x7FFF, ValueConversions.normalizedToRaw(ValueConversions.NORMALIZED_MAX));
        assertEquals(0x8000, ValueConversions.normalizedToRaw(-1.0));
    }

    @Test
    void scaledToRaw() {
        assertEquals((short) -1234, ValueConversions.scaledToRaw(-1234));
        assertEquals(Short.MAX_VALUE, ValueConversions.scaledToRaw(32767));
        assertEquals(Short.MIN_VALUE, ValueConversions.scaledToRaw(-32768));
        assertThrows(IllegalArgumentException.class, () -> ValueConversions.scaledToRaw(32768));
        assertThrows(IllegalArgumentException.class, () -> ValueConversions.scaledToRaw(0.5));
    }

    @Test
    void parserReturnsSignedValues() {
        PlcValue normalized = Iec608705104TagParser.processNormalizedValue(new NormalizedValue(0xC000));
        assertEquals(PlcValueType.REAL, normalized.getPlcValueType());
        assertEquals(-0.5, normalized.getDouble(), 1e-9);

        PlcValue scaled = Iec608705104TagParser.processScaledValue(new ScaledValue((short) -1234));
        assertEquals(PlcValueType.INT, scaled.getPlcValueType());
        assertEquals(-1234, scaled.getInt());
    }

}
