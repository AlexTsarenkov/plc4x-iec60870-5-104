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
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.apache.plc4x.java.iec608705104.readwrite.BinaryStateInformation;
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfTransmission;
import org.apache.plc4x.java.iec608705104.readwrite.DoubleCommand;
import org.apache.plc4x.java.iec608705104.readwrite.FixedTestBitPatternTwoOctet;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObject;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_BITSTRING_32_BIT_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_DOUBLE_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_REGULATING_STEP_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_SET_POINT_COMMAND_NORMALISED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SCALED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime_SINGLE_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_BITSTRING_32_BIT_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_CLOCK_SYNCHRONISATION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_COUNTER_INTERROGATION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_DELAY_ACQUISITION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_DOUBLE_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_INTERROGATION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_PARAMETER_ACTIVATION;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_NORMALIZED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SCALED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_READ_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_REGULATING_STEP_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_RESET_PROCESS_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SINGLE_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_TEST_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG;
import org.apache.plc4x.java.iec608705104.readwrite.NormalizedValue;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfCommand;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfCounterInterrogationCommand;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfInterrogation;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfParameterActivation;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfParameterOfMeasuredValues;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfResetProcessCommand;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfSetPointCommand;
import org.apache.plc4x.java.iec608705104.readwrite.RegulatingStepCommand;
import org.apache.plc4x.java.iec608705104.readwrite.ScaledValue;
import org.apache.plc4x.java.iec608705104.readwrite.SevenOctetBinaryTime;
import org.apache.plc4x.java.iec608705104.readwrite.SingleCommand;
import org.apache.plc4x.java.iec608705104.readwrite.TwoOctetBinaryTime;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104DataType;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Builds the ASDUs the driver sends to the station.
 *
 * <p>Every method only builds an object; nothing is sent here. Values that a command
 * cannot carry are rejected with an {@link IllegalArgumentException} before anything is
 * sent, which the protocol reports as {@code INVALID_DATA}.
 *
 * <h2>Value conventions per command</h2>
 * See {@link Iec608705104DataType} and the table in README.md. In short: set points take a
 * number, single commands a boolean (or 0/1), double commands a boolean ({@code true} =
 * on) or the strings {@code ON}/{@code OFF}, step commands a signed number of steps,
 * bitstrings a number, system commands their qualifier.
 */
public final class Iec104Commands {

    /** Qualifier of interrogation for a station (general) interrogation. */
    static final short QOI_STATION_INTERROGATION = 20;

    /** Double command state "off" (DCS 1). */
    static final byte DCS_OFF = 1;
    /** Double command state "on" (DCS 2). */
    static final byte DCS_ON = 2;
    /** Regulating step command state "next step lower" (RCS 1). */
    static final byte RCS_LOWER = 1;
    /** Regulating step command state "next step higher" (RCS 2). */
    static final byte RCS_HIGHER = 2;

    /** Fixed test bit pattern of C_TS_NA_1, 0x55AA (octets AA 55 on the wire, little endian). */
    static final int TEST_BIT_PATTERN = 0x55AA;

    /** Kind of parameter if a P_ME tag gives none: threshold value. */
    static final int DEFAULT_KIND_OF_PARAMETER = 1;

    /** Most steps one write of a step command may ask for. */
    public static final int MAX_STEPS = 64;

    /**
     * Everything about a command that does not come from the tag or the value.
     *
     * @param originator          originator address of this client.
     * @param select              {@code true} for the select of a select-before-operate,
     *                            {@code false} for an execute.
     * @param time                time conversions of the connection.
     * @param testSequenceCounter the TSC of a C_TS_TA_1.
     */
    public record Context(int originator, boolean select, Iec104Time time, int testSequenceCounter) {
    }

    private Iec104Commands() {
    }

    /**
     * Decides which command writes the value of a tag.
     *
     * <ol>
     *   <li>A type in the address ({@code 1/300:SCALED}) always wins.</li>
     *   <li>Otherwise a boolean value is written as single command (C_SC_NA_1),</li>
     *   <li>and any other value as floating point set point (C_SE_NC_1).</li>
     * </ol>
     *
     * @param tag   the target address.
     * @param value the value to write.
     * @return the data type to write as.
     */
    public static Iec608705104DataType resolveDataType(Iec608705104Tag tag, PlcValue value) {
        return tag.getDataType().orElseGet(() ->
            value.getPlcValueType() == PlcValueType.BOOL ? Iec608705104DataType.BOOL : Iec608705104DataType.FLOAT);
    }

    /**
     * @param dataType a command type.
     * @return {@code true} if the command has a select/execute bit, i.e. can be sent as
     * select-before-operate.
     */
    public static boolean supportsSelect(Iec608705104DataType dataType) {
        return dataType.supports(Iec608705104DataType.Option.SELECT);
    }

    /**
     * The number of commands a step command write turns into.
     *
     * @param value the written value.
     * @return the signed number of steps, positive = higher, never 0.
     * @throws IllegalArgumentException for 0, more than {@link #MAX_STEPS} or a fraction.
     */
    public static int steps(PlcValue value) {
        long steps = integerOf(value, -MAX_STEPS, MAX_STEPS, "number of steps");
        if (steps == 0) {
            throw new IllegalArgumentException("number of steps must not be 0");
        }
        return (int) steps;
    }

    /**
     * Builds one command.
     *
     * <p>For a step command it is one step, in the direction of the sign of {@code value};
     * the protocol sends it {@link #steps} times.
     *
     * @param tag      the target address, must not contain wildcards.
     * @param dataType the command to send, see {@link #resolveDataType}.
     * @param value    the value.
     * @param context  originator, select bit, time.
     * @return the ASDU.
     * @throws IllegalArgumentException if the value cannot be represented by the command.
     */
    public static ASDU createWrite(Iec608705104Tag tag, Iec608705104DataType dataType, PlcValue value, Context context) {
        int ioa = tag.getObjectAddress();
        boolean timeTagged = tag.isTimeTagged();
        TypeIdentification type = dataType.getCommandType(timeTagged);
        SevenOctetBinaryTime timeTag = timeTagged ? context.time().toCp56(context.time().now()) : null;
        boolean select = context.select();
        CauseOfTransmission cause = CauseOfTransmission.ACTIVATION_ACT;
        InformationObject object;
        switch (dataType) {
            case BOOL: {
                SingleCommand sco = new SingleCommand(qualifierOfCommand(tag, select), booleanOf(value));
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_SINGLE_COMMAND(ioa, sco, timeTag)
                    : new InformationObjectWithoutTime_SINGLE_COMMAND(ioa, sco);
                break;
            }
            case DOUBLE: {
                DoubleCommand dco = new DoubleCommand(qualifierOfCommand(tag, select), doubleCommandStateOf(value));
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_DOUBLE_COMMAND(ioa, dco, timeTag)
                    : new InformationObjectWithoutTime_DOUBLE_COMMAND(ioa, dco);
                break;
            }
            case STEP: {
                byte direction = steps(value) > 0 ? RCS_HIGHER : RCS_LOWER;
                RegulatingStepCommand rco = new RegulatingStepCommand(qualifierOfCommand(tag, select), direction);
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_REGULATING_STEP_COMMAND(ioa, rco, timeTag)
                    : new InformationObjectWithoutTime_REGULATING_STEP_COMMAND(ioa, rco);
                break;
            }
            case NORMALIZED: {
                NormalizedValue nva = new NormalizedValue(ValueConversions.normalizedToRaw(numberOf(value)));
                QualifierOfSetPointCommand qos = qualifierOfSetPoint(tag, select);
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_SET_POINT_COMMAND_NORMALISED_VALUE(ioa, nva, qos, timeTag)
                    : new InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE(ioa, nva, qos);
                break;
            }
            case SCALED: {
                ScaledValue sva = new ScaledValue(ValueConversions.scaledToRaw(numberOf(value)));
                QualifierOfSetPointCommand qos = qualifierOfSetPoint(tag, select);
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SCALED_VALUE(ioa, sva, qos, timeTag)
                    : new InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE(ioa, sva, qos);
                break;
            }
            case FLOAT: {
                float number = floatOf(value);
                QualifierOfSetPointCommand qos = qualifierOfSetPoint(tag, select);
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER(ioa, number, qos, timeTag)
                    : new InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER(ioa, number, qos);
                break;
            }
            case BITSTRING: {
                BinaryStateInformation bsi = new BinaryStateInformation(
                    integerOf(value, Integer.MIN_VALUE, 0xFFFFFFFFL, "bitstring") & 0xFFFFFFFFL);
                object = timeTagged
                    ? new InformationObjectWithSevenByteTime_BITSTRING_32_BIT_COMMAND(ioa, bsi, timeTag)
                    : new InformationObjectWithoutTime_BITSTRING_32_BIT_COMMAND(ioa, bsi);
                break;
            }
            case PARAM_NORMALIZED:
                object = new InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_NORMALIZED_VALUE(ioa,
                    new NormalizedValue(ValueConversions.normalizedToRaw(numberOf(value))), qualifierOfParameter(tag));
                break;
            case PARAM_SCALED:
                object = new InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SCALED_VALUE(ioa,
                    new ScaledValue(ValueConversions.scaledToRaw(numberOf(value))), qualifierOfParameter(tag));
                break;
            case PARAM_FLOAT:
                object = new InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER(ioa,
                    floatOf(value), qualifierOfParameter(tag));
                break;
            case PARAM_ACTIVATION:
                object = new InformationObjectWithoutTime_PARAMETER_ACTIVATION(ioa,
                    new QualifierOfParameterActivation((short) integerOf(value, 0, 255, "qualifier of parameter activation")));
                if (tag.isDeactivate()) {
                    cause = CauseOfTransmission.DEACTIVATION_DEACT;
                }
                break;
            case INTERROGATION:
                object = new InformationObjectWithoutTime_INTERROGATION_COMMAND(ioa,
                    new QualifierOfInterrogation((short) integerOf(value, 0, 255, "qualifier of interrogation")));
                break;
            case COUNTER_INTERROGATION:
                object = new InformationObjectWithoutTime_COUNTER_INTERROGATION_COMMAND(ioa,
                    new QualifierOfCounterInterrogationCommand(tag.getFreeze().orElse(0).byteValue(),
                        (byte) integerOf(value, 0, 63, "request qualifier of counter interrogation")));
                break;
            case CLOCK_SYNC: {
                Instant time = value.isDateTime()
                    ? value.getDateTime().atZone(context.time().getZone()).toInstant()
                    : context.time().now();
                object = new InformationObjectWithoutTime_CLOCK_SYNCHRONISATION_COMMAND(ioa, context.time().toCp56(time));
                break;
            }
            case TEST:
                object = timeTagged
                    ? new InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG(ioa,
                        context.testSequenceCounter() & 0xFFFF, context.time().toCp56(context.time().now()))
                    : new InformationObjectWithoutTime_TEST_COMMAND(ioa, new FixedTestBitPatternTwoOctet(TEST_BIT_PATTERN));
                break;
            case RESET_PROCESS:
                object = new InformationObjectWithoutTime_RESET_PROCESS_COMMAND(ioa,
                    new QualifierOfResetProcessCommand((short) integerOf(value, 0, 255, "qualifier of reset process")));
                break;
            case DELAY_ACQUISITION:
                object = new InformationObjectWithoutTime_DELAY_ACQUISITION_COMMAND(ioa,
                    new TwoOctetBinaryTime((int) integerOf(value, 0, 59999, "delay in milliseconds")));
                break;
            default:
                // Unreachable as long as every constant has a case above. A new constant in
                // Iec608705104DataType without one ends up here at runtime.
                throw new IllegalArgumentException("No encoding implemented for " + dataType);
        }
        return asdu(type, cause, context.originator(), tag.getAsduAddress(), object);
    }

    /**
     * Builds a station (general) interrogation, C_IC_NA_1 with QOI 20.
     *
     * @param asduAddress the station to interrogate, 65535 for all.
     * @param originator  originator address of this client.
     * @return the ASDU, cause of transmission "activation".
     */
    public static ASDU createGeneralInterrogation(int asduAddress, int originator) {
        return asdu(TypeIdentification.INTERROGATION_COMMAND, CauseOfTransmission.ACTIVATION_ACT, originator, asduAddress,
            new InformationObjectWithoutTime_INTERROGATION_COMMAND(0, new QualifierOfInterrogation(QOI_STATION_INTERROGATION)));
    }

    /**
     * Builds a read command, C_RD_NA_1. The station answers with the current value and cause
     * of transmission "request" (5).
     *
     * @param tag        the object to read, must not contain wildcards.
     * @param originator originator address of this client.
     * @return the ASDU, cause of transmission "request".
     */
    public static ASDU createRead(Iec608705104Tag tag, int originator) {
        return asdu(TypeIdentification.READ_COMMAND, CauseOfTransmission.INTERROGATION_OR_INTERROGATED_REQ, originator,
            tag.getAsduAddress(), new InformationObjectWithoutTime_READ_COMMAND(tag.getObjectAddress()));
    }

    private static ASDU asdu(TypeIdentification type, CauseOfTransmission cause, int originator, int asduAddress,
                             InformationObject object) {
        return new ASDU(type, false, false, false, cause, (short) originator, asduAddress, List.of(object));
    }

    private static QualifierOfCommand qualifierOfCommand(Iec608705104Tag tag, boolean select) {
        return new QualifierOfCommand(select, tag.getQualifierOfCommand().orElse(0).byteValue());
    }

    private static QualifierOfSetPointCommand qualifierOfSetPoint(Iec608705104Tag tag, boolean select) {
        return new QualifierOfSetPointCommand(select, tag.getQualifierOfSetPoint().orElse(0).byteValue());
    }

    private static QualifierOfParameterOfMeasuredValues qualifierOfParameter(Iec608705104Tag tag) {
        return new QualifierOfParameterOfMeasuredValues(false, false,
            tag.getKindOfParameter().orElse(DEFAULT_KIND_OF_PARAMETER).byteValue());
    }

    private static double numberOf(PlcValue value) {
        if (value.getPlcValueType() == PlcValueType.BOOL) {
            return value.getBoolean() ? 1.0 : 0.0;
        }
        try {
            return value.getDouble();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a number: " + value, e);
        }
    }

    private static float floatOf(PlcValue value) {
        double number = numberOf(value);
        if (Double.isNaN(number) || Double.isInfinite(number) || Math.abs(number) > Float.MAX_VALUE) {
            throw new IllegalArgumentException("value does not fit into a 32 bit float: " + number);
        }
        return (float) number;
    }

    private static long integerOf(PlcValue value, long min, long max, String what) {
        double number = numberOf(value);
        if (Double.isNaN(number) || number != Math.rint(number) || number < min || number > max) {
            throw new IllegalArgumentException(what + " must be a whole number in " + min + ".." + max + ", was " + value);
        }
        return (long) number;
    }

    private static boolean booleanOf(PlcValue value) {
        if (value.getPlcValueType() == PlcValueType.BOOL) {
            return value.getBoolean();
        }
        double number = numberOf(value);
        if (number == 0.0) {
            return false;
        }
        if (number == 1.0) {
            return true;
        }
        throw new IllegalArgumentException("a single command takes a boolean, 0 or 1, was " + value);
    }

    /**
     * Numbers are deliberately not accepted for a double command: 1 means "off" on the wire
     * (DCS 1) but "on" everywhere else, and a double command usually switches a breaker.
     */
    private static byte doubleCommandStateOf(PlcValue value) {
        if (value.getPlcValueType() == PlcValueType.BOOL) {
            return value.getBoolean() ? DCS_ON : DCS_OFF;
        }
        if (value.isString()) {
            String text = value.getString().trim().toUpperCase(Locale.ROOT);
            if ("ON".equals(text)) {
                return DCS_ON;
            }
            if ("OFF".equals(text)) {
                return DCS_OFF;
            }
        }
        throw new IllegalArgumentException("a double command takes a boolean (true = on) or \"ON\"/\"OFF\", was " + value);
    }

}
