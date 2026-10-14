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

import org.apache.plc4x.java.api.value.PlcValue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last known value of every information object the station has reported on this
 * connection, regardless of how it arrived (general interrogation, spontaneous, periodic,
 * read command). Read requests in {@code read-mode=cache} are answered from here.
 *
 * <p>The cache belongs to one connection and starts empty on every connect, so it never
 * serves values of a previous connection.
 */
public class ValueCache {

    /**
     * One cached value.
     *
     * @param value     the value, with the metadata the driver attaches (quality, cause of
     *                  transmission, type).
     * @param timestamp time tag of the information object, or the time it was received if
     *                  it had none.
     */
    public record Entry(PlcValue value, Instant timestamp) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Stores the newest value of an information object.
     *
     * @param asduAddress   common address of ASDU.
     * @param objectAddress information object address.
     * @param value         the value.
     * @param timestamp     see {@link Entry#timestamp()}.
     */
    public void put(int asduAddress, int objectAddress, PlcValue value, Instant timestamp) {
        entries.put(key(asduAddress, objectAddress), new Entry(value, timestamp));
    }

    /**
     * @param asduAddress   common address of ASDU.
     * @param objectAddress information object address.
     * @return the newest value, or empty if the station has not reported this object.
     */
    public Optional<Entry> get(int asduAddress, int objectAddress) {
        return Optional.ofNullable(entries.get(key(asduAddress, objectAddress)));
    }

    /**
     * @return number of cached objects.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Removes all values.
     */
    public void clear() {
        entries.clear();
    }

    private static long key(int asduAddress, int objectAddress) {
        return ((long) asduAddress << 24) | objectAddress;
    }

}
