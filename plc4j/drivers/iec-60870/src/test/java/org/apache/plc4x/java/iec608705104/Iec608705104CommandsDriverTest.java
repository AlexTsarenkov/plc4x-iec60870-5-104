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
package org.apache.plc4x.java.iec608705104;

import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriverManager;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.iec608705104.Iec104TestStation.CommandBehaviour;
import org.apache.plc4x.java.iec608705104.Iec104TestStation.PointType;
import org.apache.plc4x.java.iec608705104.Iec104TestStation.ReceivedCommand;
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfTransmission;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_COUNTER_INTERROGATION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_INTERROGATION_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_RESET_PROCESS_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.iec608705104.readwrite.protocol.Iec608705104Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the commands added on top of set points and single commands:
 * double, step, bitstring, time-tagged, parameters, system commands, select-before-operate,
 * waiting for ACT_TERM, and the time zone of time tags. Against {@link Iec104TestStation}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Iec608705104CommandsDriverTest {

    private static final long TIMEOUT_SECONDS = 5;

    private Iec104TestStation station;
    private PlcConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        station = new Iec104TestStation()
            .point(100, PointType.FLOAT, 12.5f)
            .point(130, PointType.FLOAT_WITH_TIME, 7.5f)
            .point(200, PointType.SINGLE, false)
            .point(201, PointType.SINGLE, false)
            .point(210, PointType.DOUBLE, 1)
            .point(220, PointType.STEP, 0)
            .point(221, PointType.STEP, 0)
            .point(222, PointType.STEP, 0)
            .point(223, PointType.STEP, -3)
            .point(230, PointType.BITSTRING, 0L)
            .point(240, PointType.COUNTER, 123456L)
            .command(300, CommandBehaviour.ACCEPT, 100)
            .command(400, CommandBehaviour.ACCEPT, 200)
            .command(410, CommandBehaviour.ACCEPT, 201)
            .command(500, CommandBehaviour.ACCEPT, 210)
            .command(600, CommandBehaviour.ACCEPT, 220)
            .command(610, CommandBehaviour.ACCEPT, 221)
            .command(620, CommandBehaviour.ACCEPT, 222)
            .command(630, CommandBehaviour.ACCEPT, 223)
            .command(700, CommandBehaviour.ACCEPT, 230)
            .command(800, CommandBehaviour.ACCEPT, 0);
        station.commandAt(410).requireSelect();
        station.commandAt(610).terminationDelay(300);
        station.commandAt(620).withoutTermination();
        station.commandAt(630).acceptOnly(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception e) {
            // Some tests leave the connection already closed by the driver.
        } finally {
            station.close();
        }
    }

    private PlcConnection connect(String options) throws Exception {
        connection = PlcDriverManager.getDefault().getConnectionManager().getConnection(station.connectionString(options));
        assertTrue(connection.isConnected());
        return connection;
    }

    // -- process commands ------------------------------------------------------------------

    @Test
    void doubleCommand() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/500:C_DC_NA_1", true));
        assertEquals(2, station.valueOf(210), "DCS 2 = on");
        assertEquals(PlcResponseCode.OK, write("1/500:DOUBLE", "OFF"));
        assertEquals(1, station.valueOf(210));
        assertEquals(PlcResponseCode.INVALID_DATA, write("1/500:DOUBLE", 1), "numbers are ambiguous");
    }

    @Test
    void readsDoublePointAsCode() throws Exception {
        // ACT_TERM comes after the new value (COT 11), so the cache has it when the write completes.
        connect("wait-for-termination=true");
        write("1/500:DOUBLE", true);
        assertEquals(2, read("1/210").getInt(), "0 intermediate, 1 off, 2 on, 3 indeterminate");
    }

    @Test
    void stepCommandSendsOneCommandPerStep() throws Exception {
        // ACT_TERM comes after the new value (COT 11), so the cache has it when the write completes.
        connect("wait-for-termination=true");
        assertEquals(PlcResponseCode.OK, write("1/600:C_RC_NA_1", 3));
        assertEquals(3, station.valueOf(220));
        assertEquals(PlcResponseCode.OK, write("1/600:STEP", -5));
        assertEquals(-2, station.valueOf(220));
        assertEquals(8, station.getReceivedCommands(TypeIdentification.REGULATING_STEP_COMMAND).size());
        assertEquals(-2, read("1/220").getInt(), "step position is signed");
    }

    @Test
    void stepCommandStopsAtTheFirstRefusedStep() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.REMOTE_ERROR, write("1/630:STEP", 5));
        assertEquals(-1, station.valueOf(223), "two of five steps executed (-3 + 2)");
        assertEquals(3, station.getReceivedCommands(TypeIdentification.REGULATING_STEP_COMMAND).size(),
            "the refused third step is the last one sent");
    }

    @Test
    void stepCommandRejectsZeroAndTooManySteps() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.INVALID_DATA, write("1/600:STEP", 0));
        assertEquals(PlcResponseCode.INVALID_DATA, write("1/600:STEP", 65));
        assertTrue(station.getReceivedCommands(TypeIdentification.REGULATING_STEP_COMMAND).isEmpty());
    }

    @Test
    void bitstringCommand() throws Exception {
        // ACT_TERM comes after the new value (COT 11), so the cache has it when the write completes.
        connect("wait-for-termination=true");
        assertEquals(PlcResponseCode.OK, write("1/700:C_BO_NA_1", 0xA5A5A5A5L));
        assertEquals(0xA5A5A5A5L, station.valueOf(230));
        assertEquals(0xA5A5A5A5L, read("1/230").getLong());
    }

    @Test
    void timeTaggedCommandCarriesTheCurrentTime() throws Exception {
        connect(null);
        Instant before = Instant.now().minusSeconds(1);
        assertEquals(PlcResponseCode.OK, write("1/300:C_SE_TC_1", 1.5f));
        Instant after = Instant.now().plusSeconds(1);
        ReceivedCommand command = single(TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER_COMMAND_WITH_TIME_TAG_CP56TIME2A);
        assertTrue(command.timeTag().isAfter(before) && command.timeTag().isBefore(after), "time tag " + command.timeTag());
        assertEquals(1.5f, station.valueOf(100));
        assertEquals(PlcResponseCode.OK, write("1/400:BOOL,time", true));
        assertEquals(1, station.getReceivedCommands(TypeIdentification.SINGLE_COMMAND_WITH_TIME_TAG_CP56TIME2A).size());
    }

    @Test
    void timeTagUsesTheConfiguredZone() throws Exception {
        station.timeZone(ZoneId.of("Europe/Moscow"));
        connect("time-zone=Europe/Moscow");
        write("1/300:C_SE_TC_1", 1.5f);
        ReceivedCommand command = single(TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER_COMMAND_WITH_TIME_TAG_CP56TIME2A);
        assertTrue(Duration.between(command.timeTag(), Instant.now()).abs().getSeconds() < 5,
            "same zone on both sides gives the right instant: " + command.timeTag());
    }

    // -- select-before-operate and ACT_TERM ----------------------------------------------------

    @Test
    void selectBeforeOperate() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.REMOTE_ERROR, write("1/410", true), "station requires a select first");
        assertEquals(PlcResponseCode.OK, write("1/410:BOOL,sbo", true));
        assertEquals(true, station.valueOf(201));
        List<ReceivedCommand> commands = station.getReceivedCommands(TypeIdentification.SINGLE_COMMAND);
        assertEquals(3, commands.size());
        assertFalse(commands.get(0).select());
        assertTrue(commands.get(1).select(), "select ...");
        assertFalse(commands.get(2).select(), "... then execute");
    }

    @Test
    void selectBeforeOperateAsConnectionDefault() throws Exception {
        connect("select-before-operate=true");
        assertEquals(PlcResponseCode.OK, write("1/410:BOOL", true));
        assertEquals(PlcResponseCode.REMOTE_ERROR, write("1/410:BOOL,direct", false), "tag option wins");
        assertEquals(PlcResponseCode.OK, write("1/700:BITSTRING", 1), "commands without select bit are sent directly");
    }

    @Test
    void refusedSelectIsNotFollowedByAnExecute() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.REMOTE_ERROR, write("1/400:BOOL,sbo", true), "station does not do select here");
        assertEquals(1, station.getReceivedCommands(TypeIdentification.SINGLE_COMMAND).size());
        assertEquals(false, station.valueOf(200));
    }

    @Test
    void waitsForTermination() throws Exception {
        connect("wait-for-termination=true");
        long start = System.nanoTime();
        assertEquals(PlcResponseCode.OK, write("1/610:STEP", 2));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(millis >= 550, "two steps, each waiting ~300 ms for ACT_TERM, took " + millis + " ms");
        assertEquals(2, station.valueOf(221));
    }

    @Test
    void missingTerminationTimesOut() throws Exception {
        connect("wait-for-termination=true&termination-timeout-ms=500");
        assertEquals(PlcResponseCode.REMOTE_ERROR, write("1/620:STEP", 1));
        assertEquals(1, station.valueOf(222), "executed, but never reported as finished");
    }

    @Test
    void withoutWaitingTerminationDoesNotMatter() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/620:STEP", 1));
    }

    // -- parameters ------------------------------------------------------------------------------

    @Test
    void parameterOfMeasuredValues() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/800:P_ME_NC_1,kpa=high", 99.5f));
        InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER parameter =
            (InformationObjectWithoutTime_PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER)
                single(TypeIdentification.PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER).object();
        assertEquals(99.5f, parameter.getValue());
        assertEquals(4, parameter.getQpm().getKindOfParameter());
    }

    @Test
    void parameterActivationAndDeactivation() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/800:P_AC_NA_1", 3));
        assertEquals(PlcResponseCode.OK, write("1/800:P_AC_NA_1,deact", 3), "answered with DEACT_CON");
        List<ReceivedCommand> commands = station.getReceivedCommands(TypeIdentification.PARAMETER_ACTIVATION);
        assertEquals(CauseOfTransmission.ACTIVATION_ACT, commands.get(0).asdu().getCauseOfTransmission());
        assertEquals(CauseOfTransmission.DEACTIVATION_DEACT, commands.get(1).asdu().getCauseOfTransmission());
    }

    // -- system commands ---------------------------------------------------------------------------

    @Test
    void clockSynchronisation() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/0:C_CS_NA_1", 0));
        ReceivedCommand now = single(TypeIdentification.CLOCK_SYNCHRONISATION_COMMAND);
        assertTrue(Duration.between(now.timeTag(), Instant.now()).abs().getSeconds() < 5, "now: " + now.timeTag());

        assertEquals(PlcResponseCode.OK, write("1/0:C_CS_NA_1", LocalDateTime.parse("2027-03-04T05:06:07")));
        ReceivedCommand given = station.getReceivedCommands(TypeIdentification.CLOCK_SYNCHRONISATION_COMMAND).get(1);
        assertEquals(LocalDateTime.parse("2027-03-04T05:06:07").toInstant(ZoneOffset.UTC), given.timeTag());
    }

    @Test
    void testCommandsCountTheirSequence() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/0:C_TS_TA_1", 0));
        assertEquals(PlcResponseCode.OK, write("1/0:C_TS_TA_1", 0));
        assertEquals(PlcResponseCode.OK, write("1/0:C_TS_NA_1", 0));
        List<ReceivedCommand> tests = station.getReceivedCommands(TypeIdentification.TEST_COMMAND_WITH_TIME_TAG_CP56TIME2A);
        int first = ((InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG) tests.get(0).object()).getTestSequenceCounter();
        int second = ((InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG) tests.get(1).object()).getTestSequenceCounter();
        assertTrue(second > first, "test sequence counter increases: " + first + ", " + second);
    }

    @Test
    void resetProcessAndDelayAcquisition() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.OK, write("1/0:C_RP_NA_1", 1));
        assertEquals(1, ((InformationObjectWithoutTime_RESET_PROCESS_COMMAND)
            single(TypeIdentification.RESET_PROCESS_COMMAND).object()).getQrp().getQualifier());
        assertEquals(PlcResponseCode.OK, write("1/0:C_CD_NA_1", 250));
    }

    @Test
    void counterInterrogationFillsTheCache() throws Exception {
        // Waiting for ACT_TERM means the counter values (sent before it) are in the cache
        // when the write completes.
        connect("wait-for-termination=true");
        assertEquals(PlcResponseCode.NOT_FOUND, readCode("1/240"), "counters are not part of the general interrogation");
        assertEquals(PlcResponseCode.OK, write("1/0:C_CI_NA_1,frz=read", 5));
        assertEquals(5, ((InformationObjectWithoutTime_COUNTER_INTERROGATION_COMMAND)
            single(TypeIdentification.COUNTER_INTERROGATION_COMMAND).object()).getQcc().getRequest());
        PlcValue counter = read("1/240");
        assertEquals(123456L, counter.getLong());
        assertEquals(37, counter.getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt(),
            "interrogated by general counter request");
    }

    @Test
    void groupAndBroadcastInterrogation() throws Exception {
        connect("general-interrogation=false&wait-for-termination=true");
        assertEquals(PlcResponseCode.OK, write("1/0:C_IC_NA_1", 21));
        assertEquals(21, ((InformationObjectWithoutTime_INTERROGATION_COMMAND)
            single(TypeIdentification.INTERROGATION_COMMAND).object()).getQoi().getQualifierOfCommand());
        assertEquals(PlcResponseCode.OK, write("65535/0:INTERROGATION", 20),
            "the station answers with its own address, matched to the broadcast");
        assertEquals(PlcResponseCode.OK, readCode("1/100"), "and the cache has the values");
    }

    @Test
    void commandToUnknownStationIsNotFound() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.NOT_FOUND, write("7/300", 1.0f));
    }

    // -- time tags of monitoring data ----------------------------------------------------------------

    @Test
    void timeTagOfMonitoringDataInTheConfiguredZone() throws Exception {
        Instant event = Instant.parse("2026-05-06T07:08:09.123Z");
        station.timeZone(ZoneId.of("Europe/Moscow")).timeTag(event);
        connect("time-zone=Europe/Moscow");
        assertEquals(event, timestampOf(read("1/130")));
    }

    @Test
    void wrongZoneShiftsTheTimeTag() throws Exception {
        Instant event = Instant.parse("2026-05-06T07:08:09.123Z");
        station.timeZone(ZoneId.of("Europe/Moscow")).timeTag(event);
        connect(null);
        assertEquals(event.plus(Duration.ofHours(3)), timestampOf(read("1/130")),
            "station in Moscow time, driver assumes UTC: three hours late");
    }

    // -- helpers -------------------------------------------------------------------------------------

    private PlcResponseCode write(String address, Object value) throws Exception {
        PlcWriteResponse response = connection.writeRequestBuilder()
            .addTagAddress("tag", address, value)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return response.getResponseCode("tag");
    }

    private PlcValue read(String address) throws Exception {
        PlcReadResponse response = connection.readRequestBuilder().addTagAddress("tag", address)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("tag"), "read " + address);
        return response.getPlcValue("tag");
    }

    private PlcResponseCode readCode(String address) throws Exception {
        return connection.readRequestBuilder().addTagAddress("tag", address)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResponseCode("tag");
    }

    private ReceivedCommand single(TypeIdentification type) {
        List<ReceivedCommand> commands = station.getReceivedCommands(type);
        assertEquals(1, commands.size(), "commands of type " + type);
        return commands.get(0);
    }

    private static Instant timestampOf(PlcValue value) {
        return value.getMetaData(Iec608705104Protocol.METADATA_TIMESTAMP).getDateTime().toInstant(ZoneOffset.UTC);
    }

}
