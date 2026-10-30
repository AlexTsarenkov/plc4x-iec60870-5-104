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
| General Interrogation (C_IC_NA_1) после подключения и после M_EI_NA_1 (если опрос уже не идёт) | да, отключается |
| Чтение (`read`) из кэша значений | да, по умолчанию |
| Чтение командой C_RD_NA_1 | да, `read-mode=command` |
| Запись (`write`): все команды направления управления, см. «Запись» | да |
| — уставки C_SE_NA/NB/NC_1, одиночная C_SC_NA_1, двойная C_DC_NA_1, шаговая C_RC_NA_1, битовая строка C_BO_NA_1 | да |
| — те же команды с меткой времени CP56Time2a (C_SC_TA_1 … C_BO_TA_1) | да |
| — параметры P_ME_NA/NB/NC_1, P_AC_NA_1 | да |
| — системные: опрос C_IC_NA_1 (в т. ч. группы), опрос счётчиков C_CI_NA_1, синхронизация часов C_CS_NA_1, тест C_TS_NA_1/C_TS_TA_1, сброс C_RP_NA_1, задержка C_CD_NA_1 | да |
| Прямое исполнение и select-before-operate | да, параметр подключения и опция тега |
| Ожидание ACT_TERM | да, `wait-for-termination` |
| Подписки (`subscribe`) с фильтром по адресу и wildcard | да |
| ASDU с SQ=1 (последовательность объектов с одним адресом) | да |
| Часовой пояс меток времени станции | да, `time-zone` |
| Передача файлов, Browse | нет |
| Автоматическое переподключение | нет, это задача приложения |

## Как подключить к своему проекту

Драйвер публикуется как `com.labsp.plc4x:plc4j-driver-iec-60870:0.13.1-iec104.2`. Он
зависит от официальных `plc4j-api`, `plc4j-spi` и `plc4j-transport-tcp` версии 0.13.1 из
Maven Central, они подтянутся сами.

1. Соберите и установите драйвер в локальный репозиторий, см. MAINTENANCE.md, «Сборка».
2. В проекте:

```xml
<dependency>
  <groupId>com.labsp.plc4x</groupId>
  <artifactId>plc4j-driver-iec-60870</artifactId>
  <version>0.13.1-iec104.2</version>
</dependency>
```

groupId у форка свой (`com.labsp.plc4x`), Java-пакеты и код подключения те же, что у PLC4X.

Официального `org.apache.plc4x:plc4j-driver-iec-60870` в проекте быть не должно, даже
транзитивно (например, через `plc4j-drivers-all`). Из-за разных groupId Maven считает их
разными артефактами и положит в classpath оба; оба регистрируются под кодом
`iec-60870-5-104`, и `DefaultPlcDriverManager` при создании падает с
`Multiple driver implementations available for protocol code 'iec-60870-5-104'`.
Проверка: `mvn dependency:tree -Dincludes=:plc4j-driver-iec-60870` должен показать только
`com.labsp.plc4x`. Официальный исключается так:

```xml
<dependency>
  <groupId>org.apache.plc4x</groupId>
  <artifactId>plc4j-drivers-all</artifactId>
  <version>0.13.1</version>
  <exclusions>
    <exclusion>
      <groupId>org.apache.plc4x</groupId>
      <artifactId>plc4j-driver-iec-60870</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

### Публикация в корпоративный Nexus

Драйвер выкладывается в Nexus один раз на каждую версию, из клона форка:

```bash
./mvnw -P with-java -pl plc4j/drivers/iec-60870 deploy -DskipTests \
  -DaltDeploymentRepository=corp-nexus::https://<nexus>/repository/<releases>/
```

Учётные данные — в `~/.m2/settings.xml` того, кто выкладывает, с тем же id:

```xml
<servers>
  <server>
    <id>corp-nexus</id>
    <username>...</username>
    <password>...</password>
  </server>
</servers>
```

Выкладываются jar, sources jar, pom и kar (архив для Karaf, проекту он не нужен), в
`com/labsp/plc4x/plc4j-driver-iec-60870/<версия>/`. Модуль `protocols/iec-60870` выкладывать
не нужно: он нужен только для перегенерации кода из mspec.
Родительские pom PLC4X 0.13.1 и `plc4j-api`/`plc4j-spi`/`plc4j-transport-tcp` выкладывать не
нужно, если Nexus проксирует Maven Central (обычно так и есть).

Release-репозиторий Nexus не даёт перезаписать версию. Для каждой новой сборки
увеличивайте номер (`0.13.1-iec104.3`, ...), см. MAINTENANCE.md, «Координаты и версии».

### Сборка fat jar через maven-shade-plugin

PLC4X находит драйверы и транспорты через `ServiceLoader`, то есть через файлы
`META-INF/services/...` в jar'ах (`org.apache.plc4x.java.api.PlcDriver`,
`org.apache.plc4x.java.spi.transport.Transport`). Shade по умолчанию оставляет только
один из одноимённых файлов, и тогда `PlcDriverManager` не найдёт драйвер для
`iec-60870-5-104` или TCP-транспорт, хотя классы в jar есть. Нужен
`ServicesResourceTransformer`, он склеивает эти файлы:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
          <!-- ваш ManifestResourceTransformer с mainClass и прочие -->
        </transformers>
        <filters>
          <filter>
            <!-- подписи чужих jar'ов в fat jar недействительны -->
            <artifact>*:*</artifact>
            <excludes>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Проверить готовый jar:

```bash
unzip -p target/<ваш>.jar META-INF/services/org.apache.plc4x.java.api.PlcDriver
# должна быть строка org.apache.plc4x.java.iec608705104.readwrite.Iec60870514PlcDriver
unzip -p target/<ваш>.jar META-INF/services/org.apache.plc4x.java.spi.transport.Transport
# должна быть строка org.apache.plc4x.java.transport.tcp.TcpTransport
```

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
| `select-before-operate` | false | Отправлять команды с битом S/E как select-before-operate (выбор, затем исполнение), если тег не говорит `direct`. |
| `wait-for-termination` | false | Запись завершается только по ACT_TERM станции, а не по ACT_CON. Действует для команд, которые станция завершает ACT_TERM (процессные команды, опросы). |
| `termination-timeout-ms` | 30000 | При `wait-for-termination`: сколько ждать ACT_TERM после ACT_CON. |
| `time-zone` | UTC | Часовой пояс меток времени станции (CP56Time2a/CP24Time2a), принимаемых и отправляемых: `UTC`, `local` (пояс JVM) или идентификатор, например `Europe/Moscow`. |
| `originator-address` | 0 | Адрес источника (0..255) во всех ASDU клиента. |

Таймеры и `k`/`w` должны совпадать с настройками станции. Почти всегда это значения
по умолчанию из стандарта. Некорректная комбинация (например, `t2-ms >= t1-ms`) приводит
к ошибке при подключении, а не к странному поведению потом.

## Адреса тегов

```
<CA>/<IOA>[:<тип>[,<опция>]...]
```

* `CA` — общий адрес ASDU (адрес станции), десятичное `0..65535` или `*`.
* `IOA` — адрес информационного объекта, десятичное `0..16777215` или `*`.
* `тип` — необязателен, нужен только для записи: какой командой писать (см. «Запись»).
  Мнемоника стандарта (`C_SE_NC_1`) или короткое имя (`FLOAT`), регистр не важен.
* `опция` — только после типа, через запятую, в любом порядке (см. «Опции команд»).

| Адрес | Значение |
|-------|----------|
| `1/100` | станция 1, объект 100 |
| `1/300:C_SE_NC_1` | запись уставки float в объект 300 (то же, что `1/300:FLOAT`) |
| `1/300:C_SE_TC_1,sbo` | то же с меткой времени и select-before-operate |
| `1/0:C_CS_NA_1` | синхронизация часов станции 1 |
| `1/*` | все объекты станции 1 (только подписки) |
| `*/*` | всё, что приходит по соединению (только подписки) |

Каноническая запись адреса (в логах, `getAddressString()`) использует мнемонику
стандарта и опции в фиксированном порядке: `1/500:C_DC_NA_1,sbo,qu=1`.

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
    .addTagAddress("setpoint", "1/300", 42.5f)             // C_SE_NC_1 (по значению)
    .addTagAddress("limit",    "1/310:C_SE_NB_1", 1200)     // уставка scaled
    .addTagAddress("breaker",  "1/500:C_DC_NA_1,sbo", true) // двойная команда, выбор + исполнение
    .addTagAddress("tap",      "1/600:C_RC_NA_1", -2)       // два шага вниз
    .addTagAddress("clock",    "1/0:C_CS_NA_1", 0)          // синхронизация часов
    .build().execute().get(5, TimeUnit.SECONDS);
```

В IEC 104 нет «регистров». Запись — это команда станции на **объект управления**,
который станция должна быть настроена принимать. Записать в объект измерения (M_ME_*)
протокол не позволяет. Обычно у станции есть пара адресов: команда (например, C_SE) и
её обратная связь (M_ME).

### Какая команда отправляется

1. Если в адресе указан тип, используется он.
2. Иначе `boolean` записывается как C_SC_NA_1,
3. а любое число — как C_SE_NC_1 (float).

Тип должен совпадать с тем, что настроено на станции для этого IOA, иначе станция
откажет (COT 44 → `INVALID_DATATYPE`, или отрицательный ACT_CON → `REMOTE_ERROR`).

### Команды и их значения

**Процессные команды.** Варианты с меткой времени CP56Time2a выбираются мнемоникой
(второй столбец) или опцией `time`: `1/300:FLOAT,time` = `1/300:C_SE_TC_1`. Метка — текущее
время в поясе `time-zone`.

| Короткое имя | Мнемоника (с меткой) | Команда | Значение |
|---|---|---|---|
| `BOOL` | `C_SC_NA_1` (`C_SC_TA_1`) | одиночная команда | `true`/`false` или 1/0 |
| `DOUBLE` | `C_DC_NA_1` (`C_DC_TA_1`) | двойная команда (выключатели) | `true` = вкл, `false` = откл, или строки `"ON"`/`"OFF"`. Числа **не принимаются**: на линии 1 = откл, а «1» обычно понимают как «вкл» |
| `STEP` | `C_RC_NA_1` (`C_RC_TA_1`) | шаговая команда (РПН) | число шагов: `+n` — n шагов выше, `−n` — ниже, до 64 |
| `NORMALIZED` | `C_SE_NA_1` (`C_SE_TA_1`) | уставка, нормализованная | −1.0 .. 1−2⁻¹⁵ (0.99997) |
| `SCALED` | `C_SE_NB_1` (`C_SE_TB_1`) | уставка, масштабированная | целое −32768..32767 |
| `FLOAT` | `C_SE_NC_1` (`C_SE_TC_1`) | уставка, float 32 бит | число в диапазоне float |
| `BITSTRING` | `C_BO_NA_1` (`C_BO_TA_1`) | битовая строка 32 бит | целое; отправляются младшие 32 бита (−1 = все единицы) |

**Шаговая команда.** На линии одна команда C_RC — это ровно один шаг. Значение `+3`
драйвер отправляет как три команды «выше» подряд, каждую — только после
подтверждения предыдущей (и после её ACT_TERM, если включено `wait-for-termination`;
для РПН это рекомендуется, потому что переключение идёт секунды). Если станция
откажет на каком-то шаге, оставшиеся не отправляются, запись возвращает ошибку, а в
лог пишется, сколько шагов выполнено. Атомарности нет: выполненные шаги остаются.

**Параметры.**

| Короткое имя | Мнемоника | Команда | Значение |
|---|---|---|---|
| `PARAM_NORMALIZED` | `P_ME_NA_1` | параметр измерения, нормализованный | как у уставки normalized |
| `PARAM_SCALED` | `P_ME_NB_1` | параметр измерения, масштабированный | как у уставки scaled |
| `PARAM_FLOAT` | `P_ME_NC_1` | параметр измерения, float | число |
| `PARAM_ACTIVATION` | `P_AC_NA_1` | активация параметра | QPA, 0..255 (например, 3 — включить циклическую передачу объекта) |

Вид параметра (порог, сглаживание, пределы) задаётся опцией `kpa`, по умолчанию порог.

**Системные команды.** Обычно отправляются на IOA 0: `1/0:<мнемоника>`.

| Короткое имя | Мнемоника | Команда | Значение |
|---|---|---|---|
| `INTERROGATION` | `C_IC_NA_1` | опрос | QOI: 20 — общий опрос, 21..36 — группа 1..16 |
| `COUNTER_INTERROGATION` | `C_CI_NA_1` | опрос счётчиков | RQT: 1..4 — группа, 5 — общий; заморозка — опцией `frz` |
| `CLOCK_SYNC` | `C_CS_NA_1` | синхронизация часов | дата/время (`LocalDateTime`, в поясе `time-zone`) или что угодно другое (например `0`) — тогда текущее время |
| `TEST` | `C_TS_NA_1` (`C_TS_TA_1`) | тестовая команда | игнорируется. В IEC 104 используется `C_TS_TA_1` (счётчик + время), `C_TS_NA_1` многие станции не принимают |
| `RESET_PROCESS` | `C_RP_NA_1` | сброс процесса | QRP: 1 — общий сброс, 2 — сброс буфера событий |
| `DELAY_ACQUISITION` | `C_CD_NA_1` | определение задержки | мс 0..59999. Из IEC 101, в профиль 104 не входит |

Ответы на опросы (значения с COT 20..36, счётчики с COT 37..41) попадают в кэш и в
подписки, как любые другие данные. Чтобы при записи опроса значения уже были в кэше,
когда запись завершилась, включите `wait-for-termination`. Команды на широковещательный
адрес (`65535/0:C_IC_NA_1`) завершаются ответом первой ответившей станции.

### Опции команд

| Опция | Для типов | Значение |
|---|---|---|
| `time` | процессные, `TEST` | вариант с меткой времени CP56Time2a |
| `sbo` / `direct` | `BOOL`, `DOUBLE`, `STEP`, уставки | select-before-operate или прямое исполнение; без опции — по параметру `select-before-operate` |
| `qu=` | `BOOL`, `DOUBLE`, `STEP` | квалификатор команды (длительность импульса): `0`/`none` — по умолчанию станции, `1`/`short`, `2`/`long`, `3`/`persistent`, до 31 |
| `ql=` | уставки | квалификатор уставки, 0..127 |
| `kpa=` | `PARAM_*` | вид параметра: `1`/`threshold` (по умолчанию), `2`/`smoothing`, `3`/`low`, `4`/`high`, до 63 |
| `frz=` | `COUNTER_INTERROGATION` | `0`/`read` (по умолчанию), `1`/`freeze`, `2`/`freeze_reset`, `3`/`reset` |
| `deact` | `PARAM_ACTIVATION` | деактивация (COT 8) вместо активации |

Опция, неприменимая к типу, — ошибка адреса (`PlcInvalidTagException`).

### Как проходит команда

```
прямое исполнение:      команда ──► ACT_CON ──► готово
                                       └─ (wait-for-termination) ──► ACT_TERM ──► готово
select-before-operate:  выбор (S/E=1) ──► ACT_CON ──► исполнение (S/E=0) ──► как выше
```

* Каждый этап ждёт ответа не дольше `request-timeout` (ACT_TERM — `termination-timeout-ms`).
* Если станция отказала в выборе, исполнение не отправляется.
* ACT_TERM ждётся только у команд, которые станция им завершает: процессные команды и
  опросы. Синхронизация часов, тест, сброс и параметры завершаются по ACT_CON.
* Несколько тегов в одном запросе отправляются одновременно, не по очереди.
* Новое значение объекта мониторинга станция обычно присылает **после** ACT_CON (COT 11).
  Чтение из кэша сразу после записи может ещё вернуть старое значение. Если нужно
  читать сразу, включите `wait-for-termination` (станция шлёт ACT_TERM после обновления)
  или подпишитесь на объект.

### Коды ответа записи

| Код | Когда |
|-----|-------|
| `OK` | станция подтвердила (ACT_CON, при `wait-for-termination` — ACT_TERM) |
| `REMOTE_ERROR` | отрицательный ACT_CON (станция отказала), нет ответа вовремя, соединение потеряно |
| `NOT_FOUND` | станция ответила «неизвестный CA» (COT 46) или «неизвестный IOA» (COT 47) |
| `INVALID_DATATYPE` | станция ответила «неизвестный тип» (COT 44): не тот тип в адресе |
| `INVALID_ADDRESS` | адрес с `*`; ничего не отправлялось |
| `INVALID_DATA` | значение не подходит команде (вне диапазона, 0 шагов, число для `DOUBLE`); ничего не отправлялось |

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
| `timestamp` | LDATE_AND_TIME (UTC) | метка времени объекта (в поясе `time-zone`, приведённая к UTC) или время приёма, если метки нет |
| `invalid`, `notTopical`, `substituted`, `blocked` | BOOL | дескриптор качества |
| `overflow` | BOOL | переполнение (для измерений) |

**Проверяйте качество.** Значение с `invalid=true` выглядит так же, как достоверное.

### Значения по типам мониторинга

| Тип станции | Значение в PLC4X |
|---|---|
| M_SP (одиночный сигнал) | BOOL |
| M_DP (двойной сигнал) | USINT: 0 — промежуточное, 1 — откл, 2 — вкл, 3 — неопределённое |
| M_ST (положение РПН) | SINT −64..63; метаданные `transientState` — идёт переключение |
| M_BO (битовая строка) | DWORD |
| M_ME_NA (нормализованное) | REAL −1..1 |
| M_ME_NB (масштабированное) | INT |
| M_ME_NC (float) | REAL |
| M_IT (счётчик) | UDINT; метаданные `counterValid`, `counterAdjusted`, `carry`, `sequenceNumber` |
| M_PS (16 сигналов с признаком изменения) | STRUCT `status`, `changed` (WORD, бит i — сигнал i) |
| M_EP_TA/TD (событие защиты) | USINT состояние события; метаданные качества и `elapsedTime` (мс) |
| M_EP_TB/TE (пуск защиты) | STRUCT флагов пуска по фазам; метаданные `relayDurationTime` (мс) |
| M_EP_TC/TF (выходные цепи защиты) | STRUCT флагов по фазам; метаданные `relayOperatingTime` (мс) |

Частые значения `cot`:

| COT | Значение |
|----:|----------|
| 1 | периодически (periodic) |
| 2 | фоновое сканирование (background) |
| 3 | спонтанно (spontaneous), т. е. изменение |
| 5 | по запросу (ответ на C_RD) |
| 11 | обратная информация по удалённой команде |
| 12 | обратная информация по местной команде |
| 20 | ответ на общий опрос (21..36 — на опрос группы 1..16) |
| 37 | ответ на общий опрос счётчиков (38..41 — группы 1..4) |

Подписчику приходят значения с любым из этих `cot`; драйвер их не фильтрует. Если
фильтруете сами, перечисляйте то, что отбрасываете, а не то, что пропускаете: белый список
легко теряет данные. Например, без 37 не дойдут счётчики (M_IT обычно приходят только по
опросу счётчиков), без 1 — измерения станций, которые шлют их только периодически, без
21..36 — ответы на опрос групп, без 5 — ответы на C_RD (`read-mode=command`), которые тоже
рассылаются подписчикам.

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

* `change`: подписка. Подписчику приходит всё, что прислала станция по этому адресу, с
  любой причиной передачи (`cot`, см. «Метаданные значения»). В наброске отброшены только
  периодические (1) и фоновые (2) передачи: это не изменения.
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
        PlcValue value = event.getPlcValue(name);
        int cot = value.getMetaData("cot").getInt();
        // 1 (периодически) и 2 (фон) не означают изменения. Уберите это условие, если
        // станция передаёт какие-то значения только периодически, иначе они не дойдут.
        if (cot == 1 || cot == 2) {
            continue;
        }
        publish(name, value);
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
* **Часовой пояс меток времени** берётся из `time-zone` и должен совпадать с настройкой
  станции. Если станция шлёт местное время, а `time-zone` оставлен `UTC`, метки будут
  смещены на разницу с UTC, а команды с меткой времени станция может счесть устаревшими.
* Время CP24Time2a (только минуты и миллисекунды) дополняется часом и датой по часам
  клиента; если так получается больше чем на полчаса в будущем, берётся предыдущий час.
* Шаговая команда на n шагов — это n команд, не атомарно (см. «Запись»).
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
