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
import org.apache.plc4x.java.iec608705104.readwrite.protocol.Iec608705104Protocol;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Interoperability test against the c104 (lib60870-C) test server in
 * {@code src/test/docker/iec104-test-server}. Its point list is documented in that
 * directory's README.md; the expected values below come from there.
 *
 * <p>Skipped unless {@code -Diec104.it.host=<host>} is given (CI does that after starting
 * the container). Locally:
 * <pre>
 *   docker build -t iec104-test-server src/test/docker/iec104-test-server
 *   docker run --rm -p 2404:2404 iec104-test-server
 *   mvn -P with-java -pl plc4j/drivers/iec-60870 verify -Diec104.it.host=127.0.0.1
 * </pre>
 *
 * <p>The tests share the server's state and run in a fixed order: initial values are
 * checked before anything is written.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Iec608705104InteropIT {

    private static final long TIMEOUT_SECONDS = 10;

    private static String host;
    private static int port;

    @BeforeAll
    static void requireServer() {
        host = System.getProperty("iec104.it.host");
        port = Integer.getInteger("iec104.it.port", 2404);
        assumeTrue(host != null && !host.isEmpty(), "iec104.it.host not set, no test server to talk to");
    }

    private static PlcConnection connect(String options) throws Exception {
        String url = "iec-60870-5-104:tcp://" + host + ":" + port + (options == null ? "" : "?" + options);
        PlcConnection connection = PlcDriverManager.getDefault().getConnectionManager().getConnection(url);
        assertTrue(connection.isConnected());
        return connection;
    }

    @Test
    @Order(1)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void readsInitialValuesFromGeneralInterrogation() throws Exception {
        try (PlcConnection connection = connect(null)) {
            PlcReadResponse response = connection.readRequestBuilder()
                .addTagAddress("float", "1/100")
                .addTagAddress("scaled", "1/110")
                .addTagAddress("normalized", "1/120")
                .addTagAddress("single", "1/200")
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(PlcResponseCode.OK, response.getResponseCode("float"));
            assertEquals(12.5, response.getPlcValue("float").getDouble(), 1e-6);
            assertEquals(1234, response.getPlcValue("scaled").getInt());
            assertEquals(0.5, response.getPlcValue("normalized").getDouble(), 1e-4);
            assertEquals(false, response.getPlcValue("single").getBoolean());
            assertEquals(20, response.getPlcValue("float").getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt());
        }
    }

    @Test
    @Order(2)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void readsWithReadCommand() throws Exception {
        try (PlcConnection connection = connect("read-mode=command&general-interrogation=false")) {
            PlcReadResponse response = connection.readRequestBuilder()
                .addTagAddress("scaled", "1/110")
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(PlcResponseCode.OK, response.getResponseCode("scaled"));
            assertEquals(1234, response.getPlcValue("scaled").getInt());
        }
    }

    @Test
    @Order(3)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void writesAllSupportedTypes() throws Exception {
        try (PlcConnection connection = connect(null)) {
            BlockingQueue<PlcSubscriptionEvent> events = new LinkedBlockingQueue<>();
            connection.subscriptionRequestBuilder()
                .addChangeOfStateTagAddress("float", "1/100")
                .setConsumer(event -> {
                    // Only the return information (COT 11), not the interrogation answer.
                    int cause = event.getPlcValue("float").getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt();
                    if (cause == 11) {
                        events.add(event);
                    }
                })
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            PlcWriteResponse response = connection.writeRequestBuilder()
                .addTagAddress("float", "1/300", 33.25f)
                .addTagAddress("scaled", "1/310:SCALED", -42)
                .addTagAddress("normalized", "1/320:NORMALIZED", -0.25)
                .addTagAddress("single", "1/400", true)
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(PlcResponseCode.OK, response.getResponseCode("float"));
            assertEquals(PlcResponseCode.OK, response.getResponseCode("scaled"));
            assertEquals(PlcResponseCode.OK, response.getResponseCode("normalized"));
            assertEquals(PlcResponseCode.OK, response.getResponseCode("single"));

            // The server sends the new value of IOA 100 as return information (COT 11).
            PlcSubscriptionEvent event = events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(event, "return information for IOA 100");
            assertEquals(33.25, event.getPlcValue("float").getDouble(), 1e-6);
        }
        try (PlcConnection connection = connect("read-mode=command&general-interrogation=false")) {
            PlcReadResponse response = connection.readRequestBuilder()
                .addTagAddress("float", "1/100")
                .addTagAddress("scaled", "1/110")
                .addTagAddress("normalized", "1/120")
                .addTagAddress("single", "1/200")
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(33.25, response.getPlcValue("float").getDouble(), 1e-6);
            assertEquals(-42, response.getPlcValue("scaled").getInt());
            assertEquals(-0.25, response.getPlcValue("normalized").getDouble(), 1e-4);
            assertEquals(true, response.getPlcValue("single").getBoolean());
        }
    }

    @Test
    @Order(4)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rejectedAndUnknownCommands() throws Exception {
        try (PlcConnection connection = connect("general-interrogation=false")) {
            PlcWriteResponse response = connection.writeRequestBuilder()
                .addTagAddress("rejected", "1/399", 1.0f)
                .addTagAddress("unknown", "1/12345", 1.0f)
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(PlcResponseCode.REMOTE_ERROR, response.getResponseCode("rejected"));
            // lib60870 answers a command for an unknown IOA with a negative ACT_CON rather
            // than with cause 47 (unknown IOA); both are valid ways for a station to refuse.
            PlcResponseCode unknown = response.getResponseCode("unknown");
            assertTrue(unknown == PlcResponseCode.NOT_FOUND || unknown == PlcResponseCode.REMOTE_ERROR,
                "unknown IOA must be refused, was " + unknown);
        }
    }

    @Test
    @Order(5)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void doubleStepBitstringAndTimeTaggedCommands() throws Exception {
        try (PlcConnection connection = connect(null)) {
            PlcWriteResponse response = connection.writeRequestBuilder()
                .addTagAddress("double", "1/500:C_DC_NA_1", true)
                .addTagAddress("step", "1/600:C_RC_NA_1", 2)
                .addTagAddress("bits", "1/700:C_BO_NA_1", 0x0F0F)
                .addTagAddress("floatTime", "1/301:C_SE_TC_1", 2.25f)
                .addTagAddress("singleTime", "1/401:C_SC_TA_1", true)
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (String name : response.getTagNames()) {
                assertEquals(PlcResponseCode.OK, response.getResponseCode(name), name);
            }
        }
        try (PlcConnection connection = connect("read-mode=command&general-interrogation=false")) {
            PlcReadResponse response = connection.readRequestBuilder()
                .addTagAddress("double", "1/210")
                .addTagAddress("step", "1/220")
                .addTagAddress("bits", "1/230")
                .addTagAddress("float", "1/100")
                .addTagAddress("single", "1/200")
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(2, response.getPlcValue("double").getInt(), "on");
            assertEquals(2, response.getPlcValue("step").getInt(), "two steps up from 0");
            assertEquals(0x0F0F, response.getPlcValue("bits").getLong());
            assertEquals(2.25, response.getPlcValue("float").getDouble(), 1e-6);
            assertEquals(true, response.getPlcValue("single").getBoolean());
        }
    }

    @Test
    @Order(6)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void selectBeforeOperate() throws Exception {
        try (PlcConnection connection = connect("general-interrogation=false")) {
            assertEquals(PlcResponseCode.REMOTE_ERROR, write(connection, "1/302", 5.5f),
                "the server only allows select-and-execute on IOA 302");
            assertEquals(PlcResponseCode.OK, write(connection, "1/302:C_SE_NC_1,sbo", 6.5f));
        }
        try (PlcConnection connection = connect("read-mode=command&general-interrogation=false")) {
            assertEquals(6.5, read(connection, "1/100").getDouble(), 1e-6);
        }
    }

    @Test
    @Order(7)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void systemCommands() throws Exception {
        try (PlcConnection connection = connect("wait-for-termination=true")) {
            assertEquals(PlcResponseCode.OK, write(connection, "1/0:C_CS_NA_1", 0), "clock synchronization");
            assertEquals(PlcResponseCode.OK, write(connection, "1/0:C_TS_TA_1", 0), "test command");
            assertEquals(PlcResponseCode.OK, write(connection, "1/0:C_CI_NA_1", 5), "counter interrogation");
            PlcReadResponse response = connection.readRequestBuilder()
                .addTagAddress("counter", "1/240")
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(PlcResponseCode.OK, response.getResponseCode("counter"));
            assertEquals(123456L, response.getPlcValue("counter").getLong());
        }
        try (PlcConnection connection = connect("read-mode=command&general-interrogation=false")) {
            double offset = read(connection, "1/901").getDouble();
            assertTrue(Math.abs(offset) < 5, "server clock vs. synchronization: " + offset + " s");
        }
    }

    private static PlcResponseCode write(PlcConnection connection, String address, Object value) throws Exception {
        return connection.writeRequestBuilder().addTagAddress("tag", address, value)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResponseCode("tag");
    }

    private static PlcValue read(PlcConnection connection, String address) throws Exception {
        PlcReadResponse response = connection.readRequestBuilder().addTagAddress("tag", address)
            .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PlcResponseCode.OK, response.getResponseCode("tag"), "read " + address);
        return response.getPlcValue("tag");
    }

    /**
     * The server sends IOA 101 only every 30 s. A client that does not acknowledge received
     * frames within t2 gets disconnected by the server after t1 (15 s); this one must still be
     * connected and receiving after more than t1.
     */
    @Test
    @Order(8)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void connectionSurvivesSparseTraffic() throws Exception {
        try (PlcConnection connection = connect(null)) {
            BlockingQueue<PlcSubscriptionEvent> events = new LinkedBlockingQueue<>();
            connection.subscriptionRequestBuilder()
                .addChangeOfStateTagAddress("slow", "1/101")
                .setConsumer(event -> {
                    int cause = event.getPlcValue("slow").getMetaData(Iec608705104Protocol.METADATA_CAUSE_OF_TRANSMISSION).getInt();
                    if (cause == 3) {
                        events.add(event);
                    }
                })
                .build().execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // Two spontaneous values, i.e. well over t1 without much traffic.
            assertNotNull(events.poll(45, TimeUnit.SECONDS), "first spontaneous value");
            assertNotNull(events.poll(45, TimeUnit.SECONDS), "second spontaneous value");
            assertTrue(connection.isConnected());
        }
    }

}
