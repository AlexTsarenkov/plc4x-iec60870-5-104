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
package org.apache.plc4x.java.iec608705104.readwrite.tag;

import org.apache.plc4x.java.api.exceptions.PlcInvalidTagException;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcValueType;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Address of one information object, or with wildcards of a group of them.
 *
 * <h2>Syntax</h2>
 * <pre>
 *   &lt;asdu&gt;/&lt;ioa&gt;[:&lt;type&gt;]
 * </pre>
 * <ul>
 *   <li>{@code asdu} - common address of ASDU (station address), decimal {@code 0..65535},
 *       or {@code *} for any station.</li>
 *   <li>{@code ioa} - information object address, decimal {@code 0..16777215},
 *       or {@code *} for any object.</li>
 *   <li>{@code type} - optional, only used when writing, see {@link Iec608705104DataType}.</li>
 * </ul>
 *
 * <p>Examples: {@code 1/100}, {@code 1/300:FLOAT}, {@code 1/400:C_SC_NA_1}, {@code 1/*},
 * {@code *}{@code /*}.
 *
 * <h2>Where wildcards are allowed</h2>
 * Wildcards only make sense for subscriptions, where one tag can receive many objects.
 * Reading and writing address exactly one object and reject a wildcard address with
 * {@link org.apache.plc4x.java.api.types.PlcResponseCode#INVALID_ADDRESS}.
 *
 * <h2>Differences to upstream PLC4X</h2>
 * Upstream 1.x also accepts octet-wise addresses ({@code 1/2.3.4}). This fork deliberately
 * accepts decimal numbers only, so that every address has exactly one spelling.
 */
public class Iec608705104Tag implements PlcTag {

    /** Value of {@link #getAsduAddress()} or {@link #getObjectAddress()} for a {@code *}. */
    public static final int WILDCARD = -1;

    /** Largest common address of ASDU (2 octets). 65535 is the broadcast address. */
    public static final int MAX_ASDU_ADDRESS = 0xFFFF;

    /** Largest information object address (3 octets). */
    public static final int MAX_OBJECT_ADDRESS = 0xFFFFFF;

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
        "^(?<asdu>\\d{1,5}|\\*)/(?<ioa>\\d{1,8}|\\*)(?::(?<type>[A-Za-z0-9_]+))?$");

    private final int asduAddress;
    private final int objectAddress;
    private final Iec608705104DataType dataType;

    /**
     * An address without a type suffix.
     *
     * @param asduAddress   common address of ASDU or {@link #WILDCARD}.
     * @param objectAddress information object address or {@link #WILDCARD}.
     */
    public Iec608705104Tag(int asduAddress, int objectAddress) {
        this(asduAddress, objectAddress, null);
    }

    /**
     * @param asduAddress   common address of ASDU or {@link #WILDCARD}.
     * @param objectAddress information object address or {@link #WILDCARD}.
     * @param dataType      the type to write as, or {@code null} to derive it from the value.
     */
    public Iec608705104Tag(int asduAddress, int objectAddress, Iec608705104DataType dataType) {
        if (asduAddress != WILDCARD && (asduAddress < 0 || asduAddress > MAX_ASDU_ADDRESS)) {
            throw new IllegalArgumentException("ASDU address out of range 0.." + MAX_ASDU_ADDRESS + ": " + asduAddress);
        }
        if (objectAddress != WILDCARD && (objectAddress < 0 || objectAddress > MAX_OBJECT_ADDRESS)) {
            throw new IllegalArgumentException("Information object address out of range 0.." + MAX_OBJECT_ADDRESS + ": " + objectAddress);
        }
        this.asduAddress = asduAddress;
        this.objectAddress = objectAddress;
        this.dataType = dataType;
    }

    /**
     * Parses an address string, see the class documentation for the syntax.
     *
     * @param address the address as written by the user.
     * @return the parsed tag.
     * @throws PlcInvalidTagException if the address is malformed or out of range.
     */
    public static Iec608705104Tag of(String address) throws PlcInvalidTagException {
        if (address == null) {
            throw new PlcInvalidTagException("null");
        }
        Matcher matcher = ADDRESS_PATTERN.matcher(address.trim());
        if (!matcher.matches()) {
            throw new PlcInvalidTagException(address, ADDRESS_PATTERN,
                "expected <asdu>/<ioa>[:<type>], for example 1/100 or 1/300:FLOAT");
        }
        int asdu = parseComponent(address, matcher.group("asdu"), MAX_ASDU_ADDRESS, "ASDU address");
        int ioa = parseComponent(address, matcher.group("ioa"), MAX_OBJECT_ADDRESS, "information object address");
        Iec608705104DataType type = null;
        String typeText = matcher.group("type");
        if (typeText != null) {
            type = Iec608705104DataType.fromText(typeText).orElseThrow(() -> new PlcInvalidTagException(
                address, ADDRESS_PATTERN, "unknown type '" + typeText + "', expected one of FLOAT, SCALED, NORMALIZED, BOOL"));
        }
        return new Iec608705104Tag(asdu, ioa, type);
    }

    private static int parseComponent(String address, String text, int max, String what) throws PlcInvalidTagException {
        if ("*".equals(text)) {
            return WILDCARD;
        }
        int value = Integer.parseInt(text);
        if (value > max) {
            throw new PlcInvalidTagException(address, ADDRESS_PATTERN, what + " out of range 0.." + max);
        }
        return value;
    }

    /**
     * @return the common address of ASDU, or {@link #WILDCARD}.
     */
    public int getAsduAddress() {
        return asduAddress;
    }

    /**
     * @return the common address of ASDU, or {@link #WILDCARD}.
     * @deprecated the original 0.13.1 name, misspelled. Use {@link #getAsduAddress()}.
     */
    @Deprecated
    public int getAdsuAddress() {
        return asduAddress;
    }

    /**
     * @return the information object address, or {@link #WILDCARD}.
     */
    public int getObjectAddress() {
        return objectAddress;
    }

    /**
     * @return the type suffix of the address, if one was given.
     */
    public Optional<Iec608705104DataType> getDataType() {
        return Optional.ofNullable(dataType);
    }

    /**
     * @return {@code true} if either part of the address is a wildcard.
     */
    public boolean isWildcard() {
        return asduAddress == WILDCARD || objectAddress == WILDCARD;
    }

    /**
     * Checks whether a received information object is covered by this address.
     * The type suffix is ignored, it only matters for writing.
     *
     * @param asdu common address of ASDU of the received object.
     * @param ioa  information object address of the received object.
     * @return {@code true} if this address covers the object.
     */
    public boolean matches(int asdu, int ioa) {
        return (asduAddress == WILDCARD || asduAddress == asdu)
            && (objectAddress == WILDCARD || objectAddress == ioa);
    }

    /**
     * @return the canonical spelling of this address, for example {@code 1/300:FLOAT}.
     */
    @Override
    public String getAddressString() {
        String base = component(asduAddress) + "/" + component(objectAddress);
        return dataType == null ? base : base + ":" + dataType.name();
    }

    private static String component(int value) {
        return value == WILDCARD ? "*" : Integer.toString(value);
    }

    /**
     * @return the value type a written value is converted to, or {@code null} without a type
     * suffix. {@code null} tells PLC4X's value handler to keep the Java type the user passed
     * in ({@code Boolean} becomes BOOL, {@code Float} becomes REAL and so on); the driver then
     * derives the command from that.
     */
    @Override
    public PlcValueType getPlcValueType() {
        return dataType == null ? null : dataType.getPlcValueType();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Iec608705104Tag)) {
            return false;
        }
        Iec608705104Tag that = (Iec608705104Tag) o;
        return asduAddress == that.asduAddress && objectAddress == that.objectAddress && dataType == that.dataType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(asduAddress, objectAddress, dataType);
    }

    @Override
    public String toString() {
        return "Iec608705104Tag{" + getAddressString() + "}";
    }

}
