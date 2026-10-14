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

# IEC 60870-5-104 test server, see README.md in this directory.
#
#   docker build -t iec104-test-server .
#   docker run --rm -p 2404:2404 iec104-test-server

FROM python:3.12-slim

# c104 ships binary wheels for linux x86_64 and aarch64, nothing to compile.
RUN pip install --no-cache-dir "c104>=2,<3"

COPY server.py /app/server.py

EXPOSE 2404
CMD ["python", "-u", "/app/server.py"]
