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
package org.apache.plc4x.java.iec608705104.readwrite.configuration;

import org.apache.plc4x.java.iec608705104.readwrite.protocol.Iec104Time;
import org.apache.plc4x.java.spi.configuration.PlcConnectionConfiguration;
import org.apache.plc4x.java.spi.configuration.annotations.ConfigurationParameter;
import org.apache.plc4x.java.spi.configuration.annotations.Description;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.BooleanDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.IntDefaultValue;
import org.apache.plc4x.java.spi.configuration.annotations.defaults.StringDefaultValue;

import java.time.ZoneId;
import java.util.Locale;

/**
 * Options of the connection string, for example
 * {@code iec-60870-5-104:tcp://10.0.0.5:2404?read-mode=command&t3-ms=30000}.
 *
 * <p>PLC4X fills the fields from the connection string by their
 * {@link ConfigurationParameter} name; the annotations are the single source of truth for
 * names and defaults. The table in the driver documentation (README.md) must be kept in
 * sync with this class.
 *
 * <p>The link layer parameters (t1, t2, t3, k, w) follow IEC 60870-5-104, section 5.
 * Their defaults are the values the standard recommends, and they must match what the
 * station is configured with, which is almost always these defaults.
 *
 * <p>The class name keeps the original 0.13.1 spelling (014 instead of 104) so that
 * existing code referencing it keeps compiling.
 */
public class Iec608705014Configuration implements PlcConnectionConfiguration {

    /** How {@code read()} obtains a value, see {@link #getReadMode()}. */
    public enum ReadMode {
        /**
         * Answer from the values the station has already sent (general interrogation,
         * spontaneous and periodic data). Nothing is sent to the station. Works with every
         * station.
         */
        CACHE,
        /**
         * Send a read command (C_RD_NA_1) per tag and wait for the answer. Gives a fresh
         * value, but not every station supports it.
         */
        COMMAND
    }

    @ConfigurationParameter("request-timeout")
    @IntDefaultValue(4000)
    @Description("Timeout in milliseconds for connection setup (TESTFR/STARTDT) and for each write and read command.")
    protected int requestTimeout;

    @ConfigurationParameter("t1-ms")
    @IntDefaultValue(15000)
    @Description("t1: how long a sent I-frame or test frame may stay unacknowledged before the connection is closed (ms).")
    protected int t1Millis;

    @ConfigurationParameter("t2-ms")
    @IntDefaultValue(10000)
    @Description("t2: latest acknowledgement of received I-frames when fewer than w arrived (ms). Must be smaller than t1.")
    protected int t2Millis;

    @ConfigurationParameter("t3-ms")
    @IntDefaultValue(20000)
    @Description("t3: idle time after which a test frame is sent to check the connection (ms).")
    protected int t3Millis;

    @ConfigurationParameter("k")
    @IntDefaultValue(12)
    @Description("k: maximum number of sent I-frames that may be unacknowledged.")
    protected int k;

    @ConfigurationParameter("w")
    @IntDefaultValue(8)
    @Description("w: acknowledge at the latest after this many received I-frames. Must not be larger than k.")
    protected int w;

    @ConfigurationParameter("general-interrogation")
    @BooleanDefaultValue(true)
    @Description("Send a general interrogation after connecting (and after the station reports end of initialization).")
    protected boolean generalInterrogation;

    @ConfigurationParameter("interrogation-asdu-address")
    @IntDefaultValue(65535)
    @Description("Common address the general interrogation is sent to. 65535 is the broadcast address.")
    protected int interrogationAsduAddress;

    @ConfigurationParameter("interrogation-timeout-ms")
    @IntDefaultValue(30000)
    @Description("How long a read in cache mode waits for the general interrogation to finish (ms).")
    protected int interrogationTimeoutMillis;

    @ConfigurationParameter("read-mode")
    @StringDefaultValue("cache")
    @Description("How read requests are answered: 'cache' (values already received) or 'command' (C_RD_NA_1 per tag).")
    protected String readMode;

    @ConfigurationParameter("select-before-operate")
    @BooleanDefaultValue(false)
    @Description("Send commands as select-before-operate (select, then execute) unless the tag says 'direct'.")
    protected boolean selectBeforeOperate;

    @ConfigurationParameter("wait-for-termination")
    @BooleanDefaultValue(false)
    @Description("A command is only complete when the station sends ACT_TERM, not already with ACT_CON.")
    protected boolean waitForTermination;

    @ConfigurationParameter("termination-timeout-ms")
    @IntDefaultValue(30000)
    @Description("With wait-for-termination: how long to wait for ACT_TERM after ACT_CON (ms).")
    protected int terminationTimeoutMillis;

    @ConfigurationParameter("time-zone")
    @StringDefaultValue("UTC")
    @Description("Time zone of the station's time tags (CP56Time2a/CP24Time2a): 'UTC', 'local' or a zone id like 'Europe/Moscow'.")
    protected String timeZone;

    @ConfigurationParameter("originator-address")
    @IntDefaultValue(0)
    @Description("Originator address put into every ASDU this client sends (0..255).")
    protected int originatorAddress;

    /**
     * Checks the values for consistency. Called once when the connection is set up, so a
     * misconfiguration shows up as a connection error instead of odd behaviour later.
     *
     * @throws IllegalArgumentException describing the first problem found.
     */
    public void validate() {
        requirePositive("request-timeout", requestTimeout);
        requirePositive("t1-ms", t1Millis);
        requirePositive("t2-ms", t2Millis);
        requirePositive("t3-ms", t3Millis);
        requirePositive("interrogation-timeout-ms", interrogationTimeoutMillis);
        if (t2Millis >= t1Millis) {
            throw new IllegalArgumentException("t2-ms (" + t2Millis + ") must be smaller than t1-ms (" + t1Millis + ")");
        }
        if (k < 1 || k > 32767) {
            throw new IllegalArgumentException("k must be in 1..32767, was " + k);
        }
        if (w < 1 || w > k) {
            throw new IllegalArgumentException("w must be in 1..k (" + k + "), was " + w);
        }
        if (interrogationAsduAddress < 0 || interrogationAsduAddress > 0xFFFF) {
            throw new IllegalArgumentException("interrogation-asdu-address must be in 0..65535, was " + interrogationAsduAddress);
        }
        if (originatorAddress < 0 || originatorAddress > 0xFF) {
            throw new IllegalArgumentException("originator-address must be in 0..255, was " + originatorAddress);
        }
        getReadMode();
        requirePositive("termination-timeout-ms", terminationTimeoutMillis);
        getTimeZone();
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getT1Millis() {
        return t1Millis;
    }

    public void setT1Millis(int t1Millis) {
        this.t1Millis = t1Millis;
    }

    public int getT2Millis() {
        return t2Millis;
    }

    public void setT2Millis(int t2Millis) {
        this.t2Millis = t2Millis;
    }

    public int getT3Millis() {
        return t3Millis;
    }

    public void setT3Millis(int t3Millis) {
        this.t3Millis = t3Millis;
    }

    public int getK() {
        return k;
    }

    public void setK(int k) {
        this.k = k;
    }

    public int getW() {
        return w;
    }

    public void setW(int w) {
        this.w = w;
    }

    public boolean isGeneralInterrogation() {
        return generalInterrogation;
    }

    public void setGeneralInterrogation(boolean generalInterrogation) {
        this.generalInterrogation = generalInterrogation;
    }

    public int getInterrogationAsduAddress() {
        return interrogationAsduAddress;
    }

    public void setInterrogationAsduAddress(int interrogationAsduAddress) {
        this.interrogationAsduAddress = interrogationAsduAddress;
    }

    public int getInterrogationTimeoutMillis() {
        return interrogationTimeoutMillis;
    }

    public void setInterrogationTimeoutMillis(int interrogationTimeoutMillis) {
        this.interrogationTimeoutMillis = interrogationTimeoutMillis;
    }

    /**
     * @return the parsed {@code read-mode} option.
     * @throws IllegalArgumentException if the option has an unknown value.
     */
    public ReadMode getReadMode() {
        String value = readMode == null ? "cache" : readMode.trim().toUpperCase(Locale.ROOT);
        try {
            return ReadMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("read-mode must be 'cache' or 'command', was '" + readMode + "'", e);
        }
    }

    public void setReadMode(String readMode) {
        this.readMode = readMode;
    }

    public boolean isSelectBeforeOperate() {
        return selectBeforeOperate;
    }

    public void setSelectBeforeOperate(boolean selectBeforeOperate) {
        this.selectBeforeOperate = selectBeforeOperate;
    }

    public boolean isWaitForTermination() {
        return waitForTermination;
    }

    public void setWaitForTermination(boolean waitForTermination) {
        this.waitForTermination = waitForTermination;
    }

    public int getTerminationTimeoutMillis() {
        return terminationTimeoutMillis;
    }

    public void setTerminationTimeoutMillis(int terminationTimeoutMillis) {
        this.terminationTimeoutMillis = terminationTimeoutMillis;
    }

    /**
     * @return the parsed {@code time-zone} option.
     * @throws IllegalArgumentException for an unknown zone.
     */
    public ZoneId getTimeZone() {
        return Iec104Time.parseZone(timeZone);
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public int getOriginatorAddress() {
        return originatorAddress;
    }

    public void setOriginatorAddress(int originatorAddress) {
        this.originatorAddress = originatorAddress;
    }

    @Override
    public String toString() {
        return "Iec608705014Configuration{" +
            "requestTimeout=" + requestTimeout +
            ", t1=" + t1Millis + ", t2=" + t2Millis + ", t3=" + t3Millis +
            ", k=" + k + ", w=" + w +
            ", generalInterrogation=" + generalInterrogation +
            ", interrogationAsduAddress=" + interrogationAsduAddress +
            ", interrogationTimeout=" + interrogationTimeoutMillis +
            ", readMode=" + readMode +
            ", selectBeforeOperate=" + selectBeforeOperate +
            ", waitForTermination=" + waitForTermination +
            ", terminationTimeout=" + terminationTimeoutMillis +
            ", timeZone=" + timeZone +
            ", originatorAddress=" + originatorAddress +
            '}';
    }

}
