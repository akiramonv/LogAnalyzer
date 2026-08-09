package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.EventKind;
import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.HttpExchange;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Потоковый разбор источника логов в поток {@link LogEvent}.
 *
 * <p>Ключевая особенность: один источник может содержать смесь форматов — текстовые строки
 * Logback, JSON-записи, многострочные стек-трейсы и HTTP-дампы. Ингестор ведёт состояние
 * «текущая запись + её продолжение» и склеивает многострочные блоки с той записью,
 * к которой они относятся, а не разрывает их на отдельные события.
 */
public final class LogIngestor {

    /** Результат разбора одного источника. */
    public static final class Result {
        private final List<LogEvent> events = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int totalLines;
        private int unparsedLines;

        public List<LogEvent> getEvents() {
            return events;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public int getTotalLines() {
            return totalLines;
        }

        public int getUnparsedLines() {
            return unparsedLines;
        }
    }

    private static final Pattern INLINE_LEVEL = Pattern.compile(
            "\\b(FATAL|ERROR|WARN(?:ING)?|INFO|DEBUG|TRACE|SEVERE)\\b");

    private final ParseOptions options;
    private final TimestampParser timestamps;
    private final JsonLogParser jsonParser;
    private final List<LogPattern> patterns;

    /** Сквозной счётчик событий — обеспечивает стабильный порядок при равных метках времени. */
    private long sequence;
    /** Последняя увиденная метка времени: HTTP-дампы и подобные блоки её не содержат. */
    private java.time.Instant lastTimestamp;

    public LogIngestor() {
        this(new ParseOptions());
    }

    public LogIngestor(ParseOptions options) {
        this.options = options == null ? new ParseOptions() : options;
        this.timestamps = this.options.timestampParser();
        this.jsonParser = new JsonLogParser(this.timestamps);
        this.patterns = this.options.effectivePatterns();
    }

    /** Разбирает файл. */
    public Result ingest(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, options.getCharset())) {
            return ingest(reader, file.toString());
        }
    }

    /** Разбирает текст (используется в тестах и при чтении из stdin). */
    public Result ingestText(String text, String sourceName) {
        try (BufferedReader reader = new BufferedReader(new StringReader(text == null ? "" : text))) {
            return ingest(reader, sourceName);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось разобрать текст: " + e.getMessage(), e);
        }
    }

    /** Разбирает произвольный поток. */
    public Result ingest(Reader reader, String sourceName) throws IOException {
        Result result = new Result();
        BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        State state = new State(sourceName);
        String line;
        int lineNo = 0;
        while ((line = br.readLine()) != null) {
            lineNo++;
            result.totalLines++;
            handleLine(stripBom(line, lineNo), lineNo, state, result);
            if (options.getMaxEvents() > 0 && result.events.size() >= options.getMaxEvents()) {
                result.warnings.add("Достигнут лимит событий (" + options.getMaxEvents()
                        + ") для источника " + sourceName + " — остальные строки пропущены");
                state.reset();
                return result;
            }
        }
        flush(state, result);
        return result;
    }

    private void handleLine(String line, int lineNo, State state, Result result) {
        if (line == null) {
            return;
        }
        // 1. JSON-запись. Внутри HTTP-дампа JSON — это тело ответа, а не новая запись лога.
        if (JsonLogParser.looksLikeJson(line) && !(state.current != null && state.httpDump)) {
            LogEvent.Builder b = jsonParser.parse(line);
            if (b != null) {
                flush(state, result);
                state.start(b, line, lineNo, false);
                return;
            }
            // JSON не разобрался — обрабатываем как обычную строку
        }
        // 2. HTTP-дамп
        if (HttpDumpParser.isDumpStart(line)) {
            flush(state, result);
            state.start(LogEvent.builder(), line, lineNo, true);
            return;
        }
        // 3. Текстовые шаблоны
        Optional<LogEvent.Builder> matched = matchPatterns(line);
        if (matched.isPresent()) {
            flush(state, result);
            state.start(matched.get(), line, lineNo, false);
            return;
        }
        // 4. Продолжение текущей записи
        if (state.current != null) {
            if (state.block.size() < options.getMaxMultilineLines()) {
                state.block.add(line);
            } else if (!state.truncated) {
                state.truncated = true;
                result.warnings.add(sourceLabel(state.source, state.startLine)
                        + ": многострочный блок обрезан на " + options.getMaxMultilineLines() + " строках");
            }
            return;
        }
        // 5. Строка без «хозяина»
        if (line.isBlank()) {
            return;
        }
        if (StackTraceParser.looksLikeExceptionHeader(line) || StackTraceParser.isStackTraceLine(line)) {
            // Стек-трейс без предшествующей записи лога — делаем из него самостоятельное событие.
            // Первая строка кладётся и в сообщение: она и есть заголовок исключения.
            state.start(LogEvent.builder().level(LogLevel.ERROR).message(line.trim()), line, lineNo, false);
            return;
        }
        result.unparsedLines++;
        result.events.add(LogEvent.builder()
                .sequence(sequence++)
                .kind(EventKind.UNPARSED)
                .level(LogLevel.UNKNOWN)
                .message(line)
                .source(state.source, lineNo)
                .raw(line)
                .build());
    }

    /** Завершает текущую запись: разбирает её многострочный хвост и добавляет событие в результат. */
    private void flush(State state, Result result) {
        if (state.current == null) {
            return;
        }
        LogEvent.Builder b = state.current;
        List<String> block = state.block;
        String raw = String.join("\n", block);

        if (state.httpDump) {
            HttpExchange http = HttpDumpParser.parse(block);
            if (http != null) {
                b.http(http);
                b.kind(http.getStatus() != null ? EventKind.HTTP_RESPONSE : EventKind.HTTP_REQUEST);
                b.message(http.summary());
                if (http.getBody() != null) {
                    // тело ответа часто содержит traceId и текст ошибки — используем их
                    AttributeExtractor.enrich(b, http.getBody());
                }
                for (var header : http.getHeaders().entrySet()) {
                    AttributeExtractor.enrich(b, header.getKey() + "=" + header.getValue());
                }
                if (b.build().getLevel() == LogLevel.UNKNOWN) {
                    b.level(http.isFailure() ? LogLevel.ERROR : LogLevel.INFO);
                }
            }
        } else if (block.size() > 1) {
            attachTail(b, block.subList(1, block.size()));
        }

        LogEvent draft = b.build();
        // Уровень мог не попасть в формат — определяем по содержимому
        if (draft.getLevel() == LogLevel.UNKNOWN) {
            b.level(guessLevel(draft));
        }
        if (options.isExtractFromMessage()) {
            AttributeExtractor.enrich(b, draft.getMessage());
            if (draft.getHttp() == null) {
                HttpExchange inline = AttributeExtractor.extractHttp(draft.getMessage());
                if (inline != null) {
                    b.http(inline);
                }
            }
        }

        if (draft.getTimestamp() == null && lastTimestamp != null) {
            // Записи без собственной метки времени (HTTP-дампы, «сырые» блоки) относим
            // к моменту предыдущей записи — иначе они выпадают из хронологии инцидента.
            b.timestamp(lastTimestamp).attribute("timestampInherited", "true");
        } else if (draft.getTimestamp() != null) {
            lastTimestamp = draft.getTimestamp();
        }

        result.events.add(b
                .sequence(sequence++)
                .source(state.source, state.startLine)
                .raw(raw)
                .build());
        state.reset();
    }

    /**
     * Разбирает хвост записи: отделяет продолжение сообщения от стек-трейса
     * и прикрепляет разобранное исключение к событию.
     */
    private void attachTail(LogEvent.Builder b, List<String> tail) {
        int stackStart = -1;
        for (int i = 0; i < tail.size(); i++) {
            String line = tail.get(i);
            if (StackTraceParser.isStackTraceLine(line) || StackTraceParser.looksLikeExceptionHeader(line)) {
                stackStart = i;
                break;
            }
        }
        if (stackStart < 0) {
            // Обычное многострочное сообщение (SQL, JSON-тело, отчёт и т.п.)
            appendNonBlank(b, tail, tail.size());
            return;
        }
        appendNonBlank(b, tail, stackStart);
        List<String> stackLines = new ArrayList<>(tail.subList(stackStart, tail.size()));

        // Если блок начинается сразу с кадра "at ...", заголовок исключения находится
        // в последней строке сообщения — переносим её в стек, чтобы получить тип исключения.
        if (!StackTraceParser.looksLikeExceptionHeader(stackLines.get(0))) {
            String message = b.build().getMessage();
            if (message != null && !message.isEmpty()) {
                String[] messageLines = message.split("\\R");
                String last = messageLines[messageLines.length - 1];
                if (StackTraceParser.looksLikeExceptionHeader(last)) {
                    stackLines.add(0, last);
                }
            }
        }
        ExceptionInfo exception = StackTraceParser.parse(stackLines);
        if (exception != null && exception.getType() != null) {
            b.exception(exception).kind(EventKind.EXCEPTION);
        } else {
            appendNonBlank(b, stackLines, stackLines.size());
        }
    }

    /** Добавляет к сообщению первые {@code limit} непустых строк списка. */
    private static void appendNonBlank(LogEvent.Builder b, List<String> lines, int limit) {
        for (int i = 0; i < limit; i++) {
            String line = lines.get(i);
            if (!line.isBlank()) {
                b.appendMessage(line);
            }
        }
    }

    /**
     * Пробует шаблоны строго в порядке их объявления — от самых специфичных к общим.
     *
     * <p>Порядок здесь важнее скорости: если однажды сработавший общий шаблон получит
     * приоритет, он перехватит и те строки, которые точнее разобрал бы специфичный.
     */
    private Optional<LogEvent.Builder> matchPatterns(String line) {
        if (!TimestampParser.TIMESTAMP_PREFIX.matcher(line).find() && !line.startsWith("[")) {
            // Быстрый отсев: почти все форматы начинаются с метки времени или скобки
            return Optional.empty();
        }
        for (LogPattern p : patterns) {
            Optional<LogEvent.Builder> hit = p.match(line, timestamps);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** Определяет уровень по содержимому, если формат его не дал. */
    private static LogLevel guessLevel(LogEvent draft) {
        if (draft.getException() != null) {
            return LogLevel.ERROR;
        }
        if (draft.getHttp() != null && draft.getHttp().isFailure()) {
            return LogLevel.ERROR;
        }
        String text = draft.getMessage();
        if (text != null && !text.isEmpty()) {
            Matcher m = INLINE_LEVEL.matcher(text.length() > 200 ? text.substring(0, 200) : text);
            if (m.find()) {
                return LogLevel.parse(m.group(1));
            }
        }
        return LogLevel.INFO;
    }

    private static String sourceLabel(String source, int line) {
        return source == null ? "строка " + line : source + ":" + line;
    }

    /** Убирает BOM в начале файла. */
    private static String stripBom(String line, int lineNo) {
        if (lineNo == 1 && !line.isEmpty() && line.charAt(0) == '﻿') {
            return line.substring(1);
        }
        return line;
    }

    /** Состояние разбора: текущая запись и накопленные строки её блока. */
    private static final class State {
        private final String source;
        private LogEvent.Builder current;
        private final List<String> block = new ArrayList<>();
        private int startLine;
        private boolean httpDump;
        private boolean truncated;

        State(String source) {
            this.source = source;
        }

        void start(LogEvent.Builder builder, String firstLine, int lineNo, boolean isHttpDump) {
            this.current = builder;
            this.block.clear();
            this.block.add(firstLine);
            this.startLine = lineNo;
            this.httpDump = isHttpDump;
            this.truncated = false;
        }

        void reset() {
            this.current = null;
            this.block.clear();
            this.httpDump = false;
            this.truncated = false;
        }
    }
}
