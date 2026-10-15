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

import org.apache.plc4x.java.iec608705104.readwrite.SevenOctetBinaryTime;
import org.apache.plc4x.java.iec608705104.readwrite.ThreeOctetBinaryTime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Converts between the binary time formats of IEC 60870-5 and {@link Instant}.
 *
 * <p>The time formats carry calendar fields (year, month, ..., milliseconds) but no time
 * zone. Which zone they are in is a matter of station configuration, so it is a connection
 * option ({@code time-zone}): {@code UTC} (the default, and the usual configuration),
 * {@code local} (the zone of this JVM) or a zone id such as {@code Europe/Moscow}. The same
 * zone is used for time tags received and sent (time-tagged commands, clock
 * synchronization).
 *
 * <ul>
 *   <li><b>CP56Time2a</b> (7 octets): milliseconds, minutes, hours, day of month and week,
 *       month, year (two digits, 2000-2099). The summer time bit is set when sending if
 *       the zone observes summer time at that instant; it is ignored when receiving, because
 *       the zone rules already determine it.</li>
 *   <li><b>CP24Time2a</b> (3 octets): milliseconds and minutes only. The hour and date are
 *       taken from the current time in the zone; if that puts the time more than half an
 *       hour into the future, it belongs to the previous hour.</li>
 * </ul>
 */
public final class Iec104Time {

    private final ZoneId zone;
    private final Clock clock;

    /**
     * @param zone  the zone the station's time tags are in.
     * @param clock the clock for "now"; {@link Clock#systemUTC()} outside of tests.
     */
    public Iec104Time(ZoneId zone, Clock clock) {
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * @param text {@code UTC}, {@code local} or a zone id, as in the {@code time-zone} option.
     * @return the zone.
     * @throws IllegalArgumentException for an unknown zone.
     */
    public static ZoneId parseZone(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || "utc".equals(trimmed.toLowerCase(Locale.ROOT))) {
            return ZoneOffset.UTC;
        }
        if ("local".equals(trimmed.toLowerCase(Locale.ROOT))) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(trimmed);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("time-zone must be 'UTC', 'local' or a zone id like 'Europe/Moscow', was '"
                + text + "'", e);
        }
    }

    /**
     * @return the zone time tags are interpreted in.
     */
    public ZoneId getZone() {
        return zone;
    }

    /**
     * @return the current time.
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * Encodes an instant as CP56Time2a.
     *
     * @param instant the time.
     * @return the time tag, valid (IV = 0).
     * @throws IllegalArgumentException outside of the years 2000-2099.
     */
    public SevenOctetBinaryTime toCp56(Instant instant) {
        ZonedDateTime time = instant.atZone(zone);
        if (time.getYear() < 2000 || time.getYear() > 2099) {
            throw new IllegalArgumentException("CP56Time2a can only carry the years 2000-2099, not " + time.getYear());
        }
        boolean summerTime = zone.getRules().isDaylightSavings(instant);
        int milliseconds = time.getSecond() * 1000 + time.getNano() / 1_000_000;
        return new SevenOctetBinaryTime(
            milliseconds,
            false,
            false,
            (byte) time.getMinute(),
            summerTime,
            (byte) time.getHour(),
            (byte) time.getDayOfWeek().getValue(),
            (byte) time.getDayOfMonth(),
            (byte) time.getMonthValue(),
            (byte) (time.getYear() - 2000));
    }

    /**
     * Decodes a CP56Time2a.
     *
     * @param time the time tag.
     * @return the instant.
     * @throws java.time.DateTimeException if the fields are not a valid date and time.
     */
    public Instant fromCp56(SevenOctetBinaryTime time) {
        return LocalDateTime.of(2000 + time.getYear(), time.getMonth(), time.getDay(), time.getHour(), time.getMinutes(),
                time.getMilliseconds() / 1000, (time.getMilliseconds() % 1000) * 1_000_000)
            .atZone(zone).toInstant();
    }

    /**
     * Decodes a CP24Time2a, which only carries minutes and milliseconds; the rest comes from
     * the current time.
     *
     * @param time the time tag.
     * @return the instant.
     * @throws java.time.DateTimeException if the fields are not a valid time.
     */
    public Instant fromCp24(ThreeOctetBinaryTime time) {
        ZonedDateTime now = clock.instant().atZone(zone);
        ZonedDateTime candidate = now.truncatedTo(ChronoUnit.HOURS)
            .withMinute(time.getMinutes())
            .withSecond(time.getMilliseconds() / 1000)
            .withNano((time.getMilliseconds() % 1000) * 1_000_000);
        if (candidate.isAfter(now.plusMinutes(30))) {
            candidate = candidate.minusHours(1);
        }
        return candidate.toInstant();
    }

}
