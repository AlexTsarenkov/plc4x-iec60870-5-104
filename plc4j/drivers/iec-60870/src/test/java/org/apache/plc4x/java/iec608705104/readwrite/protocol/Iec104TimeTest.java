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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Iec104TimeTest {

    private static Iec104Time at(String zone, String now) {
        return new Iec104Time(Iec104Time.parseZone(zone), Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    @Test
    void parsesZones() {
        assertEquals(ZoneOffset.UTC, Iec104Time.parseZone("UTC"));
        assertEquals(ZoneOffset.UTC, Iec104Time.parseZone("utc"));
        assertEquals(ZoneOffset.UTC, Iec104Time.parseZone(null));
        assertEquals(ZoneId.systemDefault(), Iec104Time.parseZone("local"));
        assertEquals(ZoneId.of("Europe/Moscow"), Iec104Time.parseZone("Europe/Moscow"));
        assertThrows(IllegalArgumentException.class, () -> Iec104Time.parseZone("Mars/Olympus"));
    }

    @Test
    void cp56InUtc() {
        Iec104Time time = at("UTC", "2026-10-15T09:30:15.250Z");
        SevenOctetBinaryTime cp56 = time.toCp56(time.now());
        assertEquals(15250, cp56.getMilliseconds());
        assertEquals(30, cp56.getMinutes());
        assertEquals(9, cp56.getHour());
        assertEquals(4, cp56.getDayOfWeek(), "Thursday");
        assertEquals(15, cp56.getDay());
        assertEquals(10, cp56.getMonth());
        assertEquals(26, cp56.getYear());
        assertFalse(cp56.getDaylightSaving());
        assertFalse(cp56.getInvalid());
        assertEquals(time.now(), time.fromCp56(cp56));
    }

    @Test
    void cp56InAZoneWithSummerTime() {
        Iec104Time time = at("Europe/Berlin", "2026-07-01T10:00:00Z");
        SevenOctetBinaryTime cp56 = time.toCp56(time.now());
        assertEquals(12, cp56.getHour(), "CEST = UTC+2");
        assertTrue(cp56.getDaylightSaving());
        assertEquals(time.now(), time.fromCp56(cp56));

        SevenOctetBinaryTime winter = time.toCp56(Instant.parse("2026-01-01T10:00:00Z"));
        assertEquals(11, winter.getHour(), "CET = UTC+1");
        assertFalse(winter.getDaylightSaving());
    }

    @Test
    void cp56InMoscow() {
        Iec104Time time = at("Europe/Moscow", "2026-10-15T09:30:00Z");
        assertEquals(12, time.toCp56(time.now()).getHour());
    }

    @Test
    void cp56OutsideTheCenturyIsRejected() {
        Iec104Time time = at("UTC", "2026-10-15T09:30:00Z");
        assertThrows(IllegalArgumentException.class, () -> time.toCp56(Instant.parse("2100-01-01T00:00:00Z")));
    }

    @Test
    void cp24TakesHourAndDateFromNow() {
        Iec104Time time = at("UTC", "2026-10-15T09:30:00Z");
        assertEquals(Instant.parse("2026-10-15T09:01:02.003Z"), time.fromCp24(new ThreeOctetBinaryTime(2003, false, (byte) 1)));
        // 29 minutes ahead: still this hour (clocks are rarely that far apart, events are).
        assertEquals(Instant.parse("2026-10-15T09:59:00Z"), time.fromCp24(new ThreeOctetBinaryTime(0, false, (byte) 59)));
    }

    @Test
    void cp24FarInTheFutureBelongsToThePreviousHour() {
        Iec104Time time = at("UTC", "2026-10-15T09:10:00Z");
        assertEquals(Instant.parse("2026-10-15T08:55:00Z"), time.fromCp24(new ThreeOctetBinaryTime(0, false, (byte) 55)));
    }

}
