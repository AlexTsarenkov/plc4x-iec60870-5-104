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
import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104DataType.Option;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Address of one information object, or with wildcards of a group of them, plus for writing
 * the command to send.
 *
 * <h2>Syntax</h2>
 * <pre>
 *   &lt;asdu&gt;/&lt;ioa&gt;[:&lt;type&gt;[,&lt;option&gt;]...]
 * </pre>
 * <ul>
 *   <li>{@code asdu} - common address of ASDU (station address), decimal {@code 0..65535},
 *       or {@code *} for any station.</li>
 *   <li>{@code ioa} - information object address, decimal {@code 0..16777215},
 *       or {@code *} for any object.</li>
 *   <li>{@code type} - only used when writing: the command, see {@link Iec608705104DataType}.
 *       Short name ({@code FLOAT}) or IEC mnemonic ({@code C_SE_NC_1}, {@code C_SE_TC_1}).</li>
 *   <li>{@code option} - only after a type, case-insensitive, any order:
 *     <ul>
 *       <li>{@code time} - send the variant with time tag CP56Time2a (same as naming the
 *           {@code _TA_}/{@code _TB_}/{@code _TC_} mnemonic).</li>
 *       <li>{@code sbo} / {@code direct} - select-before-operate or direct execute; without
 *           either the connection option {@code select-before-operate} decides.</li>
 *       <li>{@code qu=} - qualifier of command, 0..31 or {@code none}, {@code short},
 *           {@code long}, {@code persistent} (single, double and step commands).</li>
 *       <li>{@code ql=} - qualifier of set point command, 0..127.</li>
 *       <li>{@code kpa=} - kind of parameter, 1..63 or {@code threshold}, {@code smoothing},
 *           {@code low}, {@code high} (P_ME, default threshold).</li>
 *       <li>{@code frz=} - freeze of counter interrogation, 0..3 or {@code read},
 *           {@code freeze}, {@code freeze_reset}, {@code reset}.</li>
 *       <li>{@code deact} - deactivation instead of activation (P_AC).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Examples: {@code 1/100}, {@code 1/300:FLOAT}, {@code 1/300:C_SE_TC_1,sbo},
 * {@code 1/500:C_DC_NA_1,qu=short}, {@code 1/0:C_CS_NA_1}, {@code 1/*}, {@code *}{@code /*}.
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
        "^(?<asdu>\\d{1,5}|\\*)/(?<ioa>\\d{1,8}|\\*)"
            + "(?::(?<type>[A-Za-z0-9_]+)(?<options>(?:,[A-Za-z0-9_]+(?:=[A-Za-z0-9_]+)?)*))?$");

    private static final String READABLE_PATTERN =
        "<asdu>/<ioa>[:<type>[,<option>]...], for example 1/100, 1/300:FLOAT or 1/300:C_SE_TC_1,sbo";

    private static final Map<String, Integer> QU_NAMES = Map.of("none", 0, "default", 0, "short", 1, "long", 2, "persistent", 3);
    private static final Map<String, Integer> KPA_NAMES = Map.of("threshold", 1, "smoothing", 2, "low", 3, "high", 4);
    private static final Map<String, Integer> FRZ_NAMES = Map.of("read", 0, "freeze", 1, "freeze_reset", 2, "reset", 3);

    private final int asduAddress;
    private final int objectAddress;
    private final Iec608705104DataType dataType;
    private final boolean timeTagged;
    private final Boolean selectBeforeOperate;
    private final Integer qualifierOfCommand;
    private final Integer qualifierOfSetPoint;
    private final Integer kindOfParameter;
    private final Integer freeze;
    private final boolean deactivate;

    /**
     * An address without a type.
     *
     * @param asduAddress   common address of ASDU or {@link #WILDCARD}.
     * @param objectAddress information object address or {@link #WILDCARD}.
     */
    public Iec608705104Tag(int asduAddress, int objectAddress) {
        this(asduAddress, objectAddress, null);
    }

    /**
     * An address with a type and no options.
     *
     * @param asduAddress   common address of ASDU or {@link #WILDCARD}.
     * @param objectAddress information object address or {@link #WILDCARD}.
     * @param dataType      the command to write with, or {@code null} to derive it from the value.
     */
    public Iec608705104Tag(int asduAddress, int objectAddress, Iec608705104DataType dataType) {
        this(asduAddress, objectAddress, dataType, false, null, null, null, null, null, false);
    }

    private Iec608705104Tag(int asduAddress, int objectAddress, Iec608705104DataType dataType, boolean timeTagged,
                            Boolean selectBeforeOperate, Integer qualifierOfCommand, Integer qualifierOfSetPoint,
                            Integer kindOfParameter, Integer freeze, boolean deactivate) {
        if (asduAddress != WILDCARD && (asduAddress < 0 || asduAddress > MAX_ASDU_ADDRESS)) {
            throw new IllegalArgumentException("ASDU address out of range 0.." + MAX_ASDU_ADDRESS + ": " + asduAddress);
        }
        if (objectAddress != WILDCARD && (objectAddress < 0 || objectAddress > MAX_OBJECT_ADDRESS)) {
            throw new IllegalArgumentException("Information object address out of range 0.." + MAX_OBJECT_ADDRESS + ": " + objectAddress);
        }
        this.asduAddress = asduAddress;
        this.objectAddress = objectAddress;
        this.dataType = dataType;
        this.timeTagged = timeTagged;
        this.selectBeforeOperate = selectBeforeOperate;
        this.qualifierOfCommand = qualifierOfCommand;
        this.qualifierOfSetPoint = qualifierOfSetPoint;
        this.kindOfParameter = kindOfParameter;
        this.freeze = freeze;
        this.deactivate = deactivate;
    }

    /**
     * Parses an address string, see the class documentation for the syntax.
     *
     * @param address the address as written by the user.
     * @return the parsed tag.
     * @throws PlcInvalidTagException if the address is malformed, out of range, names an
     *                                unknown type or an option the type does not take.
     */
    public static Iec608705104Tag of(String address) throws PlcInvalidTagException {
        if (address == null) {
            throw new PlcInvalidTagException("null");
        }
        Matcher matcher = ADDRESS_PATTERN.matcher(address.trim());
        if (!matcher.matches()) {
            throw invalid(address, "expected " + READABLE_PATTERN);
        }
        int asdu = parseComponent(address, matcher.group("asdu"), MAX_ASDU_ADDRESS, "ASDU address");
        int ioa = parseComponent(address, matcher.group("ioa"), MAX_OBJECT_ADDRESS, "information object address");
        String typeText = matcher.group("type");
        if (typeText == null) {
            return new Iec608705104Tag(asdu, ioa);
        }
        Iec608705104DataType.Resolved resolved = Iec608705104DataType.fromText(typeText)
            .orElseThrow(() -> invalid(address, "unknown type '" + typeText + "'"));
        Iec608705104DataType type = resolved.type();
        boolean timeTagged = resolved.timeTagged();
        Boolean select = null;
        Integer qu = null;
        Integer ql = null;
        Integer kpa = null;
        Integer frz = null;
        boolean deact = false;

        String options = matcher.group("options");
        for (String option : options.isEmpty() ? new String[0] : options.substring(1).split(",")) {
            String[] keyValue = option.split("=", 2);
            String key = keyValue[0].toLowerCase(Locale.ROOT);
            String value = keyValue.length > 1 ? keyValue[1].toLowerCase(Locale.ROOT) : null;
            switch (key) {
                case "time":
                    requireNoValue(address, key, value);
                    if (!type.hasTimeTaggedVariant()) {
                        throw invalid(address, type.getMnemonic() + " has no variant with time tag");
                    }
                    timeTagged = true;
                    break;
                case "sbo":
                case "direct":
                    requireNoValue(address, key, value);
                    requireOption(address, type, Option.SELECT, key);
                    select = "sbo".equals(key);
                    break;
                case "qu":
                    requireOption(address, type, Option.QU, key);
                    qu = parseOptionValue(address, key, value, QU_NAMES, 0, 31);
                    break;
                case "ql":
                    requireOption(address, type, Option.QL, key);
                    ql = parseOptionValue(address, key, value, Map.of(), 0, 127);
                    break;
                case "kpa":
                    requireOption(address, type, Option.KPA, key);
                    kpa = parseOptionValue(address, key, value, KPA_NAMES, 0, 63);
                    break;
                case "frz":
                    requireOption(address, type, Option.FRZ, key);
                    frz = parseOptionValue(address, key, value, FRZ_NAMES, 0, 3);
                    break;
                case "deact":
                    requireNoValue(address, key, value);
                    requireOption(address, type, Option.DEACTIVATE, key);
                    deact = true;
                    break;
                default:
                    throw invalid(address, "unknown option '" + key + "'");
            }
        }
        return new Iec608705104Tag(asdu, ioa, type, timeTagged, select, qu, ql, kpa, frz, deact);
    }

    private static PlcInvalidTagException invalid(String address, String reason) {
        return new PlcInvalidTagException(address, ADDRESS_PATTERN, reason);
    }

    private static void requireNoValue(String address, String key, String value) {
        if (value != null) {
            throw invalid(address, "option '" + key + "' takes no value");
        }
    }

    private static void requireOption(String address, Iec608705104DataType type, Option option, String key) {
        if (!type.supports(option)) {
            throw invalid(address, "option '" + key + "' does not apply to " + type.getMnemonic());
        }
    }

    private static int parseOptionValue(String address, String key, String value, Map<String, Integer> names,
                                        int min, int max) {
        if (value == null) {
            throw invalid(address, "option '" + key + "' needs a value, " + key + "=<value>");
        }
        Integer named = names.get(value);
        if (named != null) {
            return named;
        }
        try {
            int number = Integer.parseInt(value);
            if (number < min || number > max) {
                throw invalid(address, "option '" + key + "' out of range " + min + ".." + max);
            }
            return number;
        } catch (NumberFormatException e) {
            throw invalid(address, "option '" + key + "' has an invalid value '" + value + "'");
        }
    }

    private static int parseComponent(String address, String text, int max, String what) throws PlcInvalidTagException {
        if ("*".equals(text)) {
            return WILDCARD;
        }
        int value = Integer.parseInt(text);
        if (value > max) {
            throw invalid(address, what + " out of range 0.." + max);
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
     * @return the type part of the address, if one was given.
     */
    public Optional<Iec608705104DataType> getDataType() {
        return Optional.ofNullable(dataType);
    }

    /**
     * @return {@code true} if the variant with time tag CP56Time2a is to be sent.
     */
    public boolean isTimeTagged() {
        return timeTagged;
    }

    /**
     * @return {@code true} for {@code sbo}, {@code false} for {@code direct}, empty if the
     * connection default applies.
     */
    public Optional<Boolean> getSelectBeforeOperate() {
        return Optional.ofNullable(selectBeforeOperate);
    }

    /**
     * @return the qualifier of command ({@code qu=}), if given.
     */
    public Optional<Integer> getQualifierOfCommand() {
        return Optional.ofNullable(qualifierOfCommand);
    }

    /**
     * @return the qualifier of set point command ({@code ql=}), if given.
     */
    public Optional<Integer> getQualifierOfSetPoint() {
        return Optional.ofNullable(qualifierOfSetPoint);
    }

    /**
     * @return the kind of parameter ({@code kpa=}), if given.
     */
    public Optional<Integer> getKindOfParameter() {
        return Optional.ofNullable(kindOfParameter);
    }

    /**
     * @return the freeze of a counter interrogation ({@code frz=}), if given.
     */
    public Optional<Integer> getFreeze() {
        return Optional.ofNullable(freeze);
    }

    /**
     * @return {@code true} if a deactivation is to be sent ({@code deact}).
     */
    public boolean isDeactivate() {
        return deactivate;
    }

    /**
     * @return {@code true} if either part of the address is a wildcard.
     */
    public boolean isWildcard() {
        return asduAddress == WILDCARD || objectAddress == WILDCARD;
    }

    /**
     * Checks whether a received information object is covered by this address.
     * Type and options are ignored, they only matter for writing.
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
     * @return the canonical spelling of this address: IEC mnemonic, options in a fixed order,
     * for example {@code 1/300:C_SE_TC_1,sbo,ql=5}.
     */
    @Override
    public String getAddressString() {
        StringBuilder text = new StringBuilder(component(asduAddress)).append('/').append(component(objectAddress));
        if (dataType == null) {
            return text.toString();
        }
        text.append(':').append(dataType.getMnemonic(timeTagged));
        if (selectBeforeOperate != null) {
            text.append(selectBeforeOperate ? ",sbo" : ",direct");
        }
        appendOption(text, "qu", qualifierOfCommand);
        appendOption(text, "ql", qualifierOfSetPoint);
        appendOption(text, "kpa", kindOfParameter);
        appendOption(text, "frz", freeze);
        if (deactivate) {
            text.append(",deact");
        }
        return text.toString();
    }

    private static void appendOption(StringBuilder text, String key, Integer value) {
        if (value != null) {
            text.append(',').append(key).append('=').append(value);
        }
    }

    private static String component(int value) {
        return value == WILDCARD ? "*" : Integer.toString(value);
    }

    /**
     * @return the value type a written value is converted to, or {@code null} without a type
     * (or for types that accept several kinds of value). {@code null} tells PLC4X's value
     * handler to keep the Java type the user passed in ({@code Boolean} becomes BOOL,
     * {@code Float} becomes REAL and so on); the driver then works with that.
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
        return getAddressString().equals(that.getAddressString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getAddressString());
    }

    @Override
    public String toString() {
        return "Iec608705104Tag{" + getAddressString() + "}";
    }

}
