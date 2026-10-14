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
            assertEquals(PlcResponseCode.NOT_FOUND, response.getResponseCode("unknown"));
        }
    }

    /**
     * The server sends IOA 101 only every 30 s. A client that does not acknowledge received
     * frames within t2 gets disconnected by the server after t1 (15 s); this one must still be
     * connected and receiving after more than t1.
     */
    @Test
    @Order(5)
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
