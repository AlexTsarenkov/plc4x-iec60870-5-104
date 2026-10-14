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

import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.PlcConsumerRegistration;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.iec608705104.readwrite.APDU;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatStartDataTransferActivation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatStartDataTransferConfirmation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameActivation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameConfirmation;
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.apache.plc4x.java.iec608705104.readwrite.CauseOfTransmission;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObject;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithSevenByteTime;
import org.apache.plc4x.java.iec608705104.readwrite.InformationObjectWithTreeByteTime;
import org.apache.plc4x.java.iec608705104.readwrite.SevenOctetBinaryTime;
import org.apache.plc4x.java.iec608705104.readwrite.ThreeOctetBinaryTime;
import org.apache.plc4x.java.iec608705104.readwrite.TypeIdentification;
import org.apache.plc4x.java.iec608705104.readwrite.configuration.Iec608705014Configuration;
import org.apache.plc4x.java.iec608705104.readwrite.configuration.Iec608705014Configuration.ReadMode;
import org.apache.plc4x.java.iec608705104.readwrite.messages.Iec608705104PlcSubscriptionEvent;
import org.apache.plc4x.java.iec608705104.readwrite.model.Iec608705104SubscriptionHandle;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104DataType;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104TagHandler;
import org.apache.plc4x.java.spi.ConversationContext;
import org.apache.plc4x.java.spi.Plc4xProtocolBase;
import org.apache.plc4x.java.spi.configuration.HasConfiguration;
import org.apache.plc4x.java.spi.connection.PlcTagHandler;
import org.apache.plc4x.java.spi.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.spi.messages.DefaultPlcSubscriptionRequest;
import org.apache.plc4x.java.spi.messages.DefaultPlcSubscriptionResponse;
import org.apache.plc4x.java.spi.messages.DefaultPlcWriteResponse;
import org.apache.plc4x.java.spi.messages.PlcSubscriber;
import org.apache.plc4x.java.spi.messages.utils.DefaultPlcResponseItem;
import org.apache.plc4x.java.spi.messages.utils.PlcResponseItem;
import org.apache.plc4x.java.spi.model.DefaultPlcConsumerRegistration;
import org.apache.plc4x.java.spi.model.DefaultPlcSubscriptionTag;
import org.apache.plc4x.java.spi.values.PlcLDATE_AND_TIME;
import org.apache.plc4x.java.spi.values.PlcUSINT;
import org.apache.plc4x.java.spi.values.PlcValueAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The IEC 60870-5-104 client (controlling station) side of one connection.
 *
 * <p>PLC4X creates one instance per connection and wires it into a Netty pipeline that
 * already turns bytes into {@link APDU} objects and back (generated from the mspec). This
 * class decides what to send and what received frames mean.
 *
 * <h2>Life cycle</h2>
 * <ol>
 *   <li>{@link #onConnect}: TESTFR act/con, then STARTDT act/con. Afterwards the
 *       {@link Iec104LinkLayer} is started, the connection is reported as connected and,
 *       unless disabled, a general interrogation (C_IC_NA_1) is sent.</li>
 *   <li>{@link #decode}: every received frame goes through the link layer; I-frame payloads
 *       (ASDUs) are dispatched by {@link #onAsdu}.</li>
 *   <li>{@link #read}, {@link #write}, {@link #subscribe}: called by PLC4X on the user's
 *       thread.</li>
 *   <li>The connection is closed by the user ({@link #close}), by the station, or by the
 *       link layer after a protocol error. All three end in {@link #onConnectionClosed()},
 *       which fails every request still waiting for an answer.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * All protocol state (link layer, pending requests) is only touched on the Netty event loop
 * of this connection. Public methods called from other threads hand their work to the
 * event loop ({@link #runOnEventLoop}). The value cache and the consumer list are
 * concurrent collections because they are also read from other threads.
 *
 * <p>Consequence for users: the futures of read/write requests and subscription consumers
 * complete <b>on the event loop</b>. Blocking there (for example calling {@code get()} on
 * another PLC4X request of the same connection) stalls the connection. Hand longer work
 * over to an executor of your own.
 *
 * <h2>How answers are matched to requests</h2>
 * IEC 104 has no request ids. A command is confirmed by the station repeating the ASDU
 * with cause of transmission ACT_CON (7), so a pending write is identified by
 * (type identification, common address, IOA); a read command by (common address, IOA) and
 * the cause "request" (5). Requests for the same key are answered in order.
 */
public class Iec608705104Protocol extends Plc4xProtocolBase<APDU>
    implements HasConfiguration<Iec608705014Configuration>, PlcSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iec608705104Protocol.class);

    /** How often the link layer timers are checked, in milliseconds. */
    static final long TICK_INTERVAL_MILLIS = 100;

    /** Control field values of the U-frames sent during connection setup. */
    private static final int TESTFR_ACT_COMMAND = 0x43;
    private static final int STARTDT_ACT_COMMAND = 0x07;

    /** Highest type identification in the monitoring direction (M_EP_TF_1 = 40, reserved up to 44). */
    private static final int LAST_MONITORING_TYPE = 44;
    /** Type identifications 45..69 are commands in the control direction. */
    private static final int FIRST_COMMAND_TYPE = 45;
    private static final int LAST_COMMAND_TYPE = 69;

    /** Metadata key: cause of transmission (numeric, see IEC 60870-5-101 7.2.3). */
    public static final String METADATA_CAUSE_OF_TRANSMISSION = "cot";
    /** Metadata key: type identification (numeric, see IEC 60870-5-101 7.2.1). */
    public static final String METADATA_TYPE_IDENTIFICATION = "typeId";
    /** Metadata key: time tag of the object, or receive time if it had none. */
    public static final String METADATA_TIMESTAMP = "timestamp";

    /** A pending write is identified by the command type and the address it was sent to. */
    private record CommandKey(TypeIdentification type, int asduAddress, int objectAddress) {
    }

    /** A pending read command is identified by the address it was sent to. */
    private record ObjectKey(int asduAddress, int objectAddress) {
    }

    private Iec608705014Configuration configuration;

    private Channel channel;
    private Iec104LinkLayer linkLayer;
    private ScheduledFuture<?> tickTask;
    private boolean connectionClosed;

    private final ValueCache valueCache = new ValueCache();

    /**
     * Completes when the general interrogation after connecting has finished (ACT_TERM),
     * failed, timed out, or was not requested. Reads in cache mode wait for it, so that a
     * read right after connecting sees the station's values instead of an empty cache.
     */
    private final CompletableFuture<Void> initialInterrogation = new CompletableFuture<>();

    private final Map<CommandKey, Deque<CompletableFuture<PlcResponseCode>>> pendingWrites = new HashMap<>();
    private final Map<ObjectKey, Deque<CompletableFuture<PlcResponseItem<PlcValue>>>> pendingReads = new HashMap<>();

    private final Map<DefaultPlcConsumerRegistration, Consumer<PlcSubscriptionEvent>> consumers = new ConcurrentHashMap<>();

    @Override
    public void setConfiguration(Iec608705014Configuration configuration) {
        configuration.validate();
        this.configuration = configuration;
    }

    @Override
    public PlcTagHandler getTagHandler() {
        return new Iec608705104TagHandler();
    }

    /**
     * @return the cache of received values. Exposed for tests and diagnostics.
     */
    public ValueCache getValueCache() {
        return valueCache;
    }

    // ---------------------------------------------------------------------------------------
    // Connection setup and teardown
    // ---------------------------------------------------------------------------------------

    @Override
    public void onConnect(ConversationContext<APDU> context) {
        channel = context.getChannel();
        linkLayer = new Iec104LinkLayer(new Iec104LinkLayer.Transport() {
            @Override
            public void send(APDU apdu) {
                channel.writeAndFlush(apdu).addListener(future -> {
                    if (!future.isSuccess()) {
                        LOGGER.warn("Failed to send {}", apdu.getClass().getSimpleName(), future.cause());
                    }
                });
            }

            @Override
            public void close(String reason) {
                channel.close();
            }
        }, configuration.getT1Millis(), configuration.getT2Millis(), configuration.getT3Millis(),
            configuration.getK(), configuration.getW());
        channel.closeFuture().addListener(future -> runOnEventLoop(this::onConnectionClosed));

        // Step 1: test frame, to see that the other side speaks IEC 104 at all.
        Duration timeout = Duration.ofMillis(configuration.getRequestTimeout());
        context.sendRequest(new APDUUFormatTestFrameActivation(TESTFR_ACT_COMMAND))
            .expectResponse(APDU.class, timeout)
            .onTimeout(e -> context.getChannel().pipeline().fireExceptionCaught(e))
            .onError((p, e) -> context.getChannel().pipeline().fireExceptionCaught(e))
            .only(APDUUFormatTestFrameConfirmation.class)
            .handle(testFrameConfirmation -> startDataTransfer(context, timeout));
    }

    /**
     * Step 2 of the connection setup: STARTDT. From its confirmation on the station may send
     * I-frames, and so may we.
     */
    private void startDataTransfer(ConversationContext<APDU> context, Duration timeout) {
        context.sendRequest(new APDUUFormatStartDataTransferActivation(STARTDT_ACT_COMMAND))
            .expectResponse(APDU.class, timeout)
            .onTimeout(e -> context.getChannel().pipeline().fireExceptionCaught(e))
            .onError((p, e) -> context.getChannel().pipeline().fireExceptionCaught(e))
            .only(APDUUFormatStartDataTransferConfirmation.class)
            .handle(startDataTransferConfirmation -> {
                linkLayer.start(System.nanoTime());
                tickTask = channel.eventLoop().scheduleAtFixedRate(
                    () -> linkLayer.onTick(System.nanoTime()),
                    TICK_INTERVAL_MILLIS, TICK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                LOGGER.debug("IEC 104 data transfer started with {}", configuration);
                context.fireConnected();
                if (configuration.isGeneralInterrogation()) {
                    sendGeneralInterrogation();
                } else {
                    initialInterrogation.complete(null);
                }
            });
    }

    @Override
    public void close(ConversationContext<APDU> context) {
        runOnEventLoop(this::onConnectionClosed);
    }

    /**
     * Cleans up after the connection is gone, whoever closed it. Idempotent.
     * Runs on the event loop.
     */
    private void onConnectionClosed() {
        if (connectionClosed) {
            return;
        }
        connectionClosed = true;
        if (tickTask != null) {
            tickTask.cancel(false);
        }
        if (linkLayer != null) {
            linkLayer.stop();
        }
        initialInterrogation.complete(null);
        pendingWrites.values().forEach(queue -> queue.forEach(f -> f.complete(PlcResponseCode.REMOTE_ERROR)));
        pendingWrites.clear();
        pendingReads.values().forEach(queue -> queue.forEach(f -> f.complete(item(PlcResponseCode.REMOTE_ERROR, null))));
        pendingReads.clear();
        LOGGER.debug("IEC 104 connection closed");
    }

    // ---------------------------------------------------------------------------------------
    // Incoming frames
    // ---------------------------------------------------------------------------------------

    @Override
    protected void decode(ConversationContext<APDU> context, APDU msg) throws Exception {
        if (linkLayer == null) {
            return;
        }
        ASDU asdu = linkLayer.onFrameReceived(msg, System.nanoTime());
        if (asdu != null) {
            try {
                onAsdu(asdu);
            } catch (RuntimeException e) {
                // One ASDU we cannot handle must not take the connection down.
                LOGGER.warn("Failed to process ASDU {}", asdu, e);
            }
        }
    }

    /**
     * Dispatches the payload of a received I-frame by its type identification.
     */
    private void onAsdu(ASDU asdu) {
        TypeIdentification type = asdu.getTypeIdentification();
        switch (type) {
            case INTERROGATION_COMMAND:
                onInterrogationAnswer(asdu);
                return;
            case END_OF_INITIALISATION:
                onEndOfInitialisation(asdu);
                return;
            case READ_COMMAND:
                // A read command only comes back when the station rejects it; a successful
                // read is answered with the monitoring data itself.
                onReadRejected(asdu);
                return;
            default:
                break;
        }
        int typeValue = type.getValue();
        if (typeValue >= 1 && typeValue <= LAST_MONITORING_TYPE) {
            onMonitoringData(asdu);
        } else if (typeValue >= FIRST_COMMAND_TYPE && typeValue <= LAST_COMMAND_TYPE) {
            onCommandAnswer(asdu);
        } else {
            LOGGER.debug("Ignoring ASDU of type {} with cause {}", type, asdu.getCauseOfTransmission());
        }
    }

    /**
     * Monitoring data: update the cache, answer pending read commands, notify subscribers.
     */
    private void onMonitoringData(ASDU asdu) {
        TypeIdentification type = asdu.getTypeIdentification();
        CauseOfTransmission cause = asdu.getCauseOfTransmission();
        int asduAddress = asdu.getAsduAddressField();
        for (InformationObject object : asdu.getInformationObjects()) {
            int objectAddress = object.getAddress();
            PlcValue value;
            try {
                value = Iec608705104TagParser.parseTag(object, type);
            } catch (RuntimeException e) {
                LOGGER.debug("Cannot convert {} at {}/{}: {}", type, asduAddress, objectAddress, e.getMessage());
                continue;
            }
            if (value == null) {
                LOGGER.debug("No value conversion for {} at {}/{}", type, asduAddress, objectAddress);
                continue;
            }
            Instant timestamp = timestampOf(object);
            addMetadata(value, type, cause, timestamp);
            valueCache.put(asduAddress, objectAddress, value, timestamp);
            if (cause == CauseOfTransmission.INTERROGATION_OR_INTERROGATED_REQ) {
                completeAll(pendingReads.remove(new ObjectKey(asduAddress, objectAddress)), item(PlcResponseCode.OK, value));
            }
            publish(asduAddress, objectAddress, value, timestamp);
        }
    }

    /**
     * The station's answer to one of our commands (write).
     */
    private void onCommandAnswer(ASDU asdu) {
        CauseOfTransmission cause = asdu.getCauseOfTransmission();
        if (cause == CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM) {
            // Sent by some stations after the command has been executed. The write was
            // already answered with the ACT_CON.
            return;
        }
        PlcResponseCode code = responseCodeOf(asdu);
        if (code == null) {
            LOGGER.debug("Ignoring command ASDU {} with cause {}", asdu.getTypeIdentification(), cause);
            return;
        }
        for (InformationObject object : asdu.getInformationObjects()) {
            CommandKey key = new CommandKey(asdu.getTypeIdentification(), asdu.getAsduAddressField(), object.getAddress());
            CompletableFuture<PlcResponseCode> pending = pollPending(pendingWrites, key);
            if (pending == null) {
                LOGGER.debug("Unexpected command answer {} for {}", cause, key);
            } else {
                pending.complete(code);
            }
        }
    }

    private void onReadRejected(ASDU asdu) {
        PlcResponseCode code = responseCodeOf(asdu);
        PlcResponseCode failure = code == null || code == PlcResponseCode.OK ? PlcResponseCode.NOT_FOUND : code;
        for (InformationObject object : asdu.getInformationObjects()) {
            ObjectKey key = new ObjectKey(asdu.getAsduAddressField(), object.getAddress());
            completeAll(pendingReads.remove(key), item(failure, null));
            LOGGER.debug("Station rejected read of {} with cause {}", key, asdu.getCauseOfTransmission());
        }
    }

    private void onInterrogationAnswer(ASDU asdu) {
        CauseOfTransmission cause = asdu.getCauseOfTransmission();
        if (cause == CauseOfTransmission.CONFIRMATION_ACTIVATION_ACTCON && !asdu.getNegative()) {
            LOGGER.debug("General interrogation of {} confirmed", asdu.getAsduAddressField());
        } else if (cause == CauseOfTransmission.TERMINATION_ACTIVATION_ACTTERM) {
            LOGGER.debug("General interrogation of {} finished, {} values cached",
                asdu.getAsduAddressField(), valueCache.size());
            initialInterrogation.complete(null);
        } else {
            LOGGER.warn("General interrogation of {} rejected by the station (cause {}, negative {})",
                asdu.getAsduAddressField(), cause, asdu.getNegative());
            initialInterrogation.complete(null);
        }
    }

    /**
     * The station (re)started. Its values may have changed while it was down, so we ask for
     * all of them again.
     */
    private void onEndOfInitialisation(ASDU asdu) {
        LOGGER.info("Station {} reports end of initialization", asdu.getAsduAddressField());
        if (configuration.isGeneralInterrogation()) {
            sendGeneralInterrogation();
        }
    }

    /**
     * Maps the cause of transmission of an answer to one of our requests to a PLC4X response
     * code.
     *
     * @return the code, or {@code null} if the cause is not an answer to a request.
     */
    private static PlcResponseCode responseCodeOf(ASDU asdu) {
        switch (asdu.getCauseOfTransmission()) {
            case CONFIRMATION_ACTIVATION_ACTCON:
            case INTERROGATION_OR_INTERROGATED_REQ:
                return asdu.getNegative() ? PlcResponseCode.REMOTE_ERROR : PlcResponseCode.OK;
            case TYPE_IDENTIFICATION_UNKNOWN_UKNOWN_TYPE:
                return PlcResponseCode.INVALID_DATATYPE;
            case CAUSE_UNKNOWN_UKNOWN_CAUSE:
                return PlcResponseCode.REMOTE_ERROR;
            case ASDU_ADDRESS_UNKNOWN_UNKNOWN_ASDU_ADDRESS:
            case INFORMATION_OBJECT_ADDRESS_UNKNOWN_UNKNOWN_OBJECT_ADDRESS:
                return PlcResponseCode.NOT_FOUND;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------------

    /**
     * Reads the current value of each tag. How depends on {@code read-mode}, see
     * {@link ReadMode}. Every tag must address exactly one object (no wildcards).
     */
    @Override
    public CompletableFuture<PlcReadResponse> read(PlcReadRequest readRequest) {
        Map<String, CompletableFuture<PlcResponseItem<PlcValue>>> items = new LinkedHashMap<>();
        ReadMode mode = configuration.getReadMode();
        for (String tagName : readRequest.getTagNames()) {
            PlcTag tag = readRequest.getTag(tagName);
            if (!(tag instanceof Iec608705104Tag) || ((Iec608705104Tag) tag).isWildcard()) {
                items.put(tagName, CompletableFuture.completedFuture(item(PlcResponseCode.INVALID_ADDRESS, null)));
            } else if (mode == ReadMode.CACHE) {
                items.put(tagName, readFromCache((Iec608705104Tag) tag));
            } else {
                items.put(tagName, readWithCommand((Iec608705104Tag) tag));
            }
        }
        return CompletableFuture.allOf(items.values().toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, PlcResponseItem<PlcValue>> values = new LinkedHashMap<>();
                items.forEach((name, future) -> values.put(name, future.join()));
                return new DefaultPlcReadResponse(readRequest, values);
            });
    }

    private CompletableFuture<PlcResponseItem<PlcValue>> readFromCache(Iec608705104Tag tag) {
        return initialInterrogation.thenApply(ignored -> valueCache.get(tag.getAsduAddress(), tag.getObjectAddress())
            .map(entry -> item(PlcResponseCode.OK, entry.value()))
            .orElseGet(() -> item(PlcResponseCode.NOT_FOUND, null)));
    }

    private CompletableFuture<PlcResponseItem<PlcValue>> readWithCommand(Iec608705104Tag tag) {
        CompletableFuture<PlcResponseItem<PlcValue>> result = new CompletableFuture<>();
        runOnEventLoop(() -> {
            if (!canSend()) {
                result.complete(item(PlcResponseCode.REMOTE_ERROR, null));
                return;
            }
            ObjectKey key = new ObjectKey(tag.getAsduAddress(), tag.getObjectAddress());
            addPending(pendingReads, key, result, item(PlcResponseCode.REMOTE_ERROR, null), "read of " + tag.getAddressString());
            linkLayer.sendAsdu(Iec104Commands.createRead(tag, configuration.getOriginatorAddress()), System.nanoTime());
        });
        return result;
    }

    // ---------------------------------------------------------------------------------------
    // Write
    // ---------------------------------------------------------------------------------------

    /**
     * Writes each value with a command (direct execute) and waits for the station's
     * activation confirmation. Which command is used is decided by
     * {@link Iec104Commands#resolveDataType}.
     *
     * <p>Response codes: OK on positive ACT_CON, REMOTE_ERROR on negative ACT_CON or no
     * answer within {@code request-timeout}, NOT_FOUND / INVALID_DATATYPE when the station
     * answers with unknown address / unknown type, INVALID_ADDRESS for wildcard addresses,
     * INVALID_DATA for values the command cannot carry.
     */
    @Override
    public CompletableFuture<PlcWriteResponse> write(PlcWriteRequest writeRequest) {
        Map<String, CompletableFuture<PlcResponseCode>> codes = new LinkedHashMap<>();
        for (String tagName : writeRequest.getTagNames()) {
            PlcTag plcTag = writeRequest.getTag(tagName);
            if (!(plcTag instanceof Iec608705104Tag) || ((Iec608705104Tag) plcTag).isWildcard()) {
                codes.put(tagName, CompletableFuture.completedFuture(PlcResponseCode.INVALID_ADDRESS));
                continue;
            }
            Iec608705104Tag tag = (Iec608705104Tag) plcTag;
            PlcValue value = writeRequest.getPlcValue(tagName);
            ASDU command;
            try {
                Iec608705104DataType dataType = Iec104Commands.resolveDataType(tag, value);
                command = Iec104Commands.createWrite(tag, dataType, value, configuration.getOriginatorAddress());
            } catch (RuntimeException e) {
                LOGGER.debug("Cannot write {} to {}: {}", value, tag.getAddressString(), e.getMessage());
                codes.put(tagName, CompletableFuture.completedFuture(PlcResponseCode.INVALID_DATA));
                continue;
            }
            codes.put(tagName, sendCommand(tag, command));
        }
        return CompletableFuture.allOf(codes.values().toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, PlcResponseCode> result = new LinkedHashMap<>();
                codes.forEach((name, future) -> result.put(name, future.join()));
                return new DefaultPlcWriteResponse(writeRequest, result);
            });
    }

    private CompletableFuture<PlcResponseCode> sendCommand(Iec608705104Tag tag, ASDU command) {
        CompletableFuture<PlcResponseCode> result = new CompletableFuture<>();
        runOnEventLoop(() -> {
            if (!canSend()) {
                result.complete(PlcResponseCode.REMOTE_ERROR);
                return;
            }
            CommandKey key = new CommandKey(command.getTypeIdentification(), tag.getAsduAddress(), tag.getObjectAddress());
            addPending(pendingWrites, key, result, PlcResponseCode.REMOTE_ERROR, "write to " + tag.getAddressString());
            linkLayer.sendAsdu(command, System.nanoTime());
        });
        return result;
    }

    // ---------------------------------------------------------------------------------------
    // Subscriptions
    // ---------------------------------------------------------------------------------------

    /**
     * Creates one handle per tag. Nothing is sent to the station: it reports everything
     * anyway, the handles only filter.
     *
     * <p>Fork: consumers given in the request ({@code setConsumer(...)} on the builder, or
     * per tag) are registered right here. In 0.13.1 nothing registered them, so they never
     * received events unless the user also called {@code handle.register(...)}. Use one of
     * the two ways, not both, or every event is delivered twice.
     */
    @Override
    public CompletableFuture<PlcSubscriptionResponse> subscribe(PlcSubscriptionRequest subscriptionRequest) {
        Map<String, PlcResponseItem<PlcSubscriptionHandle>> values = new LinkedHashMap<>();
        List<PlcSubscriptionHandle> handles = new ArrayList<>();
        for (String tagName : subscriptionRequest.getTagNames()) {
            PlcTag tag = subscriptionRequest.getTag(tagName);
            if (tag instanceof DefaultPlcSubscriptionTag) {
                tag = ((DefaultPlcSubscriptionTag) tag).getTag();
            }
            if (tag instanceof Iec608705104Tag) {
                Iec608705104SubscriptionHandle handle = new Iec608705104SubscriptionHandle(this, tagName, (Iec608705104Tag) tag);
                handles.add(handle);
                values.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.OK, handle));
            } else {
                values.put(tagName, new DefaultPlcResponseItem<>(PlcResponseCode.INVALID_ADDRESS, null));
            }
        }
        if (subscriptionRequest instanceof DefaultPlcSubscriptionRequest) {
            DefaultPlcSubscriptionRequest request = (DefaultPlcSubscriptionRequest) subscriptionRequest;
            if (request.getConsumer() != null && !handles.isEmpty()) {
                register(request.getConsumer(), handles);
            }
            for (PlcSubscriptionHandle handle : handles) {
                Consumer<PlcSubscriptionEvent> tagConsumer =
                    request.getTagConsumer(((Iec608705104SubscriptionHandle) handle).getTagName());
                if (tagConsumer != null) {
                    register(tagConsumer, List.of(handle));
                }
            }
        }
        return CompletableFuture.completedFuture(new DefaultPlcSubscriptionResponse(subscriptionRequest, values));
    }

    @Override
    public PlcConsumerRegistration register(Consumer<PlcSubscriptionEvent> consumer, Collection<PlcSubscriptionHandle> handles) {
        DefaultPlcConsumerRegistration registration =
            new DefaultPlcConsumerRegistration(this, consumer, handles.toArray(new PlcSubscriptionHandle[0]));
        consumers.put(registration, consumer);
        return registration;
    }

    @Override
    public void unregister(PlcConsumerRegistration registration) {
        consumers.remove(registration);
    }

    /**
     * Delivers one received value to every consumer with a matching subscription. One
     * event per consumer, containing the value once under the name of every matching tag.
     */
    private void publish(int asduAddress, int objectAddress, PlcValue value, Instant timestamp) {
        if (consumers.isEmpty()) {
            return;
        }
        Iec608705104Tag source = new Iec608705104Tag(asduAddress, objectAddress);
        for (Map.Entry<DefaultPlcConsumerRegistration, Consumer<PlcSubscriptionEvent>> entry : consumers.entrySet()) {
            Map<String, PlcTag> tags = new LinkedHashMap<>();
            Map<String, PlcResponseItem<PlcValue>> values = new LinkedHashMap<>();
            for (PlcSubscriptionHandle handle : entry.getKey().getSubscriptionHandles()) {
                if (handle instanceof Iec608705104SubscriptionHandle
                    && ((Iec608705104SubscriptionHandle) handle).matches(asduAddress, objectAddress)) {
                    String name = ((Iec608705104SubscriptionHandle) handle).getTagName();
                    tags.put(name, source);
                    values.put(name, item(PlcResponseCode.OK, value));
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            try {
                entry.getValue().accept(new Iec608705104PlcSubscriptionEvent(timestamp, tags, values));
            } catch (RuntimeException e) {
                LOGGER.warn("Subscription consumer failed", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void sendGeneralInterrogation() {
        if (!canSend()) {
            initialInterrogation.complete(null);
            return;
        }
        LOGGER.debug("Sending general interrogation to {}", configuration.getInterrogationAsduAddress());
        linkLayer.sendAsdu(Iec104Commands.createGeneralInterrogation(
            configuration.getInterrogationAsduAddress(), configuration.getOriginatorAddress()), System.nanoTime());
        channel.eventLoop().schedule(() -> {
            if (initialInterrogation.complete(null)) {
                LOGGER.warn("General interrogation not finished within {} ms, reads use the values received so far",
                    configuration.getInterrogationTimeoutMillis());
            }
        }, configuration.getInterrogationTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    private boolean canSend() {
        return !connectionClosed && linkLayer != null && linkLayer.isRunning();
    }

    /**
     * Runs a task on the event loop of the connection, or right away if already there.
     * Without a channel (never connected) the task runs on the calling thread; it will then
     * find {@link #canSend()} false.
     */
    private void runOnEventLoop(Runnable task) {
        if (channel == null || channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    /**
     * Registers a request waiting for an answer and arms its timeout.
     *
     * @param pending   the map of pending requests of this kind.
     * @param key       what the answer will be matched by.
     * @param future    completed by the answer.
     * @param onTimeout the result if no answer arrives within {@code request-timeout}.
     * @param what      description for the log.
     */
    private <K, V> void addPending(Map<K, Deque<CompletableFuture<V>>> pending, K key, CompletableFuture<V> future,
                                   V onTimeout, String what) {
        pending.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(future);
        channel.eventLoop().schedule(() -> {
            if (future.complete(onTimeout)) {
                LOGGER.warn("No answer from the station to the {} within {} ms", what, configuration.getRequestTimeout());
                Deque<CompletableFuture<V>> queue = pending.get(key);
                if (queue != null) {
                    queue.remove(future);
                    if (queue.isEmpty()) {
                        pending.remove(key);
                    }
                }
            }
        }, configuration.getRequestTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * @return the oldest request still waiting for this key, removed from the map, or
     * {@code null}.
     */
    private static <K, V> CompletableFuture<V> pollPending(Map<K, Deque<CompletableFuture<V>>> pending, K key) {
        Deque<CompletableFuture<V>> queue = pending.get(key);
        if (queue == null) {
            return null;
        }
        CompletableFuture<V> first = queue.pollFirst();
        if (queue.isEmpty()) {
            pending.remove(key);
        }
        return first;
    }

    private static <V> void completeAll(Deque<CompletableFuture<V>> futures, V value) {
        if (futures != null) {
            futures.forEach(future -> future.complete(value));
        }
    }

    private static PlcResponseItem<PlcValue> item(PlcResponseCode code, PlcValue value) {
        return new DefaultPlcResponseItem<>(code, value);
    }

    /**
     * Attaches cause of transmission, type identification and timestamp to a value, next to
     * the quality bits the value parser already added.
     */
    private static void addMetadata(PlcValue value, TypeIdentification type, CauseOfTransmission cause, Instant timestamp) {
        if (value instanceof PlcValueAdapter) {
            PlcValueAdapter adapter = (PlcValueAdapter) value;
            adapter.addMetaData(METADATA_CAUSE_OF_TRANSMISSION, PlcUSINT.of(cause.getValue()));
            adapter.addMetaData(METADATA_TYPE_IDENTIFICATION, PlcUSINT.of(type.getValue()));
            adapter.addMetaData(METADATA_TIMESTAMP, PlcLDATE_AND_TIME.of(LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC)));
        }
    }

    /**
     * @return the time tag of an information object, or now if it has none or an invalid one.
     */
    static Instant timestampOf(InformationObject object) {
        try {
            if (object instanceof InformationObjectWithTreeByteTime) {
                return convertCp24Time2a(((InformationObjectWithTreeByteTime) object).getCp24Time2a());
            }
            if (object instanceof InformationObjectWithSevenByteTime) {
                return convertCp56Time2a(((InformationObjectWithSevenByteTime) object).getCp56Time2a());
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Invalid time tag in information object {}: {}", object.getAddress(), e.getMessage());
        }
        return Instant.now();
    }

    /**
     * CP24Time2a only carries minutes and milliseconds; the rest is taken from the local
     * clock (same as upstream PLC4X).
     */
    static Instant convertCp24Time2a(ThreeOctetBinaryTime time) {
        LocalDateTime now = LocalDateTime.now();
        return LocalDateTime.of(now.getYear(), now.getMonthValue(), now.getDayOfMonth(), now.getHour(),
                time.getMinutes(), time.getMilliseconds() / 1000, (time.getMilliseconds() % 1000) * 1_000_000)
            .atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * CP56Time2a. Interpreted as UTC, like upstream PLC4X; the summer time bit is ignored
     * for that reason. Stations configured for local time will appear shifted by the UTC
     * offset, see README.md.
     */
    static Instant convertCp56Time2a(SevenOctetBinaryTime time) {
        return LocalDateTime.of(2000 + time.getYear(), time.getMonth(), time.getDay(), time.getHour(),
                time.getMinutes(), time.getMilliseconds() / 1000, (time.getMilliseconds() % 1000) * 1_000_000)
            .toInstant(ZoneOffset.UTC);
    }

    /**
     * @return a copy of the names of the consumers' subscriptions, for diagnostics.
     */
    List<String> getSubscribedTagNames() {
        List<String> names = new ArrayList<>();
        for (DefaultPlcConsumerRegistration registration : consumers.keySet()) {
            for (PlcSubscriptionHandle handle : registration.getSubscriptionHandles()) {
                if (handle instanceof Iec608705104SubscriptionHandle) {
                    names.add(((Iec608705104SubscriptionHandle) handle).getTagName());
                }
            }
        }
        return names;
    }

}
