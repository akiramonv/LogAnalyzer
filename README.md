# log-analyzer — разбор логов Java/Spring и поиск причины сбоя

Инструмент читает «сырые» логи приложения, собирает события одного запроса в **таймлайн инцидента**
и называет **вероятную первопричину** с оценкой достоверности.

Работает с тем, что реально пишут приложения: текстовые логи Logback/Log4j2, JSON-логи
(logstash-encoder, ECS, Log4j2 JSON), многострочные стек-трейсы с цепочкой `Caused by`
и HTTP-дампы — в том числе вперемешку в одном файле.

```
log-analyzer analyze -i app.log --only-failed
```

```
[1] traceId 8f3c2a1b4d5e6f70
    событий: 6, длительность: 64 мс, ошибок: 2, сервисы: order-service
    ┌ Вероятная причина (95%): Обращение к null-ссылке (NullPointerException)
    │ Что делать: Проверьте источник значения: незаполненное поле DTO, отсутствующая
    │ запись в БД, не сработавшая инъекция зависимости или ответ внешнего сервиса...
    │ Основание: e5, e4
    │ Правило: null-pointer
    └
    e1  +0 мс   13:04:11.204  INFO  c.e.o.web.OrderController  Получен запрос POST /api/v1/orders
    e4  +52 мс  13:04:11.256  WARN  c.e.o.s.PricingService     Профиль скидок не найден customerId=4417
    e5  +57 мс  13:04:11.261  ERROR c.e.o.web.OrderController  Не удалось обработать заказ orderId=88213
          ⟨EXCEPTION: NullPointerException⟩
          java.lang.NullPointerException: Cannot invoke "DiscountProfile.rate()" because "profile" is null
              at com.example.orders.service.PricingService.applyDiscount(PricingService.java:88)
```

## Что он делает

| Шаг | Что происходит |
|---|---|
| **Разбор** | Строки превращаются в события: время, уровень, логгер, поток, traceId/spanId, исключение, HTTP-обмен. Многострочные блоки прикрепляются к своей записи, а не разрываются. |
| **Корреляция** | События одного запроса собираются в цепочку по `traceId` → `requestId` → активному контексту потока → `sessionId` → потоку (с разрезом по паузам). |
| **Таймлайн** | События сортируются по времени, повторы схлопываются («×3»), считаются паузы, HTTP-запрос связывается с ответом. |
| **Правила** | Встроенный набор (**55 правил**) помечает события: таймауты, ретраи, исчерпание пула соединений, OOM, конфликты версий, ошибки TLS, Kafka, Redis, Spring-контекста и др. Свои правила добавляются YAML-файлом. |
| **Причина** | Цепочка `Caused by` разворачивается до первопричины, учитываются каскад ошибок и предшествующие таймауты. В отчёт попадает не ярлык, а разбор: что произошло и почему, обстоятельства инцидента (сервис, через сколько случился сбой, сколько было повторов), доказательства (`e5`, `e4`) и **пошаговый план проверки и устранения**. |
| **Отчёт** | Текст для консоли, JSON для CI, самодостаточный HTML с интерактивным таймлайном, Mermaid-диаграмма для задачи/вики. |

## Запуск

Нужен JDK 21 или новее. Gradle качать не требуется — используется wrapper.
Проще всего пользоваться скриптом-обёрткой: он сам соберёт проект при первом запуске.

**Windows (PowerShell или cmd), из папки проекта:**

```bash
.\log-analyzer.cmd analyze -i samples\spring-boot-npe.log
```

**Linux / macOS:**

```bash
chmod +x log-analyzer
./log-analyzer analyze -i samples/spring-boot-npe.log
```

Скрипт использует JDK из `JAVA_HOME` (в `PATH` часто лежит старая Java, которая не запустит сборку).

<details>
<summary>Без скрипта — вручную</summary>

```bash
./gradlew build                                    # или .\gradlew.bat build
java -jar cli/build/libs/log-analyzer.jar analyze -i app.log
```

Если `java` в `PATH` старая, укажите путь явно:
`& "$env:JAVA_HOME\bin\java.exe" -jar cli\build\libs\log-analyzer.jar ...`
</details>

Чтобы запускать из любой папки, добавьте алиас:

```bash
# PowerShell (profile.ps1)
function log-analyzer { & "C:\путь\к\проекту\log-analyzer.cmd" @args }

# bash / zsh
alias log-analyzer='/путь/к/проекту/log-analyzer'
```

## Запуск одним файлом

В корне проекта лежит единая точка входа — она сама соберёт проект при первом запуске
и откроет интерфейс:

| Система | Файл | Как запустить |
|---|---|---|
| Windows | `start.cmd` | двойной клик в проводнике или `.\start.cmd` в консоли |
| Linux / macOS | `start.sh` | `chmod +x start.sh` один раз, затем `./start.sh` |

Ключи команды `ui` передаются насквозь: `.\start.cmd --port 9000 --no-browser`.

Остальные команды (для консоли и CI) — через `log-analyzer.cmd` / `log-analyzer`.

## Интерфейс

```bash
.\start.cmd
```

Откроется браузер с локальным интерфейсом (`http://localhost:8321`).

**Раздел «Анализ».** Слева — два шага: «Откуда взять лог» (вставить текст либо указать файл
или каталог на диске) и «Что показать» (только с ошибками, уровень не ниже, сколько инцидентов,
конкретный traceId). Справа — сводка (инциденты, ошибки, предупреждения, события), поиск
по событиям и карточки инцидентов: бейдж состояния, вероятная причина с полосой уверенности,
шкала времени с отметками событий и таблица событий с раскрывающимися стек-трейсами.
Кнопка «Скачать отчёт» сохраняет самодостаточный HTML.

**Раздел «Правила»** — все 55 правил с условиями, формулировкой причины и уверенностью.

**Раздел «Форматы логов»** — встроенные шаблоны строк и проверка своей строки: вставьте её
в поле и нажмите Enter, чтобы увидеть, какой шаблон сработал и что из неё извлеклось.

Сервер слушает **только 127.0.0.1** — снаружи он недоступен. Порт меняется ключом
`--port`, автозапуск браузера отключается `--no-browser`. Остановка — `Ctrl+C`.

## Два способа дать логи (в консоли)

### 1. Целые файлы и каталоги

```bash
# один файл, только инциденты с ошибками
log-analyzer analyze -i app.log --only-failed

# каталог логов, рекурсивно, три самых тяжёлых инцидента
log-analyzer analyze -i /var/log/myapp -r --top 3

# несколько источников и маска
log-analyzer analyze -i app.log -i "logs/*.log" -i /var/log/myapp -r

# конвейер
kubectl logs deploy/order-service --tail=5000 | log-analyzer analyze --stdin --only-failed
```

### 2. Отдельный отрывок — вставить и разобрать

Отрывку не нужны ни метки времени, ни `traceId`: голый стек-трейс тоже разбирается.

```bash
# вставить в консоль: завершить ввод Ctrl+Z и Enter (Windows) или Ctrl+D (Linux/macOS)
log-analyzer analyze --paste

# взять из буфера обмена — самый быстрый способ
log-analyzer analyze --clipboard

# одной строкой в аргументе
log-analyzer analyze --text "2026-08-09 10:00:00 ERROR c.e.App - java.lang.OutOfMemoryError: Java heap space"
```

> Многострочный текст в `--text` нельзя передать через `.cmd`-обёртку (ограничение `cmd.exe`) —
> для многострочных отрывков используйте `--paste` или `--clipboard`.

Команда `prompt` тоже принимает отрывок: `log-analyzer prompt --text "<фрагмент>"`.

### Полезные ключи

```bash
# HTML-отчёт с кликабельным таймлайном
log-analyzer analyze -i app.log -f html -o report.html

# JSON для CI: код возврата 3, если найдены ошибки
log-analyzer analyze -i build/logs -f json -o report.json --fail-on-error

# конкретный инцидент по traceId
log-analyzer analyze -i app.log --trace 8f3c2a1b

# окно времени и порог уровня
log-analyzer analyze -i app.log --since 2026-08-09T10:00:00 --until 2026-08-09T11:00:00 --min-level WARN
```

## Команды

| Команда | Назначение |
|---|---|
| `ui` | Локальный веб-интерфейс в браузере: вставка отрывка, выбор файла, таймлайн и причина. |
| `analyze` | Основной разбор: таймлайны и причины. Источник: `-i` (файлы/каталоги), `--stdin`, `--paste`, `--clipboard`, `--text`. |
| `rules --list` / `--details` | Показать правила; `--export rules.yaml` — выгрузить встроенные как основу для своих. |
| `patterns --list` / `--test "строка"` | Проверить, распознаётся ли формат ваших логов. **Первое, что стоит запустить, если разбор неточен.** |
| `prompt -i app.log` | Сформировать запрос к языковой модели по инциденту (описание хронологии + задание). Никуда не ходит по сети — результат можно вставить в чат с моделью. |

Полный список ключей: `log-analyzer analyze --help`.

## Если ваш формат логов не распознаётся

Проверьте строку:

```bash
log-analyzer patterns --test "2026-08-09 10:00:00,123 [main] INFO  com.example.App - Запуск"
```

Если ни один шаблон не подошёл — добавьте свой в конфигурацию (regex с именованными группами
`ts`, `level`, `thread`, `logger`, `msg`, `trace`, `app`, `pid`):

```yaml
# analyzer.yaml
parse:
  zone: Europe/Moscow
  patterns:
    - name: my-format
      regex: '^(?<ts>\S+)\|(?<level>\w+)\|(?<logger>[^|]+)\|(?<msg>.*)$'
```

```bash
log-analyzer analyze -i app.log -c analyzer.yaml
```

Проверить свой regex можно сразу: `log-analyzer patterns --test "строка" --regex '...'`.

## Свои правила

```bash
log-analyzer rules --export my-rules.yaml   # взять встроенные за основу
log-analyzer analyze -i app.log --rules my-rules.yaml
```

Правило описывает, что искать и какой вывод из этого следует:

```yaml
name: my-rules
rules:
  - name: payment-declined
    description: Банк отклонил операцию
    priority: 80
    when:
      messageRegex: 'declined by issuer'
    annotate:
      - type: EXTERNAL_CALL
        label: Отказ банка
    cause:
      title: Платёж отклонён банком-эмитентом
      confidence: 0.85
      recommendation: Проверьте код отказа в ответе шлюза и лимиты карты.

  # «Ошибка после серии повторов» — условие по предшествующим событиям
  - name: error-after-retries
    when:
      levelAtLeast: ERROR
    precededBy:
      withinSeconds: 120
      when:
        messageRegex: 'Retry attempt'
    cause:
      title: Операция окончательно провалилась после повторов
      confidence: 0.7
```

Опечатка в имени поля — ошибка при загрузке, а не молча неработающее правило.
Справочник всех полей: [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Форматы отчёта

| `-f` | Для чего |
|---|---|
| `text` (по умолчанию) | Чтение глазами в консоли. |
| `json` | CI, дашборды, передача в другие инструменты. Структура: `summary`, `timelines[]` с `entries[]` и `rootCause`. |
| `html` | Одна самодостаточная страница: шкала инцидента, поиск, фильтр «только ошибки», раскрывающиеся стеки. Без внешних ресурсов — можно приложить к задаче. |
| `mermaid` | Gantt-диаграмма для вставки в Markdown, задачу или вики. |

## Использование как библиотеки

```java
AnalyzerConfig config = AnalyzerConfig.defaults();
config.getAnalysis().getApplicationPackages().add("com.mycompany");

AnalysisReport report = new LogAnalyzer(config).analyzeFiles(List.of(Path.of("app.log")));

for (Timeline timeline : report.getTimelines()) {
    if (timeline.isFailed()) {
        RootCause cause = timeline.getRootCause();
        System.out.printf("%s -> %s (%.0f%%)%n",
                timeline.getCorrelationId(), cause.getTitle(), cause.getConfidence() * 100);
    }
}
```

Модуль `core` не зависит ни от CLI, ни от какого-либо UI — его одинаково использует
консольная команда и (в будущем) плагин IDE.

## Структура проекта

```
core/    модель, парсеры, корреляция, таймлайн, правила, анализ причин, отчёты
cli/     консольные команды на picocli + локальный веб-интерфейс (пакет cli/ui)
samples/ примеры логов для демонстрации
docs/    архитектура, справочник конфигурации, запуск в IntelliJ IDEA
```

## Документация

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — устройство, поток данных, диаграммы, алгоритмы.
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) — все параметры конфигурации и формат правил.
- [docs/IDEA.md](docs/IDEA.md) — запуск и отладка в IntelliJ IDEA, задел под плагин.

## Статус

Готово и покрыто тестами (94 теста): разбор всех перечисленных форматов, корреляция,
таймлайн, правила, анализ причин с развёрнутым объяснением и планом действий,
четыре формата отчёта, CLI и локальный веб-интерфейс с разделами «Анализ», «Правила»
и «Форматы логов».

Не сделано: плагин IntelliJ IDEA (ядро и веб-интерфейс к нему готовы — см. [docs/IDEA.md](docs/IDEA.md))
и автоматический вызов языковой модели (есть команда `prompt`, формирующая запрос).
