#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
"""
IEC 60870-5-104 test server (controlled station) for the PLC4X driver.

Built on c104 (https://github.com/Fraunhofer-FIT-DIEN/iec104-python), a Python
binding of lib60870-C. It is an independent implementation of the protocol, which
is the point: the driver's own unit tests use a Java simulator written alongside
the driver, and this server catches the cases where both sides would share the
same misunderstanding.

The point list is fixed and documented in README.md next to this file. The
integration test (Iec608705104InteropIT) relies on exactly these addresses and
initial values, so change both together.

Everything lives in common address (CA) 1.
"""

import os
import random
import signal
import sys
import threading

import c104

PORT = int(os.environ.get("IEC104_PORT", "2404"))
CA = 1
# Interval of the one point that changes on its own. Kept long on purpose: a
# station that only sends a frame now and then is exactly the situation in
# which a client that never acknowledges (missing t2 timer) gets disconnected
# after t1, so the integration test keeps the connection open across it.
SLOW_POINT_INTERVAL_MS = int(os.environ.get("IEC104_SLOW_POINT_INTERVAL_MS", "30000"))


def log(message: str) -> None:
    print(message, flush=True)


server = c104.Server(ip="0.0.0.0", port=PORT)
station = server.add_station(common_address=CA)

# ---------------------------------------------------------------------------
# Monitoring points (station -> client)
# ---------------------------------------------------------------------------

# Float measurement, written through setpoint IOA 300.
m_float = station.add_point(io_address=100, type=c104.Type.M_ME_NC_1)
m_float.value = 12.5

# Float measurement that changes on its own every SLOW_POINT_INTERVAL_MS.
m_slow = station.add_point(io_address=101, type=c104.Type.M_ME_NC_1)
m_slow.value = 0.0

# Scaled measurement, written through setpoint IOA 310.
m_scaled = station.add_point(io_address=110, type=c104.Type.M_ME_NB_1)
m_scaled.value = c104.Int16(1234)

# Normalized measurement, written through setpoint IOA 320.
m_normalized = station.add_point(io_address=120, type=c104.Type.M_ME_NA_1)
m_normalized.value = c104.NormalizedFloat(0.5)

# Single point, written through single command IOA 400.
m_single = station.add_point(io_address=200, type=c104.Type.M_SP_NA_1)
m_single.value = False

# ---------------------------------------------------------------------------
# Control points (client -> station)
# ---------------------------------------------------------------------------


def mirror_to(target):
    """Command callback that copies the received value to a monitoring point."""

    # c104 checks the annotations of callbacks, so they must be complete.
    def on_receive(point: c104.Point, previous_info: c104.Information,
                   message: c104.IncomingMessage) -> c104.ResponseState:
        log(f"command {point.type} IOA={point.io_address} value={point.value} -> IOA {target.io_address}")
        target.value = point.value
        target.transmit(cause=c104.Cot.RETURN_INFO_REMOTE)
        return c104.ResponseState.SUCCESS

    return on_receive


def reject(point: c104.Point, previous_info: c104.Information,
           message: c104.IncomingMessage) -> c104.ResponseState:
    log(f"command {point.type} IOA={point.io_address} value={point.value} -> rejected")
    return c104.ResponseState.FAILURE


c_float = station.add_point(io_address=300, type=c104.Type.C_SE_NC_1)
c_float.on_receive(callable=mirror_to(m_float))

c_scaled = station.add_point(io_address=310, type=c104.Type.C_SE_NB_1)
c_scaled.on_receive(callable=mirror_to(m_scaled))

c_normalized = station.add_point(io_address=320, type=c104.Type.C_SE_NA_1)
c_normalized.on_receive(callable=mirror_to(m_normalized))

c_rejecting = station.add_point(io_address=399, type=c104.Type.C_SE_NC_1)
c_rejecting.on_receive(callable=reject)

c_single = station.add_point(io_address=400, type=c104.Type.C_SC_NA_1)
c_single.on_receive(callable=mirror_to(m_single))

# ---------------------------------------------------------------------------
# The slowly changing point. A plain thread instead of Point.on_timer, because
# on_timer only runs while a client is connected and we want the value to keep
# moving regardless.
# ---------------------------------------------------------------------------

stop = threading.Event()


def change_slow_point() -> None:
    while not stop.wait(SLOW_POINT_INTERVAL_MS / 1000.0):
        m_slow.value = round(random.uniform(0.0, 100.0), 2)
        m_slow.transmit(cause=c104.Cot.SPONTANEOUS)
        log(f"spontaneous IOA=101 value={m_slow.value}")


def shutdown(*_args) -> None:
    stop.set()


signal.signal(signal.SIGTERM, shutdown)
signal.signal(signal.SIGINT, shutdown)

server.start()
log(f"IEC 60870-5-104 test server listening on 0.0.0.0:{PORT}, CA={CA}")
threading.Thread(target=change_slow_point, daemon=True).start()

stop.wait()
server.stop()
log("stopped")
sys.exit(0)
