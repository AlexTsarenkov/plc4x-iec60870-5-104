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

import org.apache.plc4x.java.api.types.PlcValueType;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;

import java.util.Locale;
import java.util.Optional;

/**
 * The optional type suffix of a tag address ({@code 1/300:FLOAT}).
 *
 * <p>An IEC 60870-5-104 address does not say what kind of value lives there, the station
 * decides that. For <b>monitoring</b> data that is fine: every received ASDU carries its
 * type identification, so reading and subscribing never need a type.
 *
 * <p>For <b>writing</b> the driver has to pick the command it sends, and the station only
 * accepts the command type it has configured for that information object. This enum is
 * that choice. Each constant maps to exactly one command type identification.
 *
 * <p>Every constant can be written either by its short name ({@code FLOAT}) or by the
 * IEC 60870-5-101/104 mnemonic of its command ({@code C_SE_NC_1}), case-insensitive.
 *
 * <p>Adding a new writable type (for example a double command, C_DC_NA_1) means: add a
 * constant here, add its encoding to {@code Iec104Commands}, and document it in the
 * driver documentation. See MAINTENANCE.md, section "Adding a command type".
 */
public enum Iec608705104DataType {

    /** Set point command, short floating point number (C_SE_NC_1, type 50). */
    FLOAT("C_SE_NC_1", TypeIdentification.SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER, PlcValueType.REAL),

    /** Set point command, scaled value, signed 16 bit integer (C_SE_NB_1, type 49). */
    SCALED("C_SE_NB_1", TypeIdentification.SET_POINT_COMMAND_SCALED_VALUE, PlcValueType.INT),

    /**
     * Set point command, normalized value (C_SE_NA_1, type 48). The value is a fraction
     * in the range [-1, 1 - 2^-15], transmitted as a signed 16 bit integer.
     */
    NORMALIZED("C_SE_NA_1", TypeIdentification.SET_POINT_COMMAND_NORMALISED_VALUE, PlcValueType.REAL),

    /** Single command, on/off (C_SC_NA_1, type 45). */
    BOOL("C_SC_NA_1", TypeIdentification.SINGLE_COMMAND, PlcValueType.BOOL);

    private final String mnemonic;
    private final TypeIdentification commandType;
    private final PlcValueType plcValueType;

    Iec608705104DataType(String mnemonic, TypeIdentification commandType, PlcValueType plcValueType) {
        this.mnemonic = mnemonic;
        this.commandType = commandType;
        this.plcValueType = plcValueType;
    }

    /**
     * @return the IEC mnemonic of the command, for example {@code C_SE_NC_1}.
     */
    public String getMnemonic() {
        return mnemonic;
    }

    /**
     * @return the type identification of the command that writes this type.
     */
    public TypeIdentification getCommandType() {
        return commandType;
    }

    /**
     * @return the PLC4X value type a value of this type is converted to before it is written.
     */
    public PlcValueType getPlcValueType() {
        return plcValueType;
    }

    /**
     * Resolves the type suffix of an address.
     *
     * @param text the short name or the IEC mnemonic, case-insensitive.
     * @return the matching type, or empty if there is none.
     */
    public static Optional<Iec608705104DataType> fromText(String text) {
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (Iec608705104DataType type : values()) {
            if (type.name().equals(normalized) || type.mnemonic.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

}
