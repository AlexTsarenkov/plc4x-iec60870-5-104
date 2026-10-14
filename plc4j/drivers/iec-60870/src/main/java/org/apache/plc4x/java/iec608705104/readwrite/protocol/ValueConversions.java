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

/**
 * Conversions between the wire encodings of IEC 60870-5-101/104 values and Java numbers.
 *
 * <p>The generated message classes (from the mspec) expose the raw wire fields:
 * <ul>
 *   <li>a <b>normalized value</b> (NVA) as the unsigned 16 bit field ({@code int} 0..65535),
 *       which on the wire is a signed 16 bit fraction: value = raw / 32768,
 *       range [-1, 1 - 2^-15];</li>
 *   <li>a <b>scaled value</b> (SVA) as a signed 16 bit integer ({@code short}).</li>
 * </ul>
 * Kept in one place so that reading and writing cannot disagree.
 */
public final class ValueConversions {

    /** Number of steps per 1.0 of a normalized value (2^15). */
    static final double NORMALIZED_SCALE = 32768.0;

    /** Largest normalized value, 1 - 2^-15. */
    public static final double NORMALIZED_MAX = 32767 / NORMALIZED_SCALE;

    /** Smallest normalized value. */
    public static final double NORMALIZED_MIN = -1.0;

    private ValueConversions() {
    }

    /**
     * @param raw the unsigned 16 bit NVA field as it comes out of the generated parser.
     * @return the normalized value, -1.0 to 1 - 2^-15.
     */
    public static double normalizedFromRaw(int raw) {
        return ((short) raw) / NORMALIZED_SCALE;
    }

    /**
     * @param value a normalized value, -1.0 to 1 - 2^-15.
     * @return the unsigned 16 bit NVA field for the generated serializer.
     * @throws IllegalArgumentException if the value is outside the representable range.
     */
    public static int normalizedToRaw(double value) {
        if (Double.isNaN(value) || value < NORMALIZED_MIN || value > NORMALIZED_MAX) {
            throw new IllegalArgumentException(
                "normalized value must be in [" + NORMALIZED_MIN + ", " + NORMALIZED_MAX + "], was " + value);
        }
        long steps = Math.round(value * NORMALIZED_SCALE);
        // value <= NORMALIZED_MAX guarantees steps <= 32767 after rounding.
        return ((short) steps) & 0xFFFF;
    }

    /**
     * @param value a scaled value.
     * @return the value as signed 16 bit integer.
     * @throws IllegalArgumentException if the value is not a whole number in -32768..32767.
     */
    public static short scaledToRaw(double value) {
        if (Double.isNaN(value) || value != Math.rint(value) || value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException("scaled value must be a whole number in -32768..32767, was " + value);
        }
        return (short) value;
    }

}
