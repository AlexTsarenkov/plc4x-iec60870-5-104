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
package org.apache.plc4x.java.iec608705104.readwrite.model;

import org.apache.plc4x.java.iec608705104.readwrite.tag.Iec608705104Tag;
import org.apache.plc4x.java.spi.messages.PlcSubscriber;
import org.apache.plc4x.java.spi.model.DefaultPlcSubscriptionHandle;

import java.util.Objects;

/**
 * One tag of a subscription request.
 *
 * <p>IEC 60870-5-104 has no subscriptions on the wire: the station sends everything it has.
 * A handle is just the filter the driver applies to incoming information objects
 * ({@link #matches(int, int)}) plus the name the user gave the tag, under which matching
 * values are delivered.
 */
public class Iec608705104SubscriptionHandle extends DefaultPlcSubscriptionHandle {

    private final String tagName;
    private final Iec608705104Tag tag;

    /**
     * @param plcSubscriber the protocol that delivers the events.
     * @param tagName       the name of the tag in the subscription request.
     * @param tag           the (possibly wildcard) address.
     */
    public Iec608705104SubscriptionHandle(PlcSubscriber plcSubscriber, String tagName, Iec608705104Tag tag) {
        super(plcSubscriber);
        this.tagName = tagName;
        this.tag = tag;
    }

    /**
     * @return the name of the tag in the subscription request. Events for this handle carry
     * the value under this name.
     */
    public String getTagName() {
        return tagName;
    }

    /**
     * @return the subscribed address.
     */
    public Iec608705104Tag getTag() {
        return tag;
    }

    /**
     * @param asduAddress   common address of ASDU of a received information object.
     * @param objectAddress its information object address.
     * @return {@code true} if this subscription wants the object.
     */
    public boolean matches(int asduAddress, int objectAddress) {
        return tag.matches(asduAddress, objectAddress);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Iec608705104SubscriptionHandle)) {
            return false;
        }
        Iec608705104SubscriptionHandle that = (Iec608705104SubscriptionHandle) o;
        return Objects.equals(tagName, that.tagName) && Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tagName, tag);
    }

    @Override
    public String toString() {
        return "Iec608705104SubscriptionHandle{" + tagName + "=" + tag.getAddressString() + "}";
    }

}
