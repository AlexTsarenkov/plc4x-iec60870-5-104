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
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.iec608705104.Iec104TestStation.CommandBehaviour;
import org.apache.plc4x.java.iec608705104.Iec104TestStation.PointType;
import org.apache.plc4x.java.iec608705104.readwrite.APDUIFormat;
import org.apache.plc4x.java.iec608705104.readwrite.APDUSFormat;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameActivation;
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfTransmission;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.iec608705104.readwrite.protocol.Iec608705104Protocol;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests through the public PLC4X API against {@link Iec104TestStation}.
 * They need no Docker and run with a plain {@code mvn test}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Iec608705104DriverTest {

    private static final long TIMEOUT_SECONDS = 5;

    private Iec104TestStation station;
    private PlcConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        station = new Iec104TestStation()
            .point(100, PointType.FLOAT, 12.5f)
            .point(101, PointType.FLOAT, 0.0f)
            .point(110, PointType.SCALED, (short) -1234)
            .point(120, PointType.NORMALIZED, -0.5)
            .point(200, PointType.SINGLE, true)
            .command(300, CommandBehaviour.ACCEPT, 100)
            .command(310, CommandBehaviour.ACCEPT, 110)
            .command(320, CommandBehaviour.ACCEPT, 120)
            .command(398, CommandBehaviour.IGNORE, 0)
            .command(399, CommandBehaviour.REJECT, 0)
            .command(400, CommandBehaviour.ACCEPT, 200);
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

    // -- connection --------------------------------------------------------------------------

    @Test
    void reportsCapabilities() throws Exception {
        connect(null);
        assertTrue(connection.getMetadata().isReadSupported());
        assertTrue(connection.getMetadata().isWriteSupported());
        assertTrue(connection.getMetadata().isSubscribeSupported());
    }

    @Test
    void sendsGeneralInterrogationAfterConnecting() throws Exception {
        connect(null);
        ASDU interrogation = awaitAsdu(TypeIdentification.INTERROGATION_COMMAND);
        assertEquals(CauseOfTransmission.ACTIVATION_ACT, interrogation.getCauseOfTransmission());
        assertEquals(65535, interrogation.getAsduAddressField(), "broadcast by default");
    }

    @Test
    void interrogationAddressIsConfigurable() throws Exception {
        connect("interrogation-asdu-address=1");
        assertEquals(1, awaitAsdu(TypeIdentification.INTERROGATION_COMMAND).getAsduAddressField());
    }

    @Test
    void noGeneralInterrogationWhenDisabled() throws Exception {
        connect("general-interrogation=false");
        Thread.sleep(500);
        assertTrue(station.getReceivedAsdus().isEmpty());
    }

    @Test
    void interrogatesAgainAfterEndOfInitialisation() throws Exception {
        connect(null);
        // A read from the cache waits for the first interrogation to finish (ACT_TERM).
        connection.readRequestBuilder().addTagAddress("p", "1/100").build().execute()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        station.sendEndOfInitialisation();
        awaitCondition(() -> countAsdus(TypeIdentification.INTERROGATION_COMMAND) == 2, "second interrogation");
    }

    @Test
    void noSecondInterrogationWhileOneIsRunning() throws Exception {
        // As FreyrSCADA does: M_EI_NA_1 right after connecting, before the GI is answered.
        station.answerInterrogation(false);
        connect(null);
        awaitAsdu(TypeIdentification.INTERROGATION_COMMAND);
        station.sendEndOfInitialisation();
        Thread.sleep(500);
        assertEquals(1, countAsdus(TypeIdentification.INTERROGATION_COMMAND));
    }

    @Test
    void invalidConfigurationFailsTheConnection() {
        Exception e = null;
        try {
            connect("t2-ms=20000");
        } catch (Exception thrown) {
            e = thrown;
        }
        assertNotNull(e, "t2 >= t1 must be rejected");
        connection = null;
    }

    // -- read --------------------------------------------------------------------------------

    @Test
    void readsFromCacheAfterInterrogation() throws Exception {
        connect(null);
        PlcReadResponse response = connection.readRequestBuilder()
            .addTagAddress("float", "1/100")
            .addTagAddress("scaled", "1/110")
            .addTagAddress("normalized", "1/120")
            .addTagAddress("single", "1/200")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("float"));
        assertEquals(12.5, response.getPlcValue("float").getDouble(), 1e-6);
        assertEquals(-1234, response.getPlcValue("scaled").getInt());
        assertEquals(-0.5, response.getPlcValue("normalized").getDouble(), 1e-6);
        assertTrue(response.getPlcValue("single").getBoolean());

        PlcValue value = response.getPlcValue("float");
        assertEquals(20, value.getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt(),
            "answered by general interrogation");
        assertEquals(13, value.getMetaData(Iec608705104Protocol.METADATA_TYPE_IDENTIFICATION).getInt());
        assertFalse(value.getMetaData("invalid").getBoolean());
        assertNotNull(value.getMetaData(Iec608705104Protocol.METADATA_TIMESTAMP));
    }

    @Test
    void readOfUnknownObjectIsNotFound() throws Exception {
        connect(null);
        PlcReadResponse response = connection.readRequestBuilder()
            .addTagAddress("missing", "1/999")
            .addTagAddress("otherStation", "2/100")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.NOT_FOUND, response.getResponseCode("missing"));
        assertEquals(PlcResponseCode.NOT_FOUND, response.getResponseCode("otherStation"));
    }

    @Test
    void readWithWildcardIsInvalidAddress() throws Exception {
        connect(null);
        PlcReadResponse response = connection.readRequestBuilder()
            .addTagAddress("all", "1/*")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.INVALID_ADDRESS, response.getResponseCode("all"));
    }

    @Test
    void cacheReadWaitsForTheInterrogationToFinish() throws Exception {
        station.answerInterrogation(false);
        connect("interrogation-timeout-ms=1000");
        long start = System.nanoTime();
        PlcReadResponse response = connection.readRequestBuilder()
            .addTagAddress("float", "1/100")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waitedMillis >= 500, "waited for the interrogation timeout, only " + waitedMillis + " ms");
        assertEquals(PlcResponseCode.NOT_FOUND, response.getResponseCode("float"), "station never sent the value");
    }

    @Test
    void readsWithReadCommand() throws Exception {
        connect("read-mode=command&general-interrogation=false");
        PlcReadResponse response = connection.readRequestBuilder()
            .addTagAddress("float", "1/100")
            .addTagAddress("missing", "1/999")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("float"));
        assertEquals(12.5, response.getPlcValue("float").getDouble(), 1e-6);
        assertEquals(5, response.getPlcValue("float").getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt());
        assertEquals(PlcResponseCode.NOT_FOUND, response.getResponseCode("missing"));

        ASDU read = awaitAsdu(TypeIdentification.READ_COMMAND);
        assertEquals(CauseOfTransmission.INTERROGATION_OR_INTERROGATED_REQ, read.getCauseOfTransmission());
    }

    @Test
    void readCommandWithoutAnswerTimesOut() throws Exception {
        station.answerReadCommands(false);
        connect("read-mode=command&general-interrogation=false&request-timeout=500");
        PlcReadResponse response = connection.readRequestBuilder()
            .addTagAddress("float", "1/100")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.REMOTE_ERROR, response.getResponseCode("float"));
    }

    // -- write -------------------------------------------------------------------------------

    @Test
    void writesFloatSetPoint() throws Exception {
        connect(null);
        PlcWriteResponse response = connection.writeRequestBuilder()
            .addTagAddress("setpoint", "1/300", 42.5f)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("setpoint"));

        ASDU command = awaitAsdu(TypeIdentification.SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER);
        assertEquals(CauseOfTransmission.ACTIVATION_ACT, command.getCauseOfTransmission());
        assertEquals(1, command.getAsduAddressField());
        InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER object =
            (InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) command.getInformationObjects().get(0);
        assertEquals(300, object.getAddress());
        assertEquals(42.5f, object.getValue());
        assertFalse(object.getQos().getSelect(), "direct execute");

        // The station mirrors the set point to IOA 100 (COT 11); the cache follows.
        awaitCondition(() -> readDouble("1/100") == 42.5, "cache updated by return information");
    }

    @Test
    void writesAllSupportedTypes() throws Exception {
        connect(null);
        PlcWriteResponse response = connection.writeRequestBuilder()
            .addTagAddress("scaled", "1/310:SCALED", 777)
            .addTagAddress("normalized", "1/320:NORMALIZED", 0.25)
            .addTagAddress("bool", "1/400", false)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("scaled"));
        assertEquals(PlcResponseCode.OK, response.getResponseCode("normalized"));
        assertEquals(PlcResponseCode.OK, response.getResponseCode("bool"));
        assertEquals((short) 777, station.valueOf(110));
        assertEquals(0.25, (Double) station.valueOf(120), 1e-6);
        assertEquals(false, station.valueOf(200));
    }

    @Test
    void rejectedCommandIsRemoteError() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.REMOTE_ERROR, writeSingle("1/399", 1.0f));
    }

    @Test
    void commandToUnknownObjectIsNotFound() throws Exception {
        connect(null);
        assertEquals(PlcResponseCode.NOT_FOUND, writeSingle("1/12345", 1.0f));
    }

    @Test
    void unansweredCommandTimesOut() throws Exception {
        connect("request-timeout=500");
        assertEquals(PlcResponseCode.REMOTE_ERROR, writeSingle("1/398", 1.0f));
    }

    @Test
    void invalidWritesAreRejectedLocally() throws Exception {
        connect(null);
        PlcWriteResponse response = connection.writeRequestBuilder()
            .addTagAddress("wildcard", "1/*", 1.0f)
            .addTagAddress("outOfRange", "1/320:NORMALIZED", 2.0)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.INVALID_ADDRESS, response.getResponseCode("wildcard"));
        assertEquals(PlcResponseCode.INVALID_DATA, response.getResponseCode("outOfRange"));
        assertEquals(0, countAsdus(TypeIdentification.SET_POINT_COMMAND_NORMALISED_VALUE), "nothing sent");
    }

    // -- subscriptions -----------------------------------------------------------------------

    @Test
    void subscriptionsAreFilteredAndNamed() throws Exception {
        connect("general-interrogation=false");
        BlockingQueue<PlcSubscriptionEvent> events = new LinkedBlockingQueue<>();
        connection.subscriptionRequestBuilder()
            .addChangeOfStateTagAddress("measurement", "1/101")
            .addChangeOfStateTagAddress("station", "1/*")
            .setConsumer(events::add)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        station.sendSpontaneous(101, 7.5f);
        PlcSubscriptionEvent first = events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals(List.of("measurement", "station"), List.copyOf(first.getTagNames()));
        assertEquals(7.5, first.getPlcValue("measurement").getDouble(), 1e-6);
        Iec608705104Tag source = (Iec608705104Tag) first.getTag("station");
        assertEquals(1, source.getAsduAddress());
        assertEquals(101, source.getObjectAddress());
        assertEquals(3, first.getPlcValue("measurement").getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt(),
            "spontaneous");

        station.sendSpontaneous(100, 1.0f);
        PlcSubscriptionEvent second = events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(second);
        assertEquals(List.of("station"), List.copyOf(second.getTagNames()), "1/100 only matches the wildcard");

        assertNull(events.poll(300, TimeUnit.MILLISECONDS), "one event per value");
    }

    @Test
    void handleRegistrationStillWorks() throws Exception {
        connect("general-interrogation=false");
        BlockingQueue<PlcSubscriptionEvent> events = new LinkedBlockingQueue<>();
        connection.subscriptionRequestBuilder()
            .addChangeOfStateTagAddress("measurement", "1/101")
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getSubscriptionHandle("measurement").register(events::add);
        station.sendSpontaneous(101, 3.0f);
        assertNotNull(events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    // -- link layer --------------------------------------------------------------------------

    @Test
    void acknowledgesReceivedFramesWithinT2() throws Exception {
        connect("general-interrogation=false&t1-ms=2000&t2-ms=300");
        station.sendSpontaneous(101, 1.0f);
        APDUSFormat ack = (APDUSFormat) station.awaitReceived(apdu -> apdu instanceof APDUSFormat, 1500);
        assertEquals(1 << 1, ack.getReceiveSequenceNo(), "acknowledges the one I-frame (N(R)=1)");
    }

    @Test
    void sendsTestFrameWhenIdle() throws Exception {
        connect("general-interrogation=false&t3-ms=300");
        // The first TESTFR act is part of the connection setup; t3 causes the second.
        awaitCondition(() -> station.getReceived().stream()
            .filter(apdu -> apdu instanceof APDUUFormatTestFrameActivation).count() >= 2, "TESTFR act after t3");
        assertTrue(connection.isConnected(), "the station confirmed it");
    }

    @Test
    void closesConnectionWhenStationDoesNotAcknowledge() throws Exception {
        station.acknowledgeClientFrames(false).command(300, CommandBehaviour.IGNORE, 0);
        connect("general-interrogation=false&t1-ms=600&t2-ms=200&request-timeout=5000");
        connection.writeRequestBuilder().addTagAddress("setpoint", "1/300", 1.0f).build().execute();
        station.awaitClientDisconnected(3000);
        awaitCondition(() -> !connection.isConnected(), "connection reported as closed");
    }

    @Test
    void sendSequenceNumbersIncrease() throws Exception {
        connect(null);
        writeSingle("1/300", 1.0f);
        writeSingle("1/300", 2.0f);
        List<Integer> sequences = station.getReceived().stream()
            .filter(apdu -> apdu instanceof APDUIFormat)
            .map(apdu -> apdu.getCommand() >> 1)
            .collect(Collectors.toList());
        assertEquals(List.of(0, 1, 2), sequences, "interrogation, then two set points");
    }

    // -- helpers -----------------------------------------------------------------------------

    private PlcResponseCode writeSingle(String address, Object value) throws Exception {
        return connection.writeRequestBuilder()
            .addTagAddress("tag", address, value)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getResponseCode("tag");
    }

    private double readDouble(String address) {
        try {
            PlcReadResponse response = connection.readRequestBuilder().addTagAddress("tag", address)
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return response.getResponseCode("tag") == PlcResponseCode.OK ? response.getPlcValue("tag").getDouble() : Double.NaN;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ASDU awaitAsdu(TypeIdentification type) throws InterruptedException {
        APDUIFormat frame = (APDUIFormat) station.awaitReceived(apdu -> apdu instanceof APDUIFormat
            && ((APDUIFormat) apdu).getAsdu().getTypeIdentification() == type, TIMEOUT_SECONDS * 1000);
        return frame.getAsdu();
    }

    private long countAsdus(TypeIdentification type) {
        return station.getReceivedAsdus().stream().filter(asdu -> asdu.getTypeIdentification() == type).count();
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void awaitCondition(Condition condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }

}
