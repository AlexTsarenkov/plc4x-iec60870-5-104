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
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfTransmission;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObject;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_INTERROGATION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_READ_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SINGLE_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.NormalizedValue;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfCommand;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfInterrogation;
import org.apache.plc4x.java.iec608705104.readwrite.QualifierOfSetPointCommand;
import org.apache.plc4x.java.iec608705104.readwrite.ScaledValue;
import org.apache.plc4x.java.iec608705104.readwrite.SingleCommand;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104DataType;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;

import java.util.List;

/**
 * Builds the ASDUs the driver sends to the station.
 *
 * <p>All commands are sent as <b>direct execute</b>: the select bit (S/E) is 0 and the
 * qualifier is 0 ("no additional definition"). Select-before-operate is not implemented,
 * see MAINTENANCE.md for how to add it.
 *
 * <p>Every method only builds an object; nothing is sent here.
 */
public final class Iec104Commands {

    /** Qualifier of interrogation for a station (general) interrogation. */
    static final short QOI_STATION_INTERROGATION = 20;

    private Iec104Commands() {
    }

    /**
     * Decides which command writes the value of a tag.
     *
     * <ol>
     *   <li>A type suffix on the address ({@code 1/300:SCALED}) always wins.</li>
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
     * Builds the activation of a command that writes a value.
     *
     * @param tag        the target address, must not contain wildcards.
     * @param dataType   the command to send, see {@link #resolveDataType}.
     * @param value      the value.
     * @param originator originator address of this client.
     * @return the ASDU, cause of transmission "activation".
     * @throws IllegalArgumentException if the value cannot be represented by the command.
     */
    public static ASDU createWrite(Iec608705104Tag tag, Iec608705104DataType dataType, PlcValue value, int originator) {
        int ioa = tag.getObjectAddress();
        InformationObject object;
        switch (dataType) {
            case FLOAT: {
                double number = numberOf(value);
                if (Double.isNaN(number) || Double.isInfinite(number) || Math.abs(number) > Float.MAX_VALUE) {
                    throw new IllegalArgumentException("value does not fit into a 32 bit float: " + number);
                }
                object = new InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER(
                    ioa, (float) number, directExecuteSetPoint());
                break;
            }
            case SCALED:
                object = new InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE(
                    ioa, new ScaledValue(ValueConversions.scaledToRaw(numberOf(value))), directExecuteSetPoint());
                break;
            case NORMALIZED:
                object = new InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE(
                    ioa, new NormalizedValue(ValueConversions.normalizedToRaw(numberOf(value))), directExecuteSetPoint());
                break;
            case BOOL:
                object = new InformationObjectWithoutTime_SINGLE_COMMAND(
                    ioa, new SingleCommand(new QualifierOfCommand(false, (byte) 0), booleanOf(value)));
                break;
            default:
                // Unreachable as long as every constant has a case above. A new constant in
                // Iec608705104DataType without one ends up here at runtime.
                throw new IllegalArgumentException("No encoding implemented for " + dataType);
        }
        return asdu(dataType.getCommandType(), CauseOfTransmission.ACTIVATION_ACT, originator, tag.getAsduAddress(), object);
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

    private static QualifierOfSetPointCommand directExecuteSetPoint() {
        return new QualifierOfSetPointCommand(false, (byte) 0);
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

}
