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

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The command a write request sends, chosen by the type part of a tag address
 * ({@code 1/300:C_SE_NC_1}).
 *
 * <p>An IEC 60870-5-104 address does not say what kind of value lives there, the station
 * decides that. For <b>monitoring</b> data that is fine: every received ASDU carries its
 * type identification, so reading and subscribing never need a type. For <b>writing</b> the
 * driver has to pick the command it sends, and the station only accepts the command type
 * it has configured for that information object.
 *
 * <p>Each constant is one kind of command. Most kinds exist in two variants, without and
 * with time tag CP56Time2a; which one is sent depends on the mnemonic in the address
 * ({@code C_SE_NC_1} or {@code C_SE_TC_1}) or the {@code time} option
 * ({@code 1/300:FLOAT,time}). See {@link Iec608705104Tag} for the address syntax and the
 * options.
 *
 * <p>Every constant can be written by its short name ({@code FLOAT}) or by an IEC mnemonic
 * ({@code C_SE_NC_1}, {@code C_SE_TC_1}), case-insensitive.
 *
 * <p>Adding a command means: a constant here, its encoding in {@code Iec104Commands},
 * support in the test station, documentation. See MAINTENANCE.md, "Adding a command type".
 */
public enum Iec608705104DataType {

    // -- process information in control direction ---------------------------------------

    /** Single command: on/off. Value: boolean, or 0/1. */
    BOOL("C_SC_NA_1", TypeIdentification.SINGLE_COMMAND,
        "C_SC_TA_1", TypeIdentification.SINGLE_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        PlcValueType.BOOL, Option.SELECT, Option.QU),

    /**
     * Double command: on/off as two bits (DCS 1 = off, 2 = on), typical for circuit breakers.
     * Value: boolean ({@code true} = on) or the string "ON"/"OFF". Numbers are rejected, see
     * {@code Iec104Commands}.
     */
    DOUBLE("C_DC_NA_1", TypeIdentification.DOUBLE_COMMAND,
        "C_DC_TA_1", TypeIdentification.DOUBLE_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        null, Option.SELECT, Option.QU),

    /**
     * Regulating step command, for example a tap changer. Value: number of steps, positive
     * = higher, negative = lower. One command on the wire is one step, so the driver sends
     * one command per step, each confirmed by the station before the next one.
     */
    STEP("C_RC_NA_1", TypeIdentification.REGULATING_STEP_COMMAND,
        "C_RC_TA_1", TypeIdentification.REGULATING_STEP_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        null, Option.SELECT, Option.QU),

    /**
     * Set point command, normalized value. The value is a fraction in [-1, 1 - 2^-15],
     * transmitted as a signed 16 bit integer.
     */
    NORMALIZED("C_SE_NA_1", TypeIdentification.SET_POINT_COMMAND_NORMALISED_VALUE,
        "C_SE_TA_1", TypeIdentification.MEASURED_VALUE_NORMALISED_VALUE_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        PlcValueType.REAL, Option.SELECT, Option.QL),

    /** Set point command, scaled value: a signed 16 bit integer. */
    SCALED("C_SE_NB_1", TypeIdentification.SET_POINT_COMMAND_SCALED_VALUE,
        "C_SE_TB_1", TypeIdentification.MEASURED_VALUE_SCALED_VALUE_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        PlcValueType.INT, Option.SELECT, Option.QL),

    /** Set point command, short floating point number (32 bit float). */
    FLOAT("C_SE_NC_1", TypeIdentification.SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER,
        "C_SE_TC_1", TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        PlcValueType.REAL, Option.SELECT, Option.QL),

    /** Bitstring of 32 bit command. Value: a number, its lowest 32 bits are sent. */
    BITSTRING("C_BO_NA_1", TypeIdentification.BITSTRING_32_BIT_COMMAND,
        "C_BO_TA_1", TypeIdentification.BITSTRING_OF_32_BIT_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        null),

    // -- parameters in control direction ------------------------------------------------

    /** Parameter of measured values, normalized value (threshold, smoothing, limits). */
    PARAM_NORMALIZED("P_ME_NA_1", TypeIdentification.PARAMETER_OF_MEASURED_VALUES_NORMALIZED_VALUE,
        null, null, PlcValueType.REAL, Option.KPA),

    /** Parameter of measured values, scaled value. */
    PARAM_SCALED("P_ME_NB_1", TypeIdentification.PARAMETER_OF_MEASURED_VALUES_SCALED_VALUE,
        null, null, PlcValueType.INT, Option.KPA),

    /** Parameter of measured values, short floating point number. */
    PARAM_FLOAT("P_ME_NC_1", TypeIdentification.PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER,
        null, null, PlcValueType.REAL, Option.KPA),

    /**
     * Parameter activation. Value: qualifier of parameter activation (QPA), for example 3 =
     * activation of persistent cyclic or periodic transmission of the addressed object.
     */
    PARAM_ACTIVATION("P_AC_NA_1", TypeIdentification.PARAMETER_ACTIVATION,
        null, null, null, Option.DEACTIVATE),

    // -- system information in control direction ----------------------------------------

    /**
     * Interrogation command. Value: qualifier of interrogation (QOI), 20 = station
     * (general) interrogation, 21..36 = group 1..16. Usually sent to IOA 0.
     */
    INTERROGATION("C_IC_NA_1", TypeIdentification.INTERROGATION_COMMAND,
        null, null, null),

    /**
     * Counter interrogation command. Value: request qualifier RQT, 1..4 = group 1..4,
     * 5 = general. Freeze/reset with the {@code frz} option. Usually sent to IOA 0.
     */
    COUNTER_INTERROGATION("C_CI_NA_1", TypeIdentification.COUNTER_INTERROGATION_COMMAND,
        null, null, null, Option.FRZ),

    /**
     * Clock synchronization command. Value: a date/time to set, or anything else (for
     * example {@code 0}) for the current time. Encoded in the {@code time-zone} of the
     * connection. Usually sent to IOA 0.
     */
    CLOCK_SYNC("C_CS_NA_1", TypeIdentification.CLOCK_SYNCHRONISATION_COMMAND,
        null, null, null),

    /**
     * Test command. Value: ignored. {@code C_TS_NA_1} sends the fixed test pattern;
     * {@code C_TS_TA_1} (the variant IEC 104 actually uses) sends a test sequence counter
     * and the current time. Usually sent to IOA 0.
     */
    TEST("C_TS_NA_1", TypeIdentification.TEST_COMMAND,
        "C_TS_TA_1", TypeIdentification.TEST_COMMAND_WITH_TIME_TAG_CP56TIME2A,
        null),

    /**
     * Reset process command. Value: qualifier of reset process (QRP), 1 = general reset of
     * process, 2 = reset of pending information with time tag of the event buffer.
     */
    RESET_PROCESS("C_RP_NA_1", TypeIdentification.RESET_PROCESS_COMMAND,
        null, null, null),

    /**
     * Delay acquisition command. Value: delay in milliseconds, 0..59999. Defined for serial
     * links (IEC 101); not part of the IEC 104 profile, but some stations accept it.
     */
    DELAY_ACQUISITION("C_CD_NA_1", TypeIdentification.DELAY_ACQUISITION_COMMAND,
        null, null, null);

    /**
     * Options a tag of this type may carry, see {@link Iec608705104Tag}.
     */
    public enum Option {
        /** {@code sbo} / {@code direct}: select-before-operate or direct execute (S/E bit). */
        SELECT,
        /** {@code qu=}: qualifier of command, pulse duration (single, double, step). */
        QU,
        /** {@code ql=}: qualifier of set point command. */
        QL,
        /** {@code kpa=}: kind of parameter (P_ME). */
        KPA,
        /** {@code frz=}: freeze/reset of counter interrogation. */
        FRZ,
        /** {@code deact}: deactivation instead of activation (P_AC). */
        DEACTIVATE
    }

    private final String mnemonic;
    private final TypeIdentification commandType;
    private final String timeTaggedMnemonic;
    private final TypeIdentification timeTaggedCommandType;
    private final PlcValueType plcValueType;
    private final Set<Option> options;

    Iec608705104DataType(String mnemonic, TypeIdentification commandType,
                         String timeTaggedMnemonic, TypeIdentification timeTaggedCommandType,
                         PlcValueType plcValueType, Option... options) {
        this.mnemonic = mnemonic;
        this.commandType = commandType;
        this.timeTaggedMnemonic = timeTaggedMnemonic;
        this.timeTaggedCommandType = timeTaggedCommandType;
        this.plcValueType = plcValueType;
        this.options = options.length == 0 ? EnumSet.noneOf(Option.class) : EnumSet.of(options[0], options);
    }

    /**
     * @return the IEC mnemonic of the command without time tag, for example {@code C_SE_NC_1}.
     */
    public String getMnemonic() {
        return mnemonic;
    }

    /**
     * @param timeTagged whether the variant with time tag CP56Time2a is meant.
     * @return the IEC mnemonic of that variant.
     * @throws IllegalArgumentException if this type has no variant with time tag.
     */
    public String getMnemonic(boolean timeTagged) {
        if (!timeTagged) {
            return mnemonic;
        }
        if (timeTaggedMnemonic == null) {
            throw new IllegalArgumentException(name() + " has no variant with time tag");
        }
        return timeTaggedMnemonic;
    }

    /**
     * @return the type identification of the command without time tag.
     */
    public TypeIdentification getCommandType() {
        return commandType;
    }

    /**
     * @param timeTagged whether the variant with time tag CP56Time2a is meant.
     * @return the type identification of that variant.
     * @throws IllegalArgumentException if this type has no variant with time tag.
     */
    public TypeIdentification getCommandType(boolean timeTagged) {
        if (!timeTagged) {
            return commandType;
        }
        if (timeTaggedCommandType == null) {
            throw new IllegalArgumentException(name() + " has no variant with time tag");
        }
        return timeTaggedCommandType;
    }

    /**
     * @return {@code true} if a variant with time tag CP56Time2a exists.
     */
    public boolean hasTimeTaggedVariant() {
        return timeTaggedCommandType != null;
    }

    /**
     * @param option an option.
     * @return {@code true} if tags of this type may carry the option.
     */
    public boolean supports(Option option) {
        return options.contains(option);
    }

    /**
     * @return the PLC4X value type a written value is converted to before the driver sees it,
     * or {@code null} to keep the Java type the user passed in (used where several kinds of
     * value are accepted, for example boolean or number).
     */
    public PlcValueType getPlcValueType() {
        return plcValueType;
    }

    /**
     * A type as written in an address, with the time tag variant it selects.
     *
     * @param type       the command type.
     * @param timeTagged {@code true} if the text named the variant with time tag.
     */
    public record Resolved(Iec608705104DataType type, boolean timeTagged) {
    }

    /**
     * Resolves the type part of an address.
     *
     * @param text a short name ({@code FLOAT}) or an IEC mnemonic ({@code C_SE_NC_1},
     *             {@code C_SE_TC_1}), case-insensitive.
     * @return the matching type, or empty if there is none.
     */
    public static Optional<Resolved> fromText(String text) {
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (Iec608705104DataType type : values()) {
            if (type.name().equals(normalized) || type.mnemonic.equals(normalized)) {
                return Optional.of(new Resolved(type, false));
            }
            if (normalized.equals(type.timeTaggedMnemonic)) {
                return Optional.of(new Resolved(type, true));
            }
        }
        return Optional.empty();
    }

}
