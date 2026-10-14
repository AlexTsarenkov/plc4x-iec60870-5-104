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
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link layer with a fake clock: every test controls "now" explicitly, nothing sleeps.
 */
class Iec104LinkLayerTest {

    private static final long T1 = 15_000;
    private static final long T2 = 10_000;
    private static final long T3 = 20_000;
    private static final int K = 12;
    private static final int W = 8;

    private final List<APDU> sent = new ArrayList<>();
    private final List<String> closeReasons = new ArrayList<>();
    private Iec104LinkLayer linkLayer;

    @BeforeEach
    void setUp() {
        linkLayer = new Iec104LinkLayer(new Iec104LinkLayer.Transport() {
            @Override
            public void send(APDU apdu) {
                sent.add(apdu);
            }

            @Override
            public void close(String reason) {
                closeReasons.add(reason);
            }
        }, T1, T2, T3, K, W);
        linkLayer.start(ms(0));
    }

    @Test
    void sequenceNumberHelpers() {
        assertEquals(0, Iec104LinkLayer.next(32767));
        assertEquals(1, Iec104LinkLayer.distance(32767, 0));
        assertEquals(0, Iec104LinkLayer.distance(5, 5));
        assertEquals(10, Iec104LinkLayer.toWire(5));
        assertEquals(5, Iec104LinkLayer.fromWire(10));
        assertEquals(32767, Iec104LinkLayer.fromWire(0xFFFE));
    }

    @Test
    void sentFramesCarrySequenceNumbers() {
        linkLayer.sendAsdu(asdu(), ms(0));
        linkLayer.sendAsdu(asdu(), ms(0));
        assertEquals(2, sent.size());
        assertEquals(0, Iec104LinkLayer.fromWire(sent.get(0).getCommand()));
        assertEquals(1, Iec104LinkLayer.fromWire(sent.get(1).getCommand()));
        assertEquals(2, linkLayer.getSendSequence());
        assertEquals(2, linkLayer.getUnacknowledgedCount());
    }

    @Test
    void receivedFrameIsAcknowledgedAfterT2() {
        ASDU payload = asdu();
        assertSame(payload, linkLayer.onFrameReceived(iFrame(0, 0, payload), ms(1_000)));
        assertEquals(1, linkLayer.getReceiveSequence());

        linkLayer.onTick(ms(1_000 + T2 - 1));
        assertTrue(sent.isEmpty(), "no acknowledgement before t2");

        linkLayer.onTick(ms(1_000 + T2));
        assertEquals(1, sent.size());
        APDUSFormat ack = (APDUSFormat) sent.get(0);
        assertEquals(1, Iec104LinkLayer.fromWire(ack.getReceiveSequenceNo()));

        // Still below t3 since the last received frame, so nothing else may be sent.
        linkLayer.onTick(ms(1_000 + T2 + 1_000));
        assertEquals(1, sent.size(), "acknowledged only once");
    }

    @Test
    void wReceivedFramesAreAcknowledgedImmediately() {
        for (int i = 0; i < W; i++) {
            linkLayer.onFrameReceived(iFrame(i, 0, asdu()), ms(0));
        }
        assertEquals(1, sent.size());
        assertEquals(W, Iec104LinkLayer.fromWire(((APDUSFormat) sent.get(0)).getReceiveSequenceNo()));
    }

    @Test
    void sendingAnIFrameAcknowledgesReceivedFrames() {
        linkLayer.onFrameReceived(iFrame(0, 0, asdu()), ms(0));
        linkLayer.sendAsdu(asdu(), ms(1));
        APDUIFormat frame = (APDUIFormat) sent.get(0);
        assertEquals(1, Iec104LinkLayer.fromWire(frame.getReceiveSequenceNo()), "N(R) piggybacked");

        linkLayer.onTick(ms(1 + T2));
        assertEquals(1, sent.size(), "no extra S-frame needed");
    }

    @Test
    void windowKLimitsUnacknowledgedFrames() {
        for (int i = 0; i < K + 3; i++) {
            linkLayer.sendAsdu(asdu(), ms(0));
        }
        assertEquals(K, sent.size());
        assertEquals(3, linkLayer.getQueuedCount());

        // The station acknowledges the first two frames.
        linkLayer.onFrameReceived(sFrame(2), ms(10));
        assertEquals(K + 2, sent.size());
        assertEquals(1, linkLayer.getQueuedCount());
        assertEquals(K, linkLayer.getUnacknowledgedCount());
    }

    @Test
    void acknowledgementReleasesFrames() {
        linkLayer.sendAsdu(asdu(), ms(0));
        linkLayer.sendAsdu(asdu(), ms(0));
        linkLayer.onFrameReceived(sFrame(1), ms(10));
        assertEquals(1, linkLayer.getUnacknowledgedCount());
        linkLayer.onFrameReceived(iFrame(0, 2, asdu()), ms(20));
        assertEquals(0, linkLayer.getUnacknowledgedCount());
        assertTrue(closeReasons.isEmpty());
    }

    @Test
    void unacknowledgedFrameClosesAfterT1() {
        linkLayer.sendAsdu(asdu(), ms(0));
        // Keep the line busy so that t3 does not interfere.
        linkLayer.onFrameReceived(new APDUUFormatTestFrameConfirmation(0x83), ms(T1 - 1));
        linkLayer.onTick(ms(T1 - 1));
        assertTrue(closeReasons.isEmpty());
        linkLayer.onTick(ms(T1));
        assertEquals(1, closeReasons.size());
        assertTrue(closeReasons.get(0).contains("t1"));
        assertFalse(linkLayer.isRunning());
    }

    @Test
    void invalidReceiveSequenceClosesTheConnection() {
        linkLayer.sendAsdu(asdu(), ms(0));
        // Acknowledges 3 frames, but only 1 was sent.
        linkLayer.onFrameReceived(sFrame(3), ms(1));
        assertEquals(1, closeReasons.size());
        assertTrue(closeReasons.get(0).contains("N(R)"));
    }

    @Test
    void sequenceGapIsSkippedNotFatal() {
        linkLayer.onFrameReceived(iFrame(0, 0, asdu()), ms(0));
        // Frames 1 and 2 could not be parsed by the codec and never arrived here.
        ASDU payload = asdu();
        assertSame(payload, linkLayer.onFrameReceived(iFrame(3, 0, payload), ms(1)));
        assertEquals(4, linkLayer.getReceiveSequence());
        assertTrue(closeReasons.isEmpty());
    }

    @Test
    void testFrameAfterT3AndCloseWithoutConfirmation() {
        linkLayer.onTick(ms(T3 - 1));
        assertTrue(sent.isEmpty());
        linkLayer.onTick(ms(T3));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0) instanceof APDUUFormatTestFrameActivation);

        linkLayer.onTick(ms(T3 + T1 - 1));
        assertEquals(1, sent.size(), "no second test frame while waiting");
        assertTrue(closeReasons.isEmpty());
        linkLayer.onTick(ms(T3 + T1));
        assertEquals(1, closeReasons.size());
    }

    @Test
    void confirmedTestFrameKeepsTheConnection() {
        linkLayer.onTick(ms(T3));
        linkLayer.onFrameReceived(new APDUUFormatTestFrameConfirmation(0x83), ms(T3 + 100));
        linkLayer.onTick(ms(T3 + T1 + 100));
        assertTrue(closeReasons.isEmpty());
    }

    @Test
    void answersTestFrameOfStation() {
        assertNull(linkLayer.onFrameReceived(new APDUUFormatTestFrameActivation(0x43), ms(0)));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0) instanceof APDUUFormatTestFrameConfirmation);
    }

    @Test
    void sequenceNumbersWrapAround() {
        // Receive 32768 frames: V(R) wraps to 0.
        for (int i = 0; i < Iec104LinkLayer.SEQUENCE_MODULUS; i++) {
            assertNotNull(linkLayer.onFrameReceived(iFrame(i, 0, asdu()), ms(0)));
        }
        assertEquals(0, linkLayer.getReceiveSequence());
        assertNotNull(linkLayer.onFrameReceived(iFrame(0, 0, asdu()), ms(0)));
        assertTrue(closeReasons.isEmpty());
    }

    @Test
    void framesBeforeStartAreIgnored() {
        Iec104LinkLayer notStarted = new Iec104LinkLayer(new Iec104LinkLayer.Transport() {
            @Override
            public void send(APDU apdu) {
                sent.add(apdu);
            }

            @Override
            public void close(String reason) {
                closeReasons.add(reason);
            }
        }, T1, T2, T3, K, W);
        assertNull(notStarted.onFrameReceived(iFrame(0, 0, asdu()), ms(0)));
        notStarted.onTick(ms(T3 * 10));
        assertTrue(sent.isEmpty());
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static ASDU asdu() {
        return Iec104Commands.createRead(new Iec608705104Tag(1, 100), 0);
    }

    private static APDUIFormat iFrame(int sendSequence, int receiveSequence, ASDU asdu) {
        return new APDUIFormat(Iec104LinkLayer.toWire(sendSequence), Iec104LinkLayer.toWire(receiveSequence), asdu);
    }

    private static APDUSFormat sFrame(int receiveSequence) {
        return new APDUSFormat(0x01, Iec104LinkLayer.toWire(receiveSequence));
    }

}
