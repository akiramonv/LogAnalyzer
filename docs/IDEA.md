# Работа в IntelliJ IDEA

## Открыть проект

`File → Open` → выбрать корневую папку проекта. IDEA подхватит Gradle-проект сама.
В `File → Project Structure → SDK` должен стоять JDK 21 или новее (проект собирается
toolchain-ом 21, поэтому подойдёт и более новый JDK — Gradle найдёт нужный сам).

## Запуск CLI из IDE

**Вариант 0 — из терминала IDE (самый быстрый).**

```bash
.\log-analyzer.cmd ui                                          # интерфейс в браузере
.\log-analyzer.cmd analyze -i samples\spring-boot-npe.log      # консольный отчёт
```

Скрипт при первом запуске соберёт проект сам и возьмёт JDK из `JAVA_HOME`.
Для интерфейса можно завести отдельную Run Configuration типа Application
с main-классом `io.github.loganalyzer.cli.LogAnalyzerCli` и аргументом `ui`.

**Вариант 1 — Run Configuration типа Application.**

| Поле | Значение |
|---|---|
| Main class | `io.github.loganalyzer.cli.LogAnalyzerCli` |
| Module | `log-analyzer.cli.main` |
| Program arguments | `analyze -i samples/spring-boot-npe.log --only-failed` |
| Working directory | корень проекта |
| VM options | `-Dfile.encoding=UTF-8` (Windows: иначе русский текст в консоли будет нечитаем) |

**Вариант 2 — Gradle-задача.** В панели Gradle: `cli → Tasks → application → run`.
Аргументы задаются так:

```bash
./gradlew :cli:run --args="analyze -i samples/spring-boot-npe.log --only-failed"
```

**Вариант 3 — собранный jar.**

```bash
./gradlew :cli:fatJar
java -jar cli/build/libs/log-analyzer.jar analyze -i samples/spring-boot-npe.log
```

## Отладка

Поставьте точку останова и запустите конфигурацию в режиме Debug. Удобные места:

| Что выясняем | Где ставить точку останова |
|---|---|
| Почему строка разобрана не так | `LogIngestor.handleLine`, `LogPattern.match` |
| Почему события не собрались в одну цепочку | `Correlator.resolveKey` |
| Почему повторы не схлопнулись | `EventFingerprint.of` |
| Почему правило не сработало | `Condition.matches` |
| Откуда взялась формулировка причины | `HeuristicRootCauseAnalyzer.analyze` |

Быстрее, чем отладчик, для первых двух пунктов работает команда:

```bash
log-analyzer patterns --test "ваша строка лога"
```

## Тесты

`./gradlew test` или запуск класса теста прямо из редактора.
Отчёт: `core/build/reports/tests/test/index.html`.

Тесты используют JUnit 5 и AssertJ; названия сценариев заданы через `@DisplayName` на русском,
поэтому дерево тестов в IDE читается как список проверяемых свойств.

## Кодировка консоли в Windows

Если в выводе вместо русских букв кракозябры:

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
java -Dfile.encoding=UTF-8 -jar cli\build\libs\log-analyzer.jar analyze -i samples\spring-boot-npe.log
```

Либо выводите отчёт в файл (`-o report.txt` или `-f html -o report.html`) — файлы всегда пишутся в UTF-8.

---

# Задел под плагин IntelliJ IDEA

Ядро (`core`) уже готово к встраиванию в плагин: оно не зависит ни от CLI, ни от какого-либо UI,
работает в памяти и возвращает готовую модель (`AnalysisReport` → `Timeline` → `TimelineEntry`).

Готовый веб-интерфейс (`cli/ui`) уже показывает, каким должен быть UI плагина: список
инцидентов, таймлайн, причина, фильтры. В плагине его можно либо переписать на Swing-компоненты
IDEA, либо на первом шаге просто открыть тот же HTML-отчёт в `JCEFHtmlPanel` внутри ToolWindow.

Что потребуется добавить:

1. **Модуль `idea-plugin`** с плагином `org.jetbrains.intellij.platform` (IntelliJ Platform Gradle Plugin 2.x)
   и зависимостью на `project(":core")`.

2. **Действие** `AnAction` («Analyze Logs») в меню `Tools` и в контекстном меню файла:
   вызывает `new LogAnalyzer(config).analyzeFiles(...)` в фоновой задаче
   (`Task.Backgroundable`) и передаёт `AnalysisReport` в ToolWindow.

3. **ToolWindow** с двумя панелями:
   - слева дерево инцидентов (`Timeline`: correlationId, число ошибок, длительность);
   - справа таблица событий выбранного инцидента (`TimelineEntry`) и блок с причиной.

4. **Переход к месту в логе.** У каждого события уже есть `sourceFile` и `sourceLine` —
   этого достаточно, чтобы открыть файл на нужной строке через `OpenFileDescriptor`.

5. **Переход к коду.** Верхний кадр стека (`StackFrame`: класс, метод, файл, строка)
   позволяет открыть исходник через `JavaPsiFacade.findClass(...)` — тот же механизм,
   что использует стандартная «Analyze Stacktrace».

6. **Настройки плагина** — те же поля, что в `AnalyzerConfig` (пакеты приложения, часовой пояс,
   файлы правил): их можно отобразить формой и сериализовать в `PersistentStateComponent`.

Ничего из перечисленного не требует изменений в `core` — только новый модуль поверх него.
