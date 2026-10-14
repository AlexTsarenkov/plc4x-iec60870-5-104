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
# Сопровождение драйвера IEC 60870-5-104

Документ для того, кто будет поддерживать и дорабатывать драйвер. Руководство
пользователя: [README.md](README.md).

## 1. Происхождение и отличия от upstream

Форк: `AlexTsarenkov/plc4x-iec60870-5-104`, ветка `iec104-read-write`, создана от тега
`v0.13.1` Apache PLC4X. Изменён только модуль `plc4j/drivers/iec-60870`, остальной PLC4X
не тронут (Java 11). Модуль драйвера компилируется под Java 17: свойство
`java.version` в его `pom.xml`.

Почему 0.13.1, а не 1.x: PLC4X 1.x требует Java 21 (виртуальные потоки в TCP-транспорте,
`List.getFirst()` в SPI), а целевая среда выполнения — Java 17.

Что изменено по сравнению с 0.13.1 и почему:

| Было в 0.13.1 | Стало | Почему |
|---------------|-------|--------|
| Адрес тега игнорировался (`parseTag` всегда `0/0`), каждая подписка получала все объекты | Разбор `CA/IOA[:тип]`, wildcard `*`, фильтрация подписок | Без этого подписки бесполезны |
| S-кадр с номером `N(R)+1` вместо `N(S)+1`, только каждые 8 кадров, без таймера t2 | `Iec104LinkLayer`: V(S)/V(R), k, w, t1, t2, t3 | Станция разрывала соединение по своему t1 при редком трафике |
| Нет исходящих I-кадров | Есть, с окном k | Нужны для GI, C_RD и команд |
| Нет GI | GI после STARTDT и после M_EI_NA_1 | Без него нет начальных значений |
| `read`/`write` не поддерживались | Чтение (кэш / C_RD), запись (C_SE_NC/NB/NA, C_SC) | Цель доработки |
| Normalized отдавался как сырой `UINT`, scaled как `UINT` | `REAL` −1..1 и знаковый `INT` | Отрицательные значения были неверными |
| Потребитель из запроса подписки (`setConsumer`) не регистрировался | Регистрируется в `subscribe()` | События не доходили |
| Событие подписки под ключом `tag.toString()` | Под именем тега из запроса | Пользователь узнаёт свои имена |
| `CorruptPackageCleaner` читал байт по абсолютному индексу 0 | Читает по `readerIndex`, проверяет конец буфера | Зацикливание/исключение при мусоре в потоке |
| ASDU с SQ=1 не разбирались (ArrayIndexOutOfBounds) | `Iec104FrameDecoder` переписывает такой кадр в SQ=0 перед парсером | lib60870 и многие RTU отвечают на GI с SQ=1 |

Сгенерированный код (`src/main/generated`) и mspec не менялись.

## 2. Архитектура

```
 Пользователь (PlcConnection API)
        │ read / write / subscribe              ▲ ответы, события подписок
        ▼                                       │
 ┌────────────────────────────────────────────────────────────────┐
 │ Iec608705104Protocol  (protocol/)                              │
 │  - установка связи: TESTFR → STARTDT → GI                       │
 │  - разбор ASDU: мониторинг → ValueCache + подписки + C_RD;      │
 │    ответы на команды → ожидающие write; GI → initialInterrogation│
 │  - ожидающие запросы и их таймауты                              │
 │        │ sendAsdu(ASDU)           ▲ ASDU из I-кадра             │
 │        ▼                          │                              │
 │  Iec104LinkLayer  — номера, k/w, t1/t2/t3 (без Netty, тестируем) │
 └────────│──────────────────────────│──────────────────────────────┘
          ▼ APDU                     │ APDU
 ┌────────────────────────────────────────────────────────────────┐
 │ Netty pipeline PLC4X: GeneratedProtocolMessageCodec            │
 │  байты → Iec104FrameDecoder (SQ=1 → SQ=0) → APDU               │
 │  APDU → байты (код из mspec, src/main/generated)               │
 └────────────────────────────────────────────────────────────────┘
          ▼ TCP
```

### Классы

| Класс | Ответственность |
|-------|-----------------|
| `Iec60870514PlcDriver` | Регистрация в PLC4X: код протокола, возможности (`canRead/Write/Subscribe`), классы конфигурации, сборка Netty-стека, определение длины кадра. |
| `configuration/Iec608705014Configuration` | Параметры строки подключения, значения по умолчанию, проверка (`validate()`). Имя класса с опечаткой «014» оставлено из upstream. |
| `tag/Iec608705104Tag` | Адрес: разбор, wildcard, сравнение (`matches`), каноническая запись. |
| `tag/Iec608705104DataType` | Суффикс типа для записи → команда (TypeIdentification). |
| `tag/Iec608705104TagHandler` | Связка с PLC4X: строка → тег. |
| `protocol/Iec608705104Protocol` | Логика протокола уровня приложения, см. схему. |
| `protocol/Iec104LinkLayer` | Процедуры передачи IEC 104 (раздел 5 стандарта). Не знает про Netty и PLC4X. |
| `protocol/Iec104FrameDecoder` | Разбор кадра для кодека: кадр с SQ=1 переписывается в SQ=0 и отдаётся сгенерированному парсеру. |
| `protocol/Iec104Commands` | Построение ASDU: команды записи, GI, C_RD. Выбор команды по тегу/значению. |
| `protocol/ValueCache` | Последние значения объектов на соединении. |
| `protocol/ValueConversions` | Кодирование normalized/scaled. Одно место для чтения и записи. |
| `protocol/Iec608705104TagParser` | Объект ASDU → `PlcValue` с дескриптором качества (из upstream, исправлены normalized/scaled). |
| `model/Iec608705104SubscriptionHandle` | Подписка: имя тега + фильтр адреса. |
| `messages/Iec608705104PlcSubscriptionEvent` | Событие подписки с фактическим адресом объекта. |

### Потоки

Всё состояние протокола (`linkLayer`, ожидающие запросы) меняется только в event loop
Netty этого соединения. Методы `read/write`, которые PLC4X вызывает из потока
пользователя, передают работу в event loop (`runOnEventLoop`). Таймеры канального уровня
проверяет задача `onTick`, запускаемая каждые 100 мс (`TICK_INTERVAL_MILLIS`) в том же
event loop. Поэтому синхронизация не нужна. Нарушать это правило нельзя: любая новая
работа с `linkLayer` или `pending*` — только через event loop.

### Сопоставление ответов и запросов

В IEC 104 нет идентификаторов запросов.

* Запись: станция повторяет ASDU команды с COT 7 (ACT_CON). Ключ ожидания
  `(TypeIdentification, CA, IOA)`; несколько запросов с одним ключом обслуживаются по
  порядку (`Deque`).
* Чтение C_RD: станция отвечает данными мониторинга с COT 5 по тому же `(CA, IOA)`,
  а при отказе возвращает ASDU C_RD с COT 44–47 или битом P/N.
* GI: ACT_CON, затем данные с COT 20, затем ACT_TERM. Первое окончание GI завершает
  `initialInterrogation`, которого ждут чтения в режиме `cache`.

Каждый ожидающий запрос имеет таймаут `request-timeout`. При закрытии соединения все
ожидающие завершаются с `REMOTE_ERROR` (`onConnectionClosed`).

### Отступление от стандарта

Пропуск номера N(S) принятого I-кадра по стандарту требует закрыть соединение. Драйвер
вместо этого пишет предупреждение и продолжает. Причина: по TCP кадры не теряются, а
пропуск означает, что кодек не смог разобрать кадр (тип или формат, который не
поддерживает mspec, например файловый обмен) и выбросил его. Закрытие соединения в такой
ситуации привело бы к бесконечным переподключениям. Неверный N(R) и истечение t1
соединение закрывают, как требует стандарт.

## 3. Сборка и тесты

### Локально

Нужны JDK 17+, Maven 3.9 (есть `./mvnw`), доступ к Maven Central.

```bash
# один раз: модули, от которых зависит драйвер
./mvnw -P with-java -pl plc4j/drivers/iec-60870 -am install -DskipTests -Drat.skip=true

# тесты драйвера (без Docker)
./mvnw -P with-java -pl plc4j/drivers/iec-60870 test -Drat.skip=true

# плюс тесты совместимости против c104 (нужен запущенный тестовый сервер)
docker build -t iec104-test-server plc4j/drivers/iec-60870/src/test/docker/iec104-test-server
docker run -d --rm -p 2404:2404 --name iec104-test-server iec104-test-server
./mvnw -P with-java -pl plc4j/drivers/iec-60870 verify -Drat.skip=true -Diec104.it.host=127.0.0.1
```

Если `-Drat.skip` не указать, Apache RAT потребует лицензионный заголовок в каждом новом
файле. Все файлы форка его содержат, так что можно и без флага.

### Тесты

| Тест | Что проверяет | Чем |
|------|---------------|-----|
| `Iec608705104TagTest` | синтаксис адресов | — |
| `ValueConversionsTest` | normalized/scaled, знаки | — |
| `Iec104CommandsTest` | байты каждого отправляемого ASDU против кодировок, выписанных по стандарту вручную | сгенерированный сериализатор |
| `Iec104LinkLayerTest` | номера, k/w, t1/t2/t3, переполнение счётчиков | поддельные часы, без сна |
| `Iec104FrameDecoderTest` | развёртывание SQ=1 | реальный кадр ответа lib60870 на GI |
| `Iec608705104DriverTest` | сквозные сценарии через API PLC4X | `Iec104TestStation` (Java) |
| `Iec608705104InteropIT` | совместимость с независимой реализацией | сервер c104 (lib60870-C) в Docker |

`Iec104TestStation` написана на тех же сгенерированных классах, что и драйвер. Ошибку в
mspec она не поймает: обе стороны ошибутся одинаково. Для этого есть
`Iec608705104InteropIT` против lib60870-C и `Iec104CommandsTest` с байтами, выписанными
вручную. Зато тестовая станция умеет вести себя плохо по заказу: не подтверждать кадры,
не отвечать, отказывать.

### CI

Workflow `.github/workflows/iec104-driver.yml` запускается на push в ветки `iec104-*`,
если изменился драйвер, протокол или сам workflow. Шаги: сборка зависимостей → сборка и
запуск c104 в Docker → `verify` драйвера → публикация результатов.

Результаты (логи Maven, логи тестового сервера, отчёты surefire/failsafe, `SUMMARY.txt`)
каждый запуск перезаписывает в отдельную ветку **`ci-results`** (без истории). Их можно
читать без доступа к веб-интерфейсу GitHub:

```bash
git fetch origin ci-results
git show origin/ci-results:SUMMARY.txt
git show origin/ci-results:03-driver-test.log | grep -E "Tests run|FAIL|ERROR"
```

То же лежит в артефакте запуска `iec104-ci-results`.

## 4. Рецепты доработки

### Добавить тип команды (например, двойную команду C_DC_NA_1)

1. `Iec608705104DataType`: новая константа (`DOUBLE("C_DC_NA_1", TypeIdentification.DOUBLE_COMMAND, PlcValueType.USINT)`).
2. `Iec104Commands.createWrite`: новая ветка `case`, строящая
   `InformationObjectWithoutTime_DOUBLE_COMMAND`. Без неё запись вернёт `INVALID_DATA`
   (сработает ветка `default`).
3. Тип 46 уже попадает в диапазон команд 45–69, так что `onCommandAnswer` обработает
   ответ без изменений.
4. `Iec104CommandsTest`: проверка байтов по стандарту.
5. `Iec104TestStation.onAsdu` и `commandValue`: поддержка новой команды; тест в
   `Iec608705104DriverTest`.
6. Тестовый сервер c104 (`server.py`, его README) и `Iec608705104InteropIT`.
7. Таблица в README.md.

### Select-before-operate

Сейчас бит S/E всегда 0 (`Iec104Commands.directExecuteSetPoint`, `QualifierOfCommand(false, 0)`).
Для SBO нужно: отправить команду с S/E=1, дождаться ACT_CON, затем отправить ту же
команду с S/E=0 и ждать второго ACT_CON. Удобнее всего сделать это в
`Iec608705104Protocol.sendCommand` цепочкой из двух ожиданий с одним ключом. Включение —
новым параметром конфигурации или суффиксом адреса.

### Поддержка SQ=1

Сделана обходным путём: `Iec104FrameDecoder` до сгенерированного парсера переписывает
кадр с SQ=1 в эквивалентный кадр с SQ=0, подставляя каждому объекту его адрес (адрес
первого + i). Размер объекта вычисляется из длины кадра и числа объектов.

Правильное решение — в mspec (`protocols/iec-60870/src/main/resources/protocols/iec608705104/iec-60870-5-104.mspec`):
`InformationObject` сейчас всегда читает 3-байтовый адрес. Если когда-нибудь это
исправлять:
1. В `ASDU` передать `structureQualifier` и индекс объекта в `InformationObject` и читать
   `address` условно.
2. Перегенерировать код: `./mvnw -P with-java,update-generated-code -pl plc4j/drivers/iec-60870 -am generate-sources`.
   Сгенерированный код лежит в `src/main/generated` и коммитится вместе с mspec.
3. Удалить `Iec104FrameDecoder` и вернуть `APDU::staticParse` в `Iec60870514PlcDriver.getStackConfigurer()`.
4. `Iec104FrameDecoderTest` (реальный кадр lib60870) превратить в тест парсера.

### Новый параметр конфигурации

1. Поле с `@ConfigurationParameter`, значением по умолчанию и `@Description` в
   `Iec608705014Configuration`, геттер, при необходимости проверка в `validate()`.
2. Использование в протоколе.
3. Строка в таблице параметров README.md.

### Переход на новую версию PLC4X

Изменения сосредоточены в одном модуле, поэтому перенос — это перенос каталога
`plc4j/drivers/iec-60870` (кроме `src/main/generated`, который перегенерируется). Учтите:
* PLC4X 1.x требует Java 21 и сменил SPI (`ConnectionBase` вместо Netty-стека). Класс
  `Iec104LinkLayer` переносится без изменений, `Iec608705104Protocol` придётся
  переписать под новый SPI.
* В upstream 1.x своя реализация разбора адресов (с октетной записью) и исправления
  подтверждений, но нет чтения и записи.

## 5. Диагностика

Логи (SLF4J):

| Логгер | Уровень | Что видно |
|--------|---------|-----------|
| `...protocol.Iec104LinkLayer` | WARN | закрытие соединения (t1, неверный N(R)), пропуски N(S) |
| `...protocol.Iec104LinkLayer` | DEBUG | TESTFR по t3, игнорируемые U-кадры |
| `...protocol.Iec608705104Protocol` | WARN | отказ/таймаут GI, нет ответа на команду, ошибка обработки ASDU |
| `...protocol.Iec608705104Protocol` | DEBUG | все прочие решения: неизвестные ответы, отклонённые чтения, параметры соединения |
| `org.apache.plc4x.java.spi.GeneratedDriverByteToMessageCodec` | WARN | кадр, который не удалось разобрать, в hex |

Кадр из лога в hex можно разобрать вручную по таблице:
`68 <длина> <4 байта управления> <тип> <VSQ> <COT> <OA> <CA lo> <CA hi> <IOA 3 байта> ...`.

## 6. Термины

| Термин | Значение |
|--------|----------|
| APDU | кадр: стартовый байт 0x68, длина, 4 байта управления, опционально ASDU |
| I-кадр | кадр с данными (ASDU), нумеруется N(S)/N(R) |
| S-кадр | только подтверждение, N(R) |
| U-кадр | управление: STARTDT, STOPDT, TESTFR (act/con) |
| ASDU | прикладной блок: тип, VSQ, причина передачи, адрес станции, объекты |
| CA | Common Address of ASDU, адрес станции |
| IOA | Information Object Address, адрес объекта |
| COT | Cause of Transmission, причина передачи |
| ACT / ACT_CON / ACT_TERM | активация / её подтверждение / завершение |
| GI | General (station) Interrogation, C_IC_NA_1 с QOI 20 |
| SQ | бит VSQ: объекты идут подряд с одним адресом |
| k, w, t1, t2, t3 | параметры процедур передачи, см. README.md |
