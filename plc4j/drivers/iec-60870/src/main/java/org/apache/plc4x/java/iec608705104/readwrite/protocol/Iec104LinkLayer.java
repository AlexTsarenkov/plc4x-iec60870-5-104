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

import org.apache.plc4x.java.iec608705104.readwrite.APDU;
import org.apache.plc4x.java.iec608705104.readwrite.APDUIFormat;
import org.apache.plc4x.java.iec608705104.readwrite.APDUSFormat;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameActivation;
import org.apache.plc4x.java.iec608705104.readwrite.APDUUFormatTestFrameConfirmation;
import org.apache.plc4x.java.iec608705104.readwrite.ASDU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * The transmission procedures of IEC 60870-5-104 (section 5 of the standard) for the
 * controlling station (the client): numbered I-frames, acknowledgements and the
 * timers t1, t2 and t3.
 *
 * <h2>Why a class of its own</h2>
 * This is the part of the protocol that is easiest to get subtly wrong and hardest to
 * debug against a real station, so it is kept free of Netty and PLC4X: it gets frames and
 * the current time passed in and sends frames through {@link Transport}. That makes it
 * testable with a fake clock, see {@code Iec104LinkLayerTest}.
 *
 * <h2>Threading</h2>
 * Not thread-safe. The protocol calls it only from the Netty event loop of the connection.
 *
 * <h2>Sequence numbers</h2>
 * Every I-frame carries N(S), its own number, and N(R), the number of the next I-frame the
 * sender expects from the other side, which acknowledges everything before it. Both are 15
 * bit counters that wrap from 32767 to 0. On the wire they sit in the upper 15 bits of a 16
 * bit field ({@link #toWire(int)}, {@link #fromWire(int)}).
 * <ul>
 *   <li>V(S) - {@link #getSendSequence()}: N(S) of the next I-frame we send.</li>
 *   <li>V(R) - {@link #getReceiveSequence()}: N(S) we expect in the next I-frame received.</li>
 * </ul>
 *
 * <h2>What the timers do here</h2>
 * <ul>
 *   <li><b>t1</b> - an I-frame we sent, or a TESTFR act, has not been acknowledged within
 *       t1: the connection is considered broken and closed.</li>
 *   <li><b>t2</b> - we received I-frames but have not acknowledged them: at the latest t2
 *       after the first unacknowledged one an S-frame is sent. Without this the station's
 *       own t1 runs out and it drops the connection whenever traffic is low (the original
 *       0.13.1 driver had exactly this bug).</li>
 *   <li><b>t3</b> - nothing received for t3: a TESTFR act is sent, which the station must
 *       confirm within t1.</li>
 * </ul>
 * Additionally at most {@code k} sent I-frames may be unacknowledged; further ASDUs wait
 * in a queue. After {@code w} received I-frames an S-frame is sent right away.
 *
 * <h2>Deviation from the standard</h2>
 * A gap in the received N(S) is logged and skipped instead of closing the connection, see
 * {@code onIFrame}. An invalid N(R) and expired t1 do close the connection.
 *
 * <p>Timers are checked by {@link #onTick(long)}, which the protocol calls periodically
 * (every {@code TICK_INTERVAL} ms). The precision of all timers is therefore one tick,
 * which is far below what the standard requires.
 */
public class Iec104LinkLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iec104LinkLayer.class);

    /** Sequence numbers are 15 bits wide. */
    static final int SEQUENCE_MODULUS = 1 << 15;

    /** Marker for "timer not running". */
    private static final long NOT_RUNNING = Long.MIN_VALUE;

    /** Control field value identifying an S-frame. */
    private static final int S_FORMAT_COMMAND = 0x01;

    /** Control field value of a TESTFR act U-frame. */
    private static final int TESTFR_ACT_COMMAND = 0x43;

    /** Control field value of a TESTFR con U-frame. */
    private static final int TESTFR_CON_COMMAND = 0x83;

    /**
     * Where the link layer sends frames to and reports fatal errors.
     */
    public interface Transport {

        /**
         * Sends a frame. Must not block.
         *
         * @param apdu the frame.
         */
        void send(APDU apdu);

        /**
         * The connection is broken from the protocol's point of view and must be closed.
         * Called at most once.
         *
         * @param reason human-readable description, ends up in the log.
         */
        void close(String reason);
    }

    /**
     * An I-frame we sent that the station has not acknowledged yet.
     *
     * @param sequence its N(S).
     * @param sentAt   {@link System#nanoTime()} when it was sent.
     */
    private record SentFrame(int sequence, long sentAt) {
    }

    private final Transport transport;
    private final long t1Nanos;
    private final long t2Nanos;
    private final long t3Nanos;
    private final int k;
    private final int w;

    /** V(S). */
    private int sendSequence;
    /** V(R). */
    private int receiveSequence;
    /** Sent and not yet acknowledged I-frames, oldest first. Their numbers are consecutive. */
    private final Deque<SentFrame> unacknowledged = new ArrayDeque<>();
    /** ASDUs waiting because k frames are unacknowledged. */
    private final Deque<ASDU> sendQueue = new ArrayDeque<>();
    /** I-frames received since we last acknowledged. */
    private int receivedSinceAcknowledgement;
    /** When the t2 acknowledgement is due, or {@link #NOT_RUNNING}. */
    private long acknowledgementDue = NOT_RUNNING;
    /** When the last frame of any kind was received, for t3. */
    private long lastReceived;
    /** When the confirmation of our TESTFR act is due (t1), or {@link #NOT_RUNNING}. */
    private long testFrameConfirmationDue = NOT_RUNNING;

    private boolean started;
    private boolean closed;

    /**
     * @param transport where frames go.
     * @param t1Millis  timer t1 in milliseconds.
     * @param t2Millis  timer t2 in milliseconds.
     * @param t3Millis  timer t3 in milliseconds.
     * @param k         maximum number of unacknowledged sent I-frames.
     * @param w         acknowledge after at most this many received I-frames.
     */
    public Iec104LinkLayer(Transport transport, long t1Millis, long t2Millis, long t3Millis, int k, int w) {
        this.transport = transport;
        this.t1Nanos = TimeUnit.MILLISECONDS.toNanos(t1Millis);
        this.t2Nanos = TimeUnit.MILLISECONDS.toNanos(t2Millis);
        this.t3Nanos = TimeUnit.MILLISECONDS.toNanos(t3Millis);
        this.k = k;
        this.w = w;
    }

    /**
     * Starts data transfer. Called once STARTDT has been confirmed; both sequence numbers
     * start at 0 on every new connection.
     *
     * @param now current {@link System#nanoTime()}.
     */
    public void start(long now) {
        sendSequence = 0;
        receiveSequence = 0;
        unacknowledged.clear();
        sendQueue.clear();
        receivedSinceAcknowledgement = 0;
        acknowledgementDue = NOT_RUNNING;
        testFrameConfirmationDue = NOT_RUNNING;
        lastReceived = now;
        started = true;
        closed = false;
    }

    /**
     * Stops the link layer without closing the transport, for an orderly shutdown.
     */
    public void stop() {
        closed = true;
        sendQueue.clear();
    }

    /**
     * @return {@code true} between {@link #start(long)} and the connection being closed.
     */
    public boolean isRunning() {
        return started && !closed;
    }

    /**
     * Sends an ASDU in an I-frame, or queues it if k frames are unacknowledged.
     *
     * @param asdu the ASDU.
     * @param now  current {@link System#nanoTime()}.
     * @throws IllegalStateException if data transfer is not running.
     */
    public void sendAsdu(ASDU asdu, long now) {
        if (!isRunning()) {
            throw new IllegalStateException("IEC 104 data transfer is not running");
        }
        sendQueue.add(asdu);
        flushSendQueue(now);
    }

    /**
     * Handles a received frame.
     *
     * @param apdu the frame.
     * @param now  current {@link System#nanoTime()}.
     * @return the ASDU of an I-frame, for the protocol to process, or {@code null} for every
     * other kind of frame (and for an I-frame that broke the connection).
     */
    public ASDU onFrameReceived(APDU apdu, long now) {
        if (!isRunning()) {
            LOGGER.debug("Ignoring {} received while data transfer is not running", apdu.getClass().getSimpleName());
            return null;
        }
        lastReceived = now;

        if (apdu instanceof APDUIFormat) {
            return onIFrame((APDUIFormat) apdu, now);
        }
        if (apdu instanceof APDUSFormat) {
            acknowledge(fromWire(((APDUSFormat) apdu).getReceiveSequenceNo()), now);
            return null;
        }
        if (apdu instanceof APDUUFormatTestFrameActivation) {
            transport.send(new APDUUFormatTestFrameConfirmation(TESTFR_CON_COMMAND));
            return null;
        }
        if (apdu instanceof APDUUFormatTestFrameConfirmation) {
            testFrameConfirmationDue = NOT_RUNNING;
            return null;
        }
        // STARTDT/STOPDT confirmations and activations from the station: nothing to do once
        // data transfer runs. A controlled station is not supposed to send activations.
        LOGGER.debug("Ignoring {}", apdu.getClass().getSimpleName());
        return null;
    }

    private ASDU onIFrame(APDUIFormat frame, long now) {
        int sendSequenceOfFrame = fromWire(frame.getCommand());
        if (sendSequenceOfFrame != receiveSequence) {
            // Deliberate deviation from the standard, which demands closing the connection.
            // Over TCP frames are not lost, so in practice a gap means that the codec
            // received a frame it could not parse (an ASDU type or layout the generated
            // parser does not support) and dropped it. The station considers that frame
            // delivered, so we continue after it instead of reconnecting forever.
            LOGGER.warn("I-frame sequence gap: expected N(S)={} but got N(S)={}; {} frame(s) could not be parsed",
                receiveSequence, sendSequenceOfFrame, distance(receiveSequence, sendSequenceOfFrame));
        }
        receiveSequence = next(sendSequenceOfFrame);
        if (!acknowledge(fromWire(frame.getReceiveSequenceNo()), now)) {
            return null;
        }
        receivedSinceAcknowledgement++;
        if (receivedSinceAcknowledgement >= w) {
            sendSupervisoryFrame();
        } else if (acknowledgementDue == NOT_RUNNING) {
            acknowledgementDue = now + t2Nanos;
        }
        return frame.getAsdu();
    }

    /**
     * Processes an N(R) received from the station: every I-frame we sent with a number
     * before it is acknowledged.
     *
     * @return {@code false} if the N(R) was invalid and the connection got closed.
     */
    private boolean acknowledge(int receiveSequenceOfStation, long now) {
        int oldestUnacknowledged = unacknowledged.isEmpty() ? sendSequence : unacknowledged.peekFirst().sequence();
        int acknowledgedCount = distance(oldestUnacknowledged, receiveSequenceOfStation);
        if (acknowledgedCount > unacknowledged.size()) {
            fail("Invalid N(R)=" + receiveSequenceOfStation + " from station: outside "
                + oldestUnacknowledged + ".." + sendSequence);
            return false;
        }
        for (int i = 0; i < acknowledgedCount; i++) {
            unacknowledged.pollFirst();
        }
        if (acknowledgedCount > 0) {
            flushSendQueue(now);
        }
        return true;
    }

    /**
     * Checks the timers. Called periodically by the protocol.
     *
     * @param now current {@link System#nanoTime()}.
     */
    public void onTick(long now) {
        if (!isRunning()) {
            return;
        }
        SentFrame oldest = unacknowledged.peekFirst();
        if (oldest != null && now - oldest.sentAt() >= t1Nanos) {
            fail("t1 expired: I-frame N(S)=" + oldest.sequence() + " not acknowledged by the station");
            return;
        }
        if (testFrameConfirmationDue != NOT_RUNNING && now - testFrameConfirmationDue >= 0) {
            fail("t1 expired: TESTFR act not confirmed by the station");
            return;
        }
        if (acknowledgementDue != NOT_RUNNING && now - acknowledgementDue >= 0) {
            sendSupervisoryFrame();
        }
        if (testFrameConfirmationDue == NOT_RUNNING && now - lastReceived >= t3Nanos) {
            LOGGER.debug("Nothing received for t3, sending TESTFR act");
            transport.send(new APDUUFormatTestFrameActivation(TESTFR_ACT_COMMAND));
            testFrameConfirmationDue = now + t1Nanos;
        }
    }

    private void flushSendQueue(long now) {
        while (!sendQueue.isEmpty() && unacknowledged.size() < k) {
            ASDU asdu = sendQueue.pollFirst();
            int sequence = sendSequence;
            transport.send(new APDUIFormat(toWire(sequence), toWire(receiveSequence), asdu));
            unacknowledged.addLast(new SentFrame(sequence, now));
            sendSequence = next(sequence);
            // The I-frame carried N(R) = V(R), which acknowledges everything received so far.
            receivedSinceAcknowledgement = 0;
            acknowledgementDue = NOT_RUNNING;
        }
    }

    private void sendSupervisoryFrame() {
        transport.send(new APDUSFormat(S_FORMAT_COMMAND, toWire(receiveSequence)));
        receivedSinceAcknowledgement = 0;
        acknowledgementDue = NOT_RUNNING;
    }

    private void fail(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        sendQueue.clear();
        LOGGER.warn("Closing IEC 104 connection: {}", reason);
        transport.close(reason);
    }

    /** @return V(S), the N(S) of the next I-frame we send. */
    public int getSendSequence() {
        return sendSequence;
    }

    /** @return V(R), the N(S) expected in the next I-frame received. */
    public int getReceiveSequence() {
        return receiveSequence;
    }

    /** @return number of sent I-frames not yet acknowledged by the station. */
    public int getUnacknowledgedCount() {
        return unacknowledged.size();
    }

    /** @return number of ASDUs waiting for the send window (k) to open. */
    public int getQueuedCount() {
        return sendQueue.size();
    }

    /**
     * @param sequence a sequence number.
     * @return the following sequence number, wrapping at 2^15.
     */
    static int next(int sequence) {
        return (sequence + 1) % SEQUENCE_MODULUS;
    }

    /**
     * @return how many steps it takes to get from {@code from} to {@code to}, taking the
     * wrap-around into account.
     */
    static int distance(int from, int to) {
        return Math.floorMod(to - from, SEQUENCE_MODULUS);
    }

    /**
     * @param sequence a sequence number.
     * @return the value of the 16 bit control field carrying it (the lowest bit is the
     * frame format bit and stays 0).
     */
    static int toWire(int sequence) {
        return (sequence % SEQUENCE_MODULUS) << 1;
    }

    /**
     * @param field a 16 bit control field as parsed by the generated code.
     * @return the sequence number it carries.
     */
    static int fromWire(int field) {
        return (field >> 1) & (SEQUENCE_MODULUS - 1);
    }

}
