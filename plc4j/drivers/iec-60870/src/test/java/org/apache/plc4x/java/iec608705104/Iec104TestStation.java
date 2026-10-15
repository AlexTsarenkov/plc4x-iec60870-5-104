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

import org.apache.plc4x.java.iec608705104.readwrite.*;
import org.apache.plc4x.java.iec608705104.readwrite.protocol.Iec104Time;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A minimal IEC 60870-5-104 controlled station (server) for the driver's tests.
 *
 * <p>Written against the same generated message classes as the driver, so on its own it
 * cannot catch mistakes in the mspec; that is what the interop test against the c104
 * server and the byte-level tests are for. What it can do that a real station cannot:
 * misbehave on purpose (not acknowledge, not answer, reject, delay) and record every frame
 * and command value the driver sent.
 *
 * <p>One client connection at a time, one thread reading it. All values live in common
 * address {@link #ASDU_ADDRESS}; commands to any other address than that and the broadcast
 * address are refused with cause 46 (unknown common address).
 */
public class Iec104TestStation implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iec104TestStation.class);

    public static final int ASDU_ADDRESS = 1;
    public static final int BROADCAST = 0xFFFF;

    /** Kinds of monitoring points the station can hold, with the type of their value. */
    public enum PointType {
        /** M_ME_NC_1, Number. */
        FLOAT,
        /** M_ME_NB_1, Number. */
        SCALED,
        /** M_ME_NA_1, Number (-1..1). */
        NORMALIZED,
        /** M_SP_NA_1, Boolean. */
        SINGLE,
        /** M_DP_NA_1, Integer double point code 0..3. */
        DOUBLE,
        /** M_ST_NA_1, Integer step position -64..63. */
        STEP,
        /** M_BO_NA_1, Long. */
        BITSTRING,
        /** M_IT_NA_1, Long; only sent on counter interrogation. */
        COUNTER,
        /** M_ME_TF_1, Number; sent with the time tag set by {@link #timeTag(Instant)}. */
        FLOAT_WITH_TIME
    }

    /** How the station reacts to a command for an IOA. */
    public enum CommandBehaviour {
        /** Positive ACT_CON, update the mirrored monitoring point, send it with COT 11, ACT_TERM. */
        ACCEPT,
        /** Negative ACT_CON. */
        REJECT,
        /** No answer at all. */
        IGNORE
    }

    /** Configuration of one command IOA. Mutable, set up by the tests before connecting. */
    public static final class Command {
        private CommandBehaviour behaviour;
        private int mirrorIoa;
        private boolean requireSelect;
        private boolean sendTermination = true;
        private long terminationDelayMillis;
        private int acceptLimit = Integer.MAX_VALUE;

        private Command(CommandBehaviour behaviour, int mirrorIoa) {
            this.behaviour = behaviour;
            this.mirrorIoa = mirrorIoa;
        }

        /** Executes are refused unless a select of the same IOA came first. */
        public Command requireSelect() {
            this.requireSelect = true;
            return this;
        }

        /** No ACT_TERM after the ACT_CON. */
        public Command withoutTermination() {
            this.sendTermination = false;
            return this;
        }

        /** ACT_TERM only after this delay, like a tap changer that takes its time. */
        public Command terminationDelay(long millis) {
            this.terminationDelayMillis = millis;
            return this;
        }

        /** Accept only this many executes, refuse the ones after. */
        public Command acceptOnly(int executes) {
            this.acceptLimit = executes;
            return this;
        }
    }

    /** A command the station received, with the select bit and time tag decoded. */
    public record ReceivedCommand(ASDU asdu, InformationObject object, boolean select, Instant timeTag) {
    }

    private record Point(PointType type, Object value) {
    }

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iec104-test-station-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, Point> points = new ConcurrentHashMap<>();
    private final Map<Integer, Command> commands = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> executes = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> selected = new ConcurrentHashMap<>();
    private final List<APDU> received = new CopyOnWriteArrayList<>();
    private final List<ASDU> receivedAsdus = new CopyOnWriteArrayList<>();
    private final List<ReceivedCommand> receivedCommands = new CopyOnWriteArrayList<>();

    private volatile boolean acknowledgeClientFrames = true;
    private volatile boolean answerInterrogation = true;
    private volatile boolean answerReadCommands = true;
    private volatile boolean answerBroadcastWithOwnAddress = true;
    private volatile Iec104Time time = new Iec104Time(ZoneOffset.UTC, Clock.systemUTC());
    private volatile Instant timeTag = Instant.parse("2026-01-02T03:04:05.678Z");

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

    /** @return the configuration of a command IOA, to refine it. */
    public Command commandAt(int ioa) {
        return commands.get(ioa);
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

    /** Whether answers to broadcast commands carry the station's own address (default) or 65535. */
    public Iec104TestStation answerBroadcastWithOwnAddress(boolean ownAddress) {
        this.answerBroadcastWithOwnAddress = ownAddress;
        return this;
    }

    /** The zone the station's time tags are in, for sending and for decoding commands. */
    public Iec104TestStation timeZone(ZoneId zone) {
        this.time = new Iec104Time(zone, Clock.systemUTC());
        return this;
    }

    /** The time tag sent with {@link PointType#FLOAT_WITH_TIME} points. */
    public Iec104TestStation timeTag(Instant instant) {
        this.timeTag = instant;
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

    /** @return all commands (control direction ASDUs) received, decoded, in order. */
    public List<ReceivedCommand> getReceivedCommands() {
        return new ArrayList<>(receivedCommands);
    }

    /** @return the received commands of one type, in order. */
    public List<ReceivedCommand> getReceivedCommands(TypeIdentification type) {
        List<ReceivedCommand> result = new ArrayList<>();
        for (ReceivedCommand command : receivedCommands) {
            if (command.asdu().getTypeIdentification() == type) {
                result.add(command);
            }
        }
        return result;
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
        int typeValue = type.getValue();
        if (typeValue >= 45) {
            receivedCommands.add(new ReceivedCommand(request, object, selectBitOf(object), timeTagOf(object)));
        }
        if (request.getAsduAddressField() != ASDU_ADDRESS && request.getAsduAddressField() != BROADCAST) {
            sendAsdu(mirror(request, CauseOfTransmission.ASDU_ADDRESS_UNKNOWN_UNKNOWN_ASDU_ADDRESS, true));
            return;
        }
        switch (type) {
            case INTERROGATION_COMMAND: {
                if (!answerInterrogation) {
                    return;
                }
                sendAsdu(answer(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, false));
                for (Integer ioa : points.keySet()) {
                    if (points.get(ioa).type() != PointType.COUNTER) {
                        sendAsdu(monitoringAsdu(ioa, CauseOfTransmission.INTERROGATED_BY_GENERAL_INTERROGATION_INROGEN));
                    }
                }
                sendAsdu(answer(request, CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM, false));
                return;
            }
            case COUNTER_INTERROGATION_COMMAND: {
                sendAsdu(answer(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, false));
                for (Integer ioa : points.keySet()) {
                    if (points.get(ioa).type() == PointType.COUNTER) {
                        sendAsdu(monitoringAsdu(ioa, CauseOfTransmission.INTERROGATED_BY_COUNTER_GENERAL_INTERROGATION_REQCOGEN));
                    }
                }
                sendAsdu(answer(request, CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM, false));
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
            case CLOCK_SYNCHRONISATION_COMMAND:
            case TEST_COMMAND:
            case TEST_COMMAND_WITH_TIME_TAG_CP56TIME2A:
            case RESET_PROCESS_COMMAND:
            case DELAY_ACQUISITION_COMMAND:
                // Always accepted, only confirmed; the tests look at getReceivedCommands().
                sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, false));
                return;
            case PARAMETER_OF_MEASURED_VALUES_NORMALIZED_VALUE:
            case PARAMETER_OF_MEASURED_VALUES_SCALED_VALUE:
            case PARAMETER_OF_MEASURED_VALUES_SHORT_FLOATING_POINT_NUMBER:
            case PARAMETER_ACTIVATION: {
                Command command = commands.get(object.getAddress());
                if (command == null) {
                    sendAsdu(mirror(request, CauseOfTransmission.INFORMATION_OBJECT_ADDRESS_UNKNOWN_UNKNOWN_OBJECT_ADDRESS, true));
                } else if (command.behaviour != CommandBehaviour.IGNORE) {
                    CauseOfTransmission confirmation = request.getCauseOfTransmission() == CauseOfTransmission.DEACTIVATION_DEACT
                        ? CauseOfTransmission.CONFIRMATION_DEACTIVATION_DEACTCON
                        : CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON;
                    sendAsdu(mirror(request, confirmation, command.behaviour == CommandBehaviour.REJECT));
                }
                return;
            }
            default:
                break;
        }
        if (typeValue >= 45 && typeValue <= 64) {
            onProcessCommand(request, object);
        } else {
            sendAsdu(mirror(request, CauseOfTransmission.TYPE_IDENTIFICATION_UNKNOWN_UKNOWN_TYPE, true));
        }
    }

    private void onProcessCommand(ASDU request, InformationObject object) throws IOException {
        Command command = commands.get(object.getAddress());
        if (command == null) {
            sendAsdu(mirror(request, CauseOfTransmission.INFORMATION_OBJECT_ADDRESS_UNKNOWN_UNKNOWN_OBJECT_ADDRESS, true));
            return;
        }
        if (command.behaviour == CommandBehaviour.IGNORE) {
            return;
        }
        if (command.behaviour == CommandBehaviour.REJECT) {
            sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, true));
            return;
        }
        int ioa = object.getAddress();
        if (selectBitOf(object)) {
            // Select: only allowed where the station is configured for it.
            if (command.requireSelect) {
                selected.put(ioa, true);
            }
            sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, !command.requireSelect));
            return;
        }
        if (command.requireSelect && selected.remove(ioa) == null) {
            sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, true));
            return;
        }
        if (executes.computeIfAbsent(ioa, k -> new AtomicInteger()).incrementAndGet() > command.acceptLimit) {
            sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, true));
            return;
        }
        // Update first, so a test that got the ACT_CON sees the new value.
        Point target = points.get(command.mirrorIoa);
        if (target != null) {
            points.put(command.mirrorIoa, new Point(target.type(), applyCommand(target, object)));
        }
        sendAsdu(mirror(request, CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON, false));
        if (target != null) {
            sendAsdu(monitoringAsdu(command.mirrorIoa, CauseOfTransmission.FEEDBACK_CAUSED_BY_DISTANT_COMMAND_RETREM));
        }
        if (command.sendTermination) {
            ASDU termination = mirror(request, CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM, false);
            if (command.terminationDelayMillis > 0) {
                scheduler.schedule(() -> {
                    try {
                        sendAsdu(termination);
                    } catch (IOException e) {
                        LOGGER.debug("Could not send delayed ACT_TERM: {}", e.getMessage());
                    }
                }, command.terminationDelayMillis, TimeUnit.MILLISECONDS);
            } else {
                sendAsdu(termination);
            }
        }
    }

    /** The new value of a monitoring point after a command. */
    private static Object applyCommand(Point target, InformationObject object) {
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) {
            return ((InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) object).getValue();
        }
        if (object instanceof InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) {
            return ((InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) object).getValue();
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
        if (object instanceof InformationObjectWithSevenByteTime_SINGLE_COMMAND) {
            return ((InformationObjectWithSevenByteTime_SINGLE_COMMAND) object).getSco().getCommandOn();
        }
        if (object instanceof InformationObjectWithoutTime_DOUBLE_COMMAND) {
            return (int) ((InformationObjectWithoutTime_DOUBLE_COMMAND) object).getDco().getDcs();
        }
        if (object instanceof InformationObjectWithoutTime_REGULATING_STEP_COMMAND) {
            int position = (Integer) target.value();
            return ((InformationObjectWithoutTime_REGULATING_STEP_COMMAND) object).getRco().getRcs() == 2 ? position + 1 : position - 1;
        }
        if (object instanceof InformationObjectWithoutTime_BITSTRING_32_BIT_COMMAND) {
            return ((InformationObjectWithoutTime_BITSTRING_32_BIT_COMMAND) object).getBsi().getBits();
        }
        return target.value();
    }

    private static boolean selectBitOf(InformationObject object) {
        if (object instanceof InformationObjectWithoutTime_SINGLE_COMMAND) {
            return ((InformationObjectWithoutTime_SINGLE_COMMAND) object).getSco().getQoc().getSelect();
        }
        if (object instanceof InformationObjectWithSevenByteTime_SINGLE_COMMAND) {
            return ((InformationObjectWithSevenByteTime_SINGLE_COMMAND) object).getSco().getQoc().getSelect();
        }
        if (object instanceof InformationObjectWithoutTime_DOUBLE_COMMAND) {
            return ((InformationObjectWithoutTime_DOUBLE_COMMAND) object).getDco().getQoc().getSelect();
        }
        if (object instanceof InformationObjectWithoutTime_REGULATING_STEP_COMMAND) {
            return ((InformationObjectWithoutTime_REGULATING_STEP_COMMAND) object).getRco().getQoc().getSelect();
        }
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) {
            return ((InformationObjectWithoutTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) object).getQos().getSelect();
        }
        if (object instanceof InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) {
            return ((InformationObjectWithSevenByteTime_SET_POINT_COMMAND_SHORT_FLOATING_POINT_NUMBER) object).getQos().getSelect();
        }
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE) {
            return ((InformationObjectWithoutTime_SET_POINT_COMMAND_SCALED_VALUE) object).getQos().getSelect();
        }
        if (object instanceof InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE) {
            return ((InformationObjectWithoutTime_SET_POINT_COMMAND_NORMALISED_VALUE) object).getQos().getSelect();
        }
        return false;
    }

    private Instant timeTagOf(InformationObject object) {
        if (object instanceof InformationObjectWithSevenByteTime) {
            return time.fromCp56(((InformationObjectWithSevenByteTime) object).getCp56Time2a());
        }
        if (object instanceof InformationObjectWithoutTime_CLOCK_SYNCHRONISATION_COMMAND) {
            return time.fromCp56(((InformationObjectWithoutTime_CLOCK_SYNCHRONISATION_COMMAND) object).getCp56Time2a());
        }
        if (object instanceof InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG) {
            return time.fromCp56(((InformationObjectWithoutTime_TEST_COMMAND_WITH_TIME_TAG) object).getCp56Time2a());
        }
        return null;
    }

    private ASDU monitoringAsdu(int ioa, CauseOfTransmission cause) {
        Point point = points.get(ioa);
        QualityDescriptor good = new QualityDescriptor(false, false, false, false, false);
        switch (point.type()) {
            case FLOAT:
                return asdu(TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER, cause, false,
                    new InformationObjectWithoutTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER(
                        ioa, ((Number) point.value()).floatValue(), good));
            case FLOAT_WITH_TIME:
                return asdu(TypeIdentification.MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER_WITH_TIME_TAG_CP56TIME2A, cause, false,
                    new InformationObjectWithSevenByteTime_MEASURED_VALUE_SHORT_FLOATING_POINT_NUMBER(
                        ioa, ((Number) point.value()).floatValue(), good, time.toCp56(timeTag)));
            case SCALED:
                return asdu(TypeIdentification.MEASURED_VALUE_SCALED_VALUE, cause, false,
                    new InformationObjectWithoutTime_MEASURED_VALUE_SCALED_VALUE(
                        ioa, new ScaledValue(((Number) point.value()).shortValue()), good));
            case NORMALIZED:
                int raw = ((short) Math.round(((Number) point.value()).doubleValue() * 32768)) & 0xFFFF;
                return asdu(TypeIdentification.MEASURED_VALUE_NORMALISED_VALUE, cause, false,
                    new InformationObjectWithoutTime_MEASURED_VALUE_NORMALISED_VALUE(ioa, new NormalizedValue(raw), good));
            case DOUBLE:
                return asdu(TypeIdentification.DOUBLE_POINT_INFORMATION, cause, false,
                    new InformationObjectWithoutTime_DOUBLE_POINT_INFORMATION(
                        ioa, new DoublePointInformation(false, false, false, false, ((Number) point.value()).byteValue())));
            case STEP:
                return asdu(TypeIdentification.STEP_POSITION_INFORMATION, cause, false,
                    new InformationObjectWithoutTime_STEP_POSITION_INFORMATION(ioa,
                        new ValueWithTransientStateIndication(false, (byte) (((Number) point.value()).intValue() & 0x7F)), good));
            case BITSTRING:
                return asdu(TypeIdentification.BITSTRING_OF_32_BIT, cause, false,
                    new InformationObjectWithoutTime_BITSTRING_OF_32_BIT(
                        ioa, new BinaryStateInformation(((Number) point.value()).longValue()), good));
            case COUNTER:
                return asdu(TypeIdentification.INTEGRATED_TOTALS, cause, false,
                    new InformationObjectWithoutTime_INTEGRATED_TOTALS(ioa,
                        new BinaryCounterReading(((Number) point.value()).longValue(), false, false, false, (byte) 0)));
            case SINGLE:
            default:
                return asdu(TypeIdentification.SINGLE_POINT_INFORMATION, cause, false,
                    new InformationObjectWithoutTime_SINGLE_POINT_INFORMATION(
                        ioa, new SinglePointInformation(false, false, false, false, (Boolean) point.value())));
        }
    }

    /** The request mirrored with another cause, keeping its common address. */
    private static ASDU mirror(ASDU request, CauseOfTransmission cause, boolean negative) {
        return new ASDU(request.getTypeIdentification(), false, false, negative, cause,
            request.getOriginatorAddress(), request.getAsduAddressField(), request.getInformationObjects());
    }

    /** Like {@link #mirror}, but answers a broadcast with the station's own address if so configured. */
    private ASDU answer(ASDU request, CauseOfTransmission cause, boolean negative) {
        int address = request.getAsduAddressField() == BROADCAST && answerBroadcastWithOwnAddress
            ? ASDU_ADDRESS : request.getAsduAddressField();
        return new ASDU(request.getTypeIdentification(), false, false, negative, cause,
            request.getOriginatorAddress(), address, request.getInformationObjects());
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
        scheduler.shutdownNow();
        disconnectClient();
        serverSocket.close();
    }

}
