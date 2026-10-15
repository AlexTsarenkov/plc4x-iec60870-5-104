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

import datetime
import os
import random
import time
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

# Double point, written through double command IOA 500.
m_double = station.add_point(io_address=210, type=c104.Type.M_DP_NA_1)
m_double.value = c104.Double.OFF

# Step position, moved by step command IOA 600.
m_step = station.add_point(io_address=220, type=c104.Type.M_ST_NA_1)
m_step.value = c104.Int7(0)

# Bitstring, written through bitstring command IOA 700.
m_bits = station.add_point(io_address=230, type=c104.Type.M_BO_NA_1)
m_bits.value = c104.Byte32(0)

# Counter, only reported on counter interrogation.
m_counter = station.add_point(io_address=240, type=c104.Type.M_IT_NA_1)
m_counter.value = 123456

# Difference in seconds between the server clock and the last clock synchronization
# command received (0.0 = in sync).
m_clock_offset = station.add_point(io_address=901, type=c104.Type.M_ME_NC_1)
m_clock_offset.value = -1.0

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

# With time tag CP56Time2a.
c_float_time = station.add_point(io_address=301, type=c104.Type.C_SE_TC_1)
c_float_time.on_receive(callable=mirror_to(m_float))

c_single_time = station.add_point(io_address=401, type=c104.Type.C_SC_TA_1)
c_single_time.on_receive(callable=mirror_to(m_single))

# Select-before-operate only: a direct execute is refused.
c_float_sbo = station.add_point(io_address=302, type=c104.Type.C_SE_NC_1,
                                command_mode=c104.CommandMode.SELECT_AND_EXECUTE)
c_float_sbo.on_receive(callable=mirror_to(m_float))

c_double = station.add_point(io_address=500, type=c104.Type.C_DC_NA_1)
c_double.on_receive(callable=mirror_to(m_double))


# The step position is kept here: c104 2.2's Int7 does not expose its number.
step_position = 0


def step(point: c104.Point, previous_info: c104.Information,
         message: c104.IncomingMessage) -> c104.ResponseState:
    global step_position
    step_position += 1 if point.value == c104.Step.HIGHER else -1
    m_step.value = c104.Int7(step_position)
    log(f"command {point.type} IOA={point.io_address} value={point.value} -> step position {m_step.value}")
    m_step.transmit(cause=c104.Cot.RETURN_INFO_REMOTE)
    return c104.ResponseState.SUCCESS


c_step = station.add_point(io_address=600, type=c104.Type.C_RC_NA_1)
c_step.on_receive(callable=step)

c_bits = station.add_point(io_address=700, type=c104.Type.C_BO_NA_1)
c_bits.on_receive(callable=mirror_to(m_bits))


def on_clock_sync(server: c104.Server, ip: str, date_time: datetime.datetime) -> c104.ResponseState:
    offset = time.time() - date_time.timestamp()
    log(f"clock sync from {ip}: {date_time.isoformat()}, offset {offset:.3f} s")
    m_clock_offset.value = offset
    return c104.ResponseState.SUCCESS


server.on_clock_sync(callable=on_clock_sync)

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
