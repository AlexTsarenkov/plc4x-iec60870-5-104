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
# Драйвер IEC 60870-5-104 (форк PLC4X 0.13.1)

Это руководство пользователя драйвера. Как он устроен внутри и как его
сопровождать, описано в [MAINTENANCE.md](MAINTENANCE.md).

Драйвер основан на PLC4X 0.13.1 и переработан в этом форке, ветка
`iec104-read-write`. Он компилируется под **Java 17** и работает на Java 17.

## Что умеет

| Операция | Поддержка |
|----------|-----------|
| Подключение по TCP, канальный уровень IEC 104 (k, w, t1, t2, t3) | да |
| General Interrogation (C_IC_NA_1) после подключения и после M_EI_NA_1 | да, отключается |
| Чтение (`read`) из кэша значений | да, по умолчанию |
| Чтение командой C_RD_NA_1 | да, `read-mode=command` |
| Запись (`write`): C_SE_NC_1 (float), C_SE_NB_1 (scaled), C_SE_NA_1 (normalized), C_SC_NA_1 (bool) | да, прямое исполнение |
| Подписки (`subscribe`) с фильтром по адресу и wildcard | да |
| Select-before-operate, двойные команды (C_DC), шаговые (C_RC), команды с меткой времени | нет |
| ASDU с SQ=1 (последовательность объектов с одним адресом) | да |
| Browse | нет |
| Автоматическое переподключение | нет, это задача приложения |

## Подключение

```
iec-60870-5-104:tcp://<хост>[:<порт>][?<параметр>=<значение>&...]
```

Порт по умолчанию 2404. Пример:

```java
try (PlcConnection connection = PlcDriverManager.getDefault().getConnectionManager()
        .getConnection("iec-60870-5-104:tcp://10.0.0.5:2404?read-mode=cache&t3-ms=20000")) {
    ...
}
```

`getConnection` возвращает управление после того, как прошли TESTFR и STARTDT. General
Interrogation к этому моменту отправлена, но ещё может выполняться.

### Параметры

| Параметр | По умолчанию | Описание |
|----------|-------------:|----------|
| `request-timeout` | 4000 | Таймаут (мс) установки соединения (TESTFR, STARTDT) и каждой команды записи/чтения. |
| `t1-ms` | 15000 | t1: сколько отправленный I-кадр или TESTFR может оставаться без подтверждения станции, после чего соединение закрывается. |
| `t2-ms` | 10000 | t2: максимальная задержка подтверждения принятых I-кадров, если их пришло меньше `w`. Должен быть меньше `t1-ms`. |
| `t3-ms` | 20000 | t3: после стольких мс тишины отправляется TESTFR для проверки связи. |
| `k` | 12 | Максимум отправленных неподтверждённых I-кадров. Остальные ждут в очереди. |
| `w` | 8 | Подтверждать не позже чем через столько принятых I-кадров. Не больше `k`. |
| `general-interrogation` | true | Отправлять General Interrogation после подключения и после сообщения станции о (пере)запуске. |
| `interrogation-asdu-address` | 65535 | Общий адрес (CA), на который отправляется GI. 65535 — широковещательный. Если станция его не принимает, укажите её CA. |
| `interrogation-timeout-ms` | 30000 | Сколько чтение в режиме `cache` ждёт завершения первого GI. |
| `read-mode` | cache | `cache` — чтение из кэша, `command` — командой C_RD_NA_1. |
| `originator-address` | 0 | Адрес источника (0..255) во всех ASDU клиента. |

Таймеры и `k`/`w` должны совпадать с настройками станции. Почти всегда это значения
по умолчанию из стандарта. Некорректная комбинация (например, `t2-ms >= t1-ms`) приводит
к ошибке при подключении, а не к странному поведению потом.

## Адреса тегов

```
<CA>/<IOA>[:<тип>]
```

* `CA` — общий адрес ASDU (адрес станции), десятичное `0..65535` или `*`.
* `IOA` — адрес информационного объекта, десятичное `0..16777215` или `*`.
* `тип` — необязателен, нужен только для записи (см. ниже).

| Адрес | Значение |
|-------|----------|
| `1/100` | станция 1, объект 100 |
| `1/300:FLOAT` | то же для записи уставки float |
| `1/*` | все объекты станции 1 (только подписки) |
| `*/*` | всё, что приходит по соединению (только подписки) |

Октетная запись из upstream 1.x (`1/2.3.4`) в форке намеренно не поддерживается: у
каждого адреса ровно одно написание. Ошибочный адрес даёт `PlcInvalidTagException`
уже при построении запроса.

## Чтение

```java
PlcReadResponse response = connection.readRequestBuilder()
    .addTagAddress("power", "1/100")
    .build().execute().get(5, TimeUnit.SECONDS);
if (response.getResponseCode("power") == PlcResponseCode.OK) {
    double value = response.getPlcValue("power").getDouble();
}
```

### Режим `cache` (по умолчанию)

Драйвер запоминает последнее значение каждого объекта, о котором станция сообщила на
этом соединении: ответ на GI, спонтанные, периодические данные, ответы на C_RD. Чтение
отдаёт значение из этого кэша и **ничего не отправляет на станцию**.

* Чтение сразу после подключения ждёт окончания первого GI (ACT_TERM), но не дольше
  `interrogation-timeout-ms`, иначе кэш был бы ещё пуст.
* Кэш создаётся пустым при каждом подключении: значения прошлого соединения не отдаются.
* Работает с любой станцией.

### Режим `command`

На каждый тег отправляется C_RD_NA_1, ответ ожидается с COT=5 (request). Значение всегда
свежее, но не все станции поддерживают C_RD. Режимы можно смешивать, открыв два
соединения с разным `read-mode`.

### Коды ответа чтения

| Код | Когда |
|-----|-------|
| `OK` | значение есть |
| `NOT_FOUND` | в кэше нет значения этого объекта / станция ответила «неизвестный адрес» на C_RD |
| `INVALID_ADDRESS` | адрес с `*` |
| `REMOTE_ERROR` | нет ответа на C_RD за `request-timeout`, отрицательный ответ, соединение потеряно |

## Запись

```java
PlcWriteResponse response = connection.writeRequestBuilder()
    .addTagAddress("setpoint", "1/300", 42.5f)        // C_SE_NC_1
    .addTagAddress("limit", "1/310:SCALED", 1200)     // C_SE_NB_1
    .addTagAddress("ratio", "1/320:NORMALIZED", 0.25) // C_SE_NA_1
    .addTagAddress("breaker", "1/400", true)          // C_SC_NA_1
    .build().execute().get(5, TimeUnit.SECONDS);
```

В IEC 104 нет «регистров». Запись — это команда станции на **объект управления**,
который станция должна быть настроена принимать. Записать в объект измерения (M_ME_*)
протокол не позволяет. Обычно у станции есть пара адресов: уставка (C_SE) и её
обратная связь (M_ME).

### Какая команда отправляется

1. Если у адреса есть суффикс типа, используется он.
2. Иначе `boolean` записывается как C_SC_NA_1,
3. а любое число — как C_SE_NC_1 (float).

| Суффикс | Синоним | Команда | Значение |
|---------|---------|---------|----------|
| `FLOAT` | `C_SE_NC_1` | уставка, float 32 бит | любое число в диапазоне float |
| `SCALED` | `C_SE_NB_1` | уставка, масштабированное значение | целое −32768..32767 |
| `NORMALIZED` | `C_SE_NA_1` | уставка, нормализованное значение | −1.0 .. 1−2⁻¹⁵ (0.99997) |
| `BOOL` | `C_SC_NA_1` | одиночная команда | `true`/`false` или 1/0 |

Все команды отправляются с прямым исполнением (бит S/E = 0, квалификатор 0). Запись
завершается, когда станция ответила ACT_CON. ACT_TERM, если станция его шлёт,
игнорируется. Несколько тегов в одном запросе отправляются сразу, не по очереди.

### Коды ответа записи

| Код | Когда |
|-----|-------|
| `OK` | положительный ACT_CON |
| `REMOTE_ERROR` | отрицательный ACT_CON (станция отказала), нет ответа за `request-timeout`, соединение потеряно |
| `NOT_FOUND` | станция ответила «неизвестный CA» (COT 46) или «неизвестный IOA» (COT 47) |
| `INVALID_DATATYPE` | станция ответила «неизвестный тип» (COT 44): не тот суффикс типа |
| `INVALID_ADDRESS` | адрес с `*`; ничего не отправлялось |
| `INVALID_DATA` | значение не помещается в выбранную команду; ничего не отправлялось |

## Подписки

IEC 104 не знает подписок: станция сама шлёт всё, что у неё есть. Подписка в драйвере —
это фильтр по адресу.

```java
connection.subscriptionRequestBuilder()
    .addChangeOfStateTagAddress("power", "1/100")
    .addChangeOfStateTagAddress("station1", "1/*")
    .setConsumer(event -> {
        for (String name : event.getTagNames()) {
            Iec608705104Tag source = (Iec608705104Tag) event.getTag(name); // фактический адрес
            PlcValue value = event.getPlcValue(name);
        }
    })
    .build().execute().get(5, TimeUnit.SECONDS);
```

* Событие приходит под **именем тега из запроса** (`power`, `station1`). Если значение
  подходит под несколько тегов одного потребителя, оно приходит одним событием под
  всеми этими именами.
* `event.getTag(name)` возвращает фактический адрес объекта. Это полезно для wildcard.
* Тип подписки (`ChangeOfState`, `Cyclic`, `Event`) не влияет ни на что: присылает и
  решает станция.
* Потребителя можно указать в запросе (`setConsumer`, или в `add...TagAddress(..., consumer)`)
  **или** зарегистрировать через `response.getSubscriptionHandle(name).register(...)`.
  Не делайте и то и другое, иначе каждое событие придёт дважды.
* В событии бывают и ответы на GI (COT 20). Чтобы получать «только изменения»,
  фильтруйте по метаданным `cot` (см. ниже).

## Метаданные значения

К каждому значению, полученному от станции (чтение, подписка), приложены метаданные:
`value.getMetaData(имя)`.

| Имя | Тип | Содержимое |
|-----|-----|------------|
| `cot` | USINT | причина передачи (Cause of Transmission), см. таблицу |
| `typeId` | USINT | идентификатор типа ASDU, например 13 = M_ME_NC_1 |
| `timestamp` | LDATE_AND_TIME (UTC) | метка времени объекта или время приёма, если метки нет |
| `invalid`, `notTopical`, `substituted`, `blocked` | BOOL | дескриптор качества |
| `overflow` | BOOL | переполнение (для измерений) |

**Проверяйте качество.** Значение с `invalid=true` выглядит так же, как достоверное.

Частые значения `cot`:

| COT | Значение |
|----:|----------|
| 1 | периодически (periodic) |
| 2 | фоновое сканирование (background) |
| 3 | спонтанно (spontaneous), т. е. изменение |
| 5 | по запросу (ответ на C_RD) |
| 11 | обратная информация по удалённой команде |
| 12 | обратная информация по местной команде |
| 20 | ответ на General Interrogation |

## Потоки и блокировки

Ответы на `read`/`write` и вызовы потребителей подписок выполняются **в потоке
ввода-вывода Netty** этого соединения. Если там блокироваться (например, вызвать
`get()` на другом запросе того же соединения), соединение встанет. Тяжёлую обработку
передавайте в свой `Executor`.

## Пример: опрос по JSON (по таймеру или по изменению)

Конфигурация:

```json
{
  "connection": "iec-60870-5-104:tcp://10.0.0.5:2404",
  "tags": [
    { "name": "line1_P",  "address": "1/100", "mode": "change" },
    { "name": "line1_U",  "address": "1/101", "mode": "timer", "intervalMs": 5000 },
    { "name": "breaker1", "address": "1/200", "mode": "change" }
  ]
}
```

* `change`: подписка, значения с `cot` 3 (spontaneous), 11/12 (обратная информация) и
  20 (GI, начальное значение).
* `timer`: раз в `intervalMs` читается из кэша (`read-mode=cache`). Кэш наполняется
  станцией, драйвер на станцию при этом ничего не шлёт. Для принудительно свежего
  значения используйте отдельное соединение с `read-mode=command`.

Набросок:

```java
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
ExecutorService handlers = Executors.newSingleThreadExecutor();

PlcSubscriptionRequest.Builder subscription = connection.subscriptionRequestBuilder();
for (TagConfig tag : config.tags()) {
    if (tag.mode().equals("change")) {
        subscription.addChangeOfStateTagAddress(tag.name(), tag.address());
    } else {
        scheduler.scheduleAtFixedRate(() -> connection.readRequestBuilder()
                .addTagAddress(tag.name(), tag.address()).build().execute()
                .thenAcceptAsync(response -> publish(tag.name(), response), handlers),
            0, tag.intervalMs(), TimeUnit.MILLISECONDS);
    }
}
subscription.setConsumer(event -> handlers.execute(() -> {
    for (String name : event.getTagNames()) {
        int cot = event.getPlcValue(name).getMetaData("cot").getInt();
        if (cot == 3 || cot == 11 || cot == 12 || cot == 20) {
            publish(name, event.getPlcValue(name));
        }
    }
}));
subscription.build().execute().get(5, TimeUnit.SECONDS);
```

Переподключение: следите за `connection.isConnected()` (или за ошибками `REMOTE_ERROR`)
и при разрыве откройте соединение заново и повторите подписку. После переподключения
драйвер сам отправит GI.

## Ограничения

* Кадр, который сгенерированный парсер не смог разобрать (неподдерживаемый тип ASDU),
  отбрасывается с предупреждением в логе («Error decoding package»), значения из него
  теряются, соединение остаётся.
* Как станция отказывает в команде на неизвестный адрес, зависит от станции: по
  стандарту COT 47 (→ `NOT_FOUND`), но, например, lib60870 отвечает отрицательным
  ACT_CON (→ `REMOTE_ERROR`).
* **Время CP56Time2a** трактуется как UTC. Если станция шлёт местное время, метка
  `timestamp` будет смещена на разницу с UTC.
* Время CP24Time2a (только минуты и миллисекунды) дополняется датой и часом по часам
  клиента, как в upstream.
* Один адрес для GI (`interrogation-asdu-address`). Для нескольких станций за одним
  соединением используйте широковещательный адрес 65535.
* Один клиент — одно соединение; резервные соединения (redundancy groups) не реализованы.

## Тестовый сервер

Для проверки без оборудования: [src/test/docker/iec104-test-server](src/test/docker/iec104-test-server/README.md),
сервер на c104 (lib60870-C) в Docker с фиксированным списком точек.

```bash
docker build -t iec104-test-server src/test/docker/iec104-test-server
docker run --rm -p 2404:2404 iec104-test-server
```
