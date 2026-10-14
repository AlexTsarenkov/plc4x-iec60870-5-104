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
import org.apache.plc4x.java.spi.generation.ByteOrder;
import org.apache.plc4x.java.spi.generation.ParseException;
import org.apache.plc4x.java.spi.generation.ReadBuffer;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;

/**
 * Parses one received frame into an {@link APDU}, adding support for ASDUs with a set
 * structure qualifier (SQ=1), which the generated parser does not have.
 *
 * <h2>The problem</h2>
 * An ASDU normally carries an information object address (IOA) in front of every object
 * (SQ=0). With SQ=1 only the first object has an IOA; object {@code i} implicitly has
 * {@code IOA + i}. Stations use this a lot, for example lib60870 answers a general
 * interrogation with SQ=1 whenever addresses are consecutive. The mspec of PLC4X 0.13.1
 * always reads an IOA per object, so it fails on such frames ("Index out of bounds") and
 * the values are lost.
 *
 * <h2>The workaround</h2>
 * Before the generated parser sees an SQ=1 frame, this class rewrites its bytes into the
 * equivalent SQ=0 frame: every object gets its explicit IOA and the SQ bit is cleared.
 * That needs no change of the mspec or the generated code. The object size follows from
 * the frame length and the object count, since all objects of an ASDU have the same type.
 *
 * <p>Frame layout used here (offsets in bytes):
 * <pre>
 *  0      start byte 0x68
 *  1      length of the rest of the frame
 *  2..5   control field; bit 0 of byte 2 is 0 for I-frames
 *  6      type identification
 *  7      variable structure qualifier: bit 7 = SQ, bits 0..6 = number of objects
 *  8      cause of transmission
 *  9      originator address
 *  10..11 common address
 *  12..14 IOA of the first object (SQ=1) or of each object (SQ=0)
 *  15..   information elements
 * </pre>
 *
 * <p>Plugged into the Netty pipeline in {@code Iec60870514PlcDriver.getStackConfigurer()}.
 * If the proper fix (mspec) is ever done, this class and that line can be removed; see
 * MAINTENANCE.md, "Support for SQ=1".
 */
public final class Iec104FrameDecoder {

    private static final int OFFSET_CONTROL = 2;
    private static final int OFFSET_VSQ = 7;
    private static final int OFFSET_FIRST_IOA = 12;
    private static final int OFFSET_ELEMENTS = 15;
    private static final int IOA_LENGTH = 3;
    private static final int SQ_BIT = 0x80;

    private Iec104FrameDecoder() {
    }

    /**
     * {@code MessageInput} for the PLC4X codec.
     *
     * @param buffer the bytes of exactly one frame.
     * @return the parsed frame.
     * @throws ParseException if the frame cannot be parsed.
     */
    public static APDU parse(ReadBuffer buffer) throws ParseException {
        if (!(buffer instanceof ReadBufferByteBased)) {
            return APDU.staticParse(buffer);
        }
        ReadBufferByteBased byteBased = (ReadBufferByteBased) buffer;
        byte[] frame = byteBased.getBytes(byteBased.getPos(), byteBased.getTotalBytes());
        byte[] expanded = expandSequence(frame);
        if (expanded == frame) {
            return APDU.staticParse(buffer);
        }
        return APDU.staticParse(new ReadBufferByteBased(expanded, ByteOrder.LITTLE_ENDIAN));
    }

    /**
     * Rewrites an I-frame whose ASDU has SQ=1 into the equivalent frame with SQ=0.
     *
     * @param frame the complete frame, starting with 0x68.
     * @return the rewritten frame, or {@code frame} itself (same instance) if there is
     * nothing to rewrite or the frame is not consistent enough to rewrite safely.
     */
    public static byte[] expandSequence(byte[] frame) {
        if (frame.length < OFFSET_ELEMENTS || (frame[OFFSET_CONTROL] & 0x01) != 0) {
            // Too short for an ASDU with an object, or an S-/U-frame.
            return frame;
        }
        int vsq = frame[OFFSET_VSQ] & 0xFF;
        if ((vsq & SQ_BIT) == 0) {
            return frame;
        }
        int count = vsq & 0x7F;
        int elementsLength = frame.length - OFFSET_ELEMENTS;
        if (count == 0 || elementsLength % count != 0) {
            // Inconsistent; leave it to the parser to reject it.
            return frame;
        }
        int elementLength = elementsLength / count;
        int firstIoa = (frame[OFFSET_FIRST_IOA] & 0xFF)
            | (frame[OFFSET_FIRST_IOA + 1] & 0xFF) << 8
            | (frame[OFFSET_FIRST_IOA + 2] & 0xFF) << 16;

        byte[] result = new byte[OFFSET_FIRST_IOA + count * (IOA_LENGTH + elementLength)];
        System.arraycopy(frame, 0, result, 0, OFFSET_FIRST_IOA);
        // The length byte is an implicit field the parser reads and ignores; it cannot hold
        // more than 255, which an expanded frame may exceed.
        result[1] = (byte) Math.min(result.length - 2, 0xFF);
        result[OFFSET_VSQ] = (byte) count;
        int position = OFFSET_FIRST_IOA;
        for (int i = 0; i < count; i++) {
            int ioa = firstIoa + i;
            result[position] = (byte) ioa;
            result[position + 1] = (byte) (ioa >> 8);
            result[position + 2] = (byte) (ioa >> 16);
            System.arraycopy(frame, OFFSET_ELEMENTS + i * elementLength, result, position + IOA_LENGTH, elementLength);
            position += IOA_LENGTH + elementLength;
        }
        return result;
    }

}
