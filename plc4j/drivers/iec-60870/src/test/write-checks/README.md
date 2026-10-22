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
# Ручная проверка записи

Наборы тегов для ручной проверки записи на реальной станции или в симуляторе
(FreyrSCADA). Формат такой же, как в приложении: `name`, `address`, `value`. Один файл —
один запрос записи. Все теги в одном запросе драйвер отправляет одновременно и ждёт ответов.

Ожидаемые коды ответов даны для настроек подключения по умолчанию:
`select-before-operate=false`, `wait-for-termination=false` (OK приходит по положительному
ACT_CON), `request-timeout=4000`.

## Подготовка станции

Все точки в общем адресе (CA) **1**. Для каждой команды на станции должна быть точка управления
этого типа; в FreyrSCADA ещё и привязка к точке мониторинга (Mapped Point), иначе станция
ответит отрицательным ACT_CON. Если точки нет, ожидаемый результат меняется на
`NOT_FOUND` (COT 47) или `REMOTE_ERROR`.

| IOA | Тип команды | Файл |
|----:|-------------|------|
| 1, 2 | C_SE_NC_1 | 06, 07 (уже настроены) |
| 10, 11, 13, 14 | C_SC_NA_1 | 01, 02 |
| 12 | C_SC_TA_1 | 01 |
| 20, 21, 23 | C_DC_NA_1 | 01, 02 |
| 22 | C_DC_TA_1 | 01 |
| 30, 31, 33 | C_RC_NA_1 | 01, 02 |
| 32 | C_RC_TA_1 | 01 |
| 40, 41 | C_SE_NA_1 | 01 |
| 42 | C_SE_TA_1 | 01 |
| 50, 51 | C_SE_NB_1 | 01 |
| 52 | C_SE_TB_1 | 01 |
| 60, 62 | C_SE_NC_1 | 01, 02 |
| 61, 63 | C_SE_TC_1 | 01, 02 |
| 70, 71 | C_BO_NA_1 | 01 |
| 72 | C_BO_TA_1 | 01 |
| 1001 | M_ME_NC_1 (точка измерения, к ней P_ME_NC_1 и P_AC_NA_1) | 04 |
| 1002 | M_ME_NB_1 (к ней P_ME_NB_1) | 04 |
| 1003 | M_ME_NA_1 (к ней P_ME_NA_1) | 04 |

Команды с меткой времени (`_TA_`, `_TB_`, `_TC_`, а также опция `time`) станция обычно
проверяет на свежесть: время в метке должно совпадать с часами станции. Если они расходятся
(разные часовые пояса, см. параметр `time-zone`), станция отклонит команду → `REMOTE_ERROR`.

## 01-process-commands.json — все команды процесса, ожидается OK

| Тег | Что уходит на станцию | Ожидание |
|-----|-----------------------|----------|
| Single-On | C_SC_NA_1, SCS=1 | OK |
| Single-Off-Number | C_SC_NA_1, SCS=0 (`0` принимается как false) | OK |
| Single-Time | C_SC_TA_1 с CP56 | OK |
| Double-On | C_DC_NA_1, DCS=2 (вкл) | OK |
| Double-Off-Text | C_DC_NA_1, DCS=1 (откл) | OK |
| Double-Time | C_DC_TA_1, DCS=2 | OK |
| Step-Up-3 | 3 команды C_RC_NA_1 RCS=2 подряд, каждая после ACT_CON предыдущей | OK; позиция на станции +3 |
| Step-Down-2 | 2 команды C_RC_NA_1 RCS=1 | OK; позиция −2 |
| Step-Time | 1 команда C_RC_TA_1 RCS=2 | OK |
| Normalized-Half | C_SE_NA_1, 16384 (0x4000) | OK; на станции 0.5 |
| Normalized-Min | C_SE_NA_1, −32768 (0x8000) | OK; −1.0 |
| Normalized-Time | C_SE_TA_1, 8192 | OK; 0.25 |
| Scaled | C_SE_NB_1, 1234 | OK |
| Scaled-Min | C_SE_NB_1, −32768 | OK |
| Scaled-Time | C_SE_TB_1, 100 | OK |
| Float-Negative | C_SE_NC_1, −12.75 | OK |
| Float-Time | C_SE_TC_1, 99.9 (на станции 99.900002 — точность float) | OK |
| Bitstring | C_BO_NA_1, 0x000000FF | OK |
| Bitstring-All-Bits | C_BO_NA_1, 0xFFFFFFFF | OK |
| Bitstring-Time | C_BO_TA_1, 0x00000001 | OK |

## 02-options.json — опции тега, ожидается OK

| Тег | Что проверяется | Ожидание |
|-----|-----------------|----------|
| Single-SBO | `sbo`: сначала выбор (S/E=1), после ACT_CON исполнение (S/E=0) — в трафике 2 команды | OK, если станция поддерживает выбор; иначе `REMOTE_ERROR` |
| Single-Short-Pulse | `qu=short`: QU=1 в байте SCO | OK |
| Double-Direct | `direct`: одна команда без выбора, даже при `select-before-operate=true` в подключении | OK; DCS=1 (откл) |
| Step-SBO | `sbo` + 2 шага: выбор/исполнение для каждого шага, 4 команды | OK, если станция поддерживает выбор |
| Float-SBO | `sbo,ql=0` | как Single-SBO |
| Float-Time-Option | `time`: то же, что `C_SE_TC_1`, уходит тип 63 | OK |

## 03-system-commands.json — системные команды на IOA 0, ожидается OK

| Тег | Ожидание |
|-----|----------|
| General-Interrogation | OK по ACT_CON; затем станция присылает все значения (COT 20) и ACT_TERM — подписки получат обновления |
| Counter-Interrogation | OK; станция присылает счётчики (COT 37) |
| Clock-Sync-Now | OK; часы станции выставлены на текущее время в поясе `time-zone` |
| Test-Command | OK (C_TS_TA_1) |

`03b-system-variants.json` — отдельно, потому что одновременно два опроса на один адрес
станция может не принять: опрос группы 1 (QOI 21) и опрос счётчиков с заморозкой (`frz=freeze`).
Ожидается OK; у FreyrSCADA опрос группы вернёт только точки, отнесённые к группе 1.

## 04-parameters.json — параметры, зависит от станции

| Тег | Ожидание |
|-----|----------|
| Param-Float-Threshold, Param-Scaled-High-Limit, Param-Normalized-Smoothing | OK, если станция поддерживает P_ME; иначе `INVALID_DATATYPE` (COT 44) или `REMOTE_ERROR` |
| Param-Activate | OK (ACT_CON) |
| Param-Deactivate | OK (уходит с COT 8, ответ DEACT_CON) |

## 05-risky-commands.json — осторожно

| Тег | Ожидание |
|-----|----------|
| Reset-Process | OK, если поддерживается; **станция может перезапуститься и разорвать соединение** — тогда остальные теги запроса получат `REMOTE_ERROR` |
| Delay-Acquisition | обычно `INVALID_DATATYPE` (COT 44): C_CD_NA_1 из IEC 101, в профиле 104 его нет |
| Test-Command-Without-Time | обычно `INVALID_DATATYPE`: в IEC 104 используется C_TS_TA_1 |

## 06-invalid-values.json — ошибки значений, в один запрос

Драйвер проверяет значение до отправки: такой тег получает `INVALID_DATA`, **на станцию ничего
не уходит** (в трафике его нет), остальные теги запроса выполняются.

| Тег | Ожидание | Причина |
|-----|----------|---------|
| Control-OK | OK | контрольный, значение корректно |
| Double-Number | INVALID_DATA | числа для двойной команды не принимаются (1 на линии = откл) |
| Double-Bad-Text | INVALID_DATA | только `true`/`false`/`"ON"`/`"OFF"` |
| Step-Zero | INVALID_DATA | 0 шагов |
| Step-Too-Many | INVALID_DATA | больше 64 шагов |
| Step-Fraction | INVALID_DATA | дробное число шагов |
| Normalized-Too-Big | INVALID_DATA | вне −1..+0.99997 |
| Normalized-Exactly-One | INVALID_DATA | +1.0 не представимо (максимум 1 − 2⁻¹⁵) |
| Float-NaN | INVALID_DATA | NaN |
| Bitstring-33-Bits | INVALID_DATA | больше 32 бит |
| Bitstring-Fraction | INVALID_DATA | дробное |
| Reset-Bad-Qualifier | INVALID_DATA | QRP 0..255 |
| Delay-Too-Long | INVALID_DATA | 0..59999 мс |

## 07-station-errors.json — отказы станции

Команды уходят на станцию, ошибку возвращает она.

| Тег | Ожидание |
|-----|----------|
| Control-OK | OK |
| Unknown-IOA | `NOT_FOUND` (COT 47) или `REMOTE_ERROR` (отрицательный ACT_CON) |
| Unknown-CA | `NOT_FOUND` (COT 46); если станция молчит — `REMOTE_ERROR` через `request-timeout` (4 с) |
| Wrong-Type-For-Point | IOA 1 настроена как C_SE_NC_1, а приходит C_SE_NB_1: `INVALID_DATATYPE` (COT 44), `NOT_FOUND` или `REMOTE_ERROR` — зависит от станции |

## 08*-build-error-*.json — ошибка всего запроса, по одному файлу

Эти ошибки обнаруживаются при сборке запроса (`builder.build()`), до драйвера: исключение
`PlcInvalidTagException`, запрос не отправляется целиком, status code нет. Поэтому каждый
случай в отдельном файле. Как это выглядит в логе — зависит от приложения.

| Файл | Ошибка |
|------|--------|
| 08a-build-error-unknown-type | `unknown type 'C_XX_NA_1'` |
| 08b-build-error-wrong-option | `option 'qu' does not apply to C_SE_NC_1` (у уставок `ql`, не `qu`) |
| 08c-build-error-scaled-range | 40000 не помещается в INT (−32768..32767), проверка ядра PLC4X |
| 08d-build-error-float-range | 1e39 больше максимума float, проверка ядра PLC4X |
| 08e-build-error-no-time-variant | `C_IC_NA_1 has no variant with time tag` |

## Известные особенности

Для `C_SC_NA_1` и `C_SE_NB_1` значение сначала приводит к типу ядро PLC4X (BOOL и INT), и
только потом его видит драйвер. Поэтому:

- `C_SC_NA_1` со значением `2` или `-5` уходит как «вкл» (любое ненулевое число → true), OK;
- `C_SE_NB_1` со значением `12.7` уходит как `12` (дробная часть отбрасывается), OK.
