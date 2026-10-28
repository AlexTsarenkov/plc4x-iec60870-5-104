<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
# IEC 60870-5-104 test server

A small controlled station (RTU) for testing the driver against an independent
implementation of the protocol. It uses [c104](https://github.com/Fraunhofer-FIT-DIEN/iec104-python),
a Python binding of [lib60870-C](https://github.com/mz-automation/lib60870).

```bash
docker build -t iec104-test-server .
docker run --rm -p 2404:2404 iec104-test-server
```

Connect with `iec-60870-5-104:tcp://localhost:2404`.

Or with Docker Compose, attached to the existing external network `k8s_default`
(create it first with `docker network create k8s_default` if it does not exist):

```bash
docker compose up -d --build
docker compose logs -f
```

Containers in `k8s_default` connect with `iec-60870-5-104:tcp://iec104-test-server:2404`.
For an own point list, copy `server.py` to `my_server.py` and uncomment the
`volumes` lines in `docker-compose.yml`.

## Point list

All points live in common address (CA) **1**.

| IOA | Type | Direction | Initial value | Behaviour |
|----:|------|-----------|---------------|-----------|
| 100 | M_ME_NC_1 (float) | monitoring | 12.5 | Updated by setpoint 300 |
| 101 | M_ME_NC_1 (float) | monitoring | 0.0 | Random value every 30 s, sent spontaneously |
| 110 | M_ME_NB_1 (scaled) | monitoring | 1234 | Updated by setpoint 310 |
| 120 | M_ME_NA_1 (normalized) | monitoring | 0.5 | Updated by setpoint 320 |
| 200 | M_SP_NA_1 (single point) | monitoring | false | Updated by single command 400 |
| 300 | C_SE_NC_1 (float setpoint) | control | | Accepted, copied to IOA 100 |
| 310 | C_SE_NB_1 (scaled setpoint) | control | | Accepted, copied to IOA 110 |
| 320 | C_SE_NA_1 (normalized setpoint) | control | | Accepted, copied to IOA 120 |
| 399 | C_SE_NC_1 (float setpoint) | control | | Always rejected (negative ACT_CON) |
| 400 | C_SC_NA_1 (single command) | control | | Accepted, copied to IOA 200 |
| 210 | M_DP_NA_1 (double point) | monitoring | OFF | Updated by double command 500 |
| 220 | M_ST_NA_1 (step position) | monitoring | 0 | Moved by step command 600 |
| 230 | M_BO_NA_1 (bitstring) | monitoring | 0 | Updated by bitstring command 700 |
| 240 | M_IT_NA_1 (counter) | monitoring | 123456 | Reported on counter interrogation |
| 901 | M_ME_NC_1 (float) | monitoring | -1.0 | Server clock minus last clock synchronization time, seconds |
| 301 | C_SE_TC_1 (float set point with time tag) | control | | Accepted, copied to IOA 100 |
| 302 | C_SE_NC_1 (float set point) | control | | Select-and-execute only (direct is refused), copied to IOA 100 |
| 401 | C_SC_TA_1 (single command with time tag) | control | | Accepted, copied to IOA 200 |
| 500 | C_DC_NA_1 (double command) | control | | Accepted, copied to IOA 210 |
| 600 | C_RC_NA_1 (regulating step command) | control | | Accepted, IOA 220 one step up/down |
| 700 | C_BO_NA_1 (bitstring command) | control | | Accepted, copied to IOA 230 |

After a command is accepted, the new value of the monitoring point is sent with
cause of transmission *return information caused by a remote command* (COT 11).

The station answers general interrogation (C_IC_NA_1) and read commands
(C_RD_NA_1) for all monitoring points, counter interrogation (C_CI_NA_1, general),
clock synchronization (C_CS_NA_1) and the test command with time tag (C_TS_TA_1).
Reset process, delay acquisition, C_TS_NA_1 and parameters (P_ME, P_AC) are not
supported by c104; the driver's tests cover them against the Java test station.

## Environment variables

| Variable | Default | Meaning |
|----------|---------|---------|
| `IEC104_PORT` | `2404` | TCP port to listen on |
| `IEC104_SLOW_POINT_INTERVAL_MS` | `30000` | Interval of IOA 101 |

The long default interval of IOA 101 is deliberate. A station that sends a
frame only now and then is the situation in which a client that does not
acknowledge received frames in time (timer t2) is disconnected by the station
after t1. The integration test keeps a connection open across such a gap.

The integration test `Iec608705104InteropIT` depends on this exact point list.
Change both together.
