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

import org.apache.plc4x.java.iec608705104.readwrite.APDU;
import org.apache.plc4x.java.iec608705104.readwrite.APDUIFormat;
import org.apache.plc4x.java.iec608705104.readwrite.APDUSFormat;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatStartDataTransferActivation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatStartDataTransferConfirmation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameActivation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameConfirmation;
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfInitialization;
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfTransmission;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObject;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_END_OF_INITIALISATION;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_MEASURED_VALUE_NORMALISED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_MEASURED_VALUE_SCALED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SINGLE_COMMAND;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithoutTime_SINGLE_POINT_INFORMATION;
import org.apache.plc4x.java.iec608705104.readwrite.NormalizedValue;
import org.apache.plc4x.java.iec608705104.readwrite.QualityDescriptor;
import org.apache.plc4x.java.iec608705104.readwrite.ScaledValue;
import org.apache.plc4x.java.iec608705104.readwrite.SinglePointInformation;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.spi.generation.ByteOrder;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.apache.plc4x.java.spi.generation.WriteBufferByteBased;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A minimal IEC 60870-5-104 controlled station (server) for the driver's tests.
 *
 * <p>Written against the same generated message classes as the driver, so on its own it
 * cannot catch mistakes in the mspec; that is what the interop test against the c104
 * server is for. What it can do that a real station cannot: misbehave on purpose (not
 * acknowledge, not answer, reject) and record every frame the driver sent.
 *
 * <p>One client connection at a time, one thread reading it. All values live in common
 * address {@link #ASDU_ADDRESS}.
 */
public class Iec104TestStation implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iec104TestStation.class);

    public static final int ASDU_ADDRESS = 1;

    /** Kinds of monitoring points the station can hold. */
    public enum PointType { FLOAT, SCALED, NORMALIZED, SINGLE }

    /** How the station reacts to a command for an IOA. */
    public enum CommandBehaviour {
        /** Positive ACT_CON, update the mirrored monitoring point and send it with COT 11. */
        ACCEPT,
        /** Negative ACT_CON. */
        REJECT,
        /** No answer at all. */
        IGNORE
    }

    private record Point(PointType type, Object value) {
    }

    private record Command(CommandBehaviour behaviour, int mirrorIoa) {
    }

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final Map<Integer, Point> points = new ConcurrentHashMap<>();
    private final Map<Integer, Command> commands = new ConcurrentHashMap<>();
    private final List<APDU> received = new CopyOnWriteArrayList<>();
    private final List<ASDU> receivedAsdus = new CopyOnWriteArrayList<>();

    private volatile boolean acknowledgeClientFrames = true;
    private volatile boolean answerInterrogation = true;
    private volatile boolean answerReadCommands = true;

    private volatile Socket client;
    private volatile OutputStream output;
    private int sendSequence;
    private int receiveSequence;
    private volatile boolean closed;

    public Iec104TestStation() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        acceptThread = new Thread(this::acceptLoop, "iec104-test-station");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** @return the TCP port the station listens on (on 127.0.0.1). */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** @return connection string for this station, with optional options appended. */
    public String connectionString(String options) {
        return "iec-60870-5-104:tcp://127.0.0.1:" + getPort() + (options == null || options.isEmpty() ? "" : "?" + options);
    }

    // -- configuration ---------------------------------------------------------------------

    public Iec104TestStation point(int ioa, PointType type, Object value) {
        points.put(ioa, new Point(type, value));
        return this;
    }

    public Iec104TestStation command(int ioa, CommandBehaviour behaviour, int mirrorIoa) {
        commands.put(ioa, new Command(behaviour, mirrorIoa));
        return this;
    }

    public Iec104TestStation acknowledgeClientFrames(boolean acknowledge) {
        this.acknowledgeClientFrames = acknowledge;
        return this;
    }

    public Iec104TestStation answerInterrogation(boolean answer) {
        this.answerInterrogation = answer;
        return this;
    }

    public Iec104TestStation answerReadCommands(boolean answer) {
        this.answerReadCommands = answer;
        return this;
    }

    // -- inspection ------------------------------------------------------------------------

    /** @return every frame received from the client, in order. */
    public List<APDU> getReceived() {
        return new ArrayList<>(received);
    }

    /** @return the ASDUs of all I-frames received from the client, in order. */
    public List<ASDU> getReceivedAsdus() {
        return new ArrayList<>(receivedAsdus);
    }

    /** @return the current value of a monitoring point. */
    public Object valueOf(int ioa) {
        Point point = points.get(ioa);
        return point == null ? null : point.value();
    }

    /** @return true while a client is connected. */
    public boolean isClientConnected() {
        Socket socket = client;
        return socket != null && !socket.isClosed();
    }

    /**
     * Waits until a received frame matches.
     *
     * @return the frame.
     * @throws AssertionError after the timeout.
     */
    public APDU awaitReceived(Predicate<APDU> predicate, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            for (APDU apdu : received) {
                if (predicate.test(apdu)) {
                    return apdu;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No matching frame received within " + timeoutMillis + " ms, received: " + received);
    }

    /** Waits until no client is connected any more. */
    public void awaitClientDisconnected(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (!isClientConnected()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Client still connected after " + timeoutMillis + " ms");
    }

    // -- actions ---------------------------------------------------------------------------

    /** Changes a monitoring point and sends it spontaneously (COT 3). */
    public void sendSpontaneous(int ioa, Object value) throws IOException {
        Point point = points.get(ioa);
        points.put(ioa, new Point(point.type(), value));
        sendAsdu(monitoringAsdu(ioa, CauseOfTransmission.SPONTANEOUS_SPONT));
    }

    /** Reports a (re)start of the station, M_EI_NA_1. */
    public void sendEndOfInitialisation() throws IOException {
        sendAsdu(asdu(TypeIdentification.END_OF_INITIALISATION, CauseOfTransmission.INITIALIZED_INIT, false,
            new InformationObjectWithoutTime_END_OF_INITIALISATION(0, new CauseOfInitialization(false, (byte) 0))));
    }

    // -- connection handling ---------------------------------------------------------------

    private void acceptLoop() {
        while (!closed) {
            try (Socket socket = serverSocket.accept()) {
                client = socket;
                output = socket.getOutputStream();
                synchronized (this) {
                    sendSequence = 0;
                    receiveSequence = 0;
                }
                readLoop(new DataInputStream(socket.getInputStream()));
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.debug("Test station connection ended: {}", e.getMessage());
                }
            } finally {
                client = null;
            }
        }
    }

    private void readLoop(DataInputStream input) throws IOException {
        while (!closed) {
            int start = input.readUnsignedByte();
            int length = input.readUnsignedByte();
            byte[] frame = new byte[length + 2];
            frame[0] = (byte) start;
            frame[1] = (byte) length;
            input.readFully(frame, 2, length);
            APDU apdu;
            try {
                apdu = APDU.staticParse(new ReadBufferByteBased(frame, ByteOrder.LITTLE_ENDIAN));
            } catch (Exception e) {
                throw new IOException("Cannot parse frame from client", e);
            }
            received.add(apdu);
            onFrame(apdu);
        }
    }

    private void onFrame(APDU apdu) throws IOException {
        if (apdu instanceof APDUUFormatTestFrameActivation) {
            send(new APDUUFormatTestFrameConfirmation(0x83));
        } else if (apdu instanceof APDUUFormatStartDataTransferActivation) {
            send(new APDUUFormatStartDataTransferConfirmation(0x0B));
        } else if (apdu instanceof APDUIFormat) {
            APDUIFormat frame = (APDUIFormat) apdu;
            synchronized (this) {
                receiveSequence = ((frame.getCommand() >> 1) + 1) % 32768;
            }
            receivedAsdus.add(frame.getAsdu());
            if (acknowledgeClientFrames) {
                synchronized (this) {
                    send(new APDUSFormat(0x01, receiveSequence << 1));
                }
            }
            onAsdu(frame.getAsdu());
        }
    }

    private void onAsdu(ASDU request) throws IOException {
        TypeIdentification type = request.getTypeIdentification();
        InformationObject object = request.getInformationObjects().get(0);
        switch (type) {
            case INTERROGATION_COMMAND: {
                if (!answerInterrogation) {
                    return;
                }
                sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, false));
                for (Integer ioa : points.keySet()) {
                    sendAsdu(monitoringAsdu(ioa, CauseOfTransmission.INTERROGATED_BY_GENERAL_INTERROGATION_INROGEN));
                }
                sendAsdu(mirror(request, CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM, false));
                return;
            }
            case READ_COMMAND: {
                if (!answerReadCommands) {
                    return;
                }
                if (points.containsKey(object.getAddress())) {
                    sendAsdu(monitoringAsdu(object.getAddress(), CauseOfTransmission.INTERROGATION_OR_INTERROGATED_REQ));
                } else {
                    sendAsdu(mirror(request, CauseOfTransmission.INFORMATION_OBJECT_ADDRESS_UNKNOWN_UNKNOWN_OBJECT_ADDRESS, true));
                }
                return;
            }
            case SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER:
            case SET_POINT_COMMAND_SCALED_VALUE:
            case SET_POINT_COMMAND_NORMALISED_VALUE:
            case SINGLE_COMMAND: {
                Command command = commands.get(object.getAddress());
                if (command == null) {
                    sendAsdu(mirror(request, CauseOfTransmission.INFORMATION_OBJECT_ADDRESS_UNKNOWN_UNKNOWN_OBJECT_ADDRESS, true));
                    return;
                }
                switch (command.behaviour()) {
                    case IGNORE:
                        return;
                    case REJECT:
                        sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, true));
                        return;
                    case ACCEPT:
                    default:
                        // Update first, so a test that got the ACT_CON sees the new value.
                        Point target = points.get(command.mirrorIoa());
                        if (target != null) {
                            points.put(command.mirrorIoa(), new Point(target.type(), commandValue(object)));
                        }
                        sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, false));
                        if (target != null) {
                            sendAsdu(monitoringAsdu(command.mirrorIoa(), CauseOfTransmission.FEEDBACK_CAUSED_BY_DISTANT_COMMAND_RETREM));
                        }
                        sendAsdu(mirror(request, CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM, false));
                        return;
                }
            }
            default:
                sendAsdu(mirror(request, CauseOfTransmission.TYPE_IDENTIFICATION_UNKNOWN_UKNOWN_TYPE, true));
        }
    }

    /** The value carried by a command, in the representation the monitoring points use. */
    private static Object commandValue(InformationObject object) {
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) {
            return ((InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) object).getValue();
        }
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE) {
            return ((InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE) object).getSva().getValue();
        }
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE) {
            return ((short) ((InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE) object).getNva().getValue()) / 32768.0;
        }
        if (object instanceof InformationObjectWithoutTime_SINGLE_COMMAND) {
            return ((InformationObjectWithoutTime_SINGLE_COMMAND) object).getSco().getCommandOn();
        }
        throw new IllegalArgumentException("Not a supported command: " + object);
    }

    private ASDU monitoringAsdu(int ioa, CauseOfTransmission cause) {
        Point point = points.get(ioa);
        QualityDescriptor good = new QualityDescriptor(false, false, false, false, false);
        switch (point.type()) {
            case FLOAT:
                return asdu(TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER, cause, false,
                    new InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER(
                        ioa, ((Number) point.value()).floatValue(), good));
            case SCALED:
                return asdu(TypeIdentification.MEASURED_VALUE_SCALED_VALUE, cause, false,
                    new InformationObjectWithoutTime_MEASURED_VALUE_SCALED_VALUE(
                        ioa, new ScaledValue(((Number) point.value()).shortValue()), good));
            case NORMALIZED:
                int raw = ((short) Math.round(((Number) point.value()).doubleValue() * 32768)) & 0xFFFF;
                return asdu(TypeIdentification.MEASURED_VALUE_NORMALISED_VALUE, cause, false,
                    new InformationObjectWithoutTime_MEASURED_VALUE_NORMALISED_VALUE(ioa, new NormalizedValue(raw), good));
            case SINGLE:
            default:
                return asdu(TypeIdentification.SINGLE_POINT_INFORMATION, cause, false,
                    new InformationObjectWithoutTime_SINGLE_POINT_INFORMATION(
                        ioa, new SinglePointInformation(false, false, false, false, (Boolean) point.value())));
        }
    }

    private static ASDU mirror(ASDU request, CauseOfTransmission cause, boolean negative) {
        return new ASDU(request.getTypeIdentification(), false, false, negative, cause,
            request.getOriginatorAddress(), request.getAsduAddressField(), request.getInformationObjects());
    }

    private static ASDU asdu(TypeIdentification type, CauseOfTransmission cause, boolean negative, InformationObject object) {
        return new ASDU(type, false, false, negative, cause, (short) 0, ASDU_ADDRESS, List.of(object));
    }

    private synchronized void sendAsdu(ASDU asdu) throws IOException {
        send(new APDUIFormat(sendSequence << 1, receiveSequence << 1, asdu));
        sendSequence = (sendSequence + 1) % 32768;
    }

    private synchronized void send(APDU apdu) throws IOException {
        OutputStream out = output;
        if (out == null) {
            throw new IOException("No client connected");
        }
        try {
            WriteBufferByteBased buffer = new WriteBufferByteBased(apdu.getLengthInBytes(), ByteOrder.LITTLE_ENDIAN);
            apdu.serialize(buffer);
            out.write(buffer.getBytes());
            out.flush();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot serialize " + apdu, e);
        }
    }

    /** Drops the client connection, as a station restart would. */
    public void disconnectClient() throws IOException {
        Socket socket = client;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        disconnectClient();
        serverSocket.close();
    }

}
