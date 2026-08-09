package io.github.loganalyzer.cli.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.loganalyzer.cli.InputCollector;
import io.github.loganalyzer.cli.ReportFilters;
import io.github.loganalyzer.core.LogAnalyzer;
import io.github.loganalyzer.core.config.AnalyzerConfig;
import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.report.HtmlReportWriter;
import io.github.loganalyzer.core.report.JsonReportWriter;
import io.github.loganalyzer.core.report.ReportWriter;
import io.github.loganalyzer.core.report.TextReportWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Локальный веб-интерфейс анализатора.
 *
 * <p>Поднимает HTTP-сервер из JDK **только на петлевом интерфейсе** (127.0.0.1) — снаружи
 * он недоступен, поэтому чтение файлов с диска по указанному пути безопасно: обратиться
 * к серверу может лишь тот, кто уже сидит за этой машиной.
 *
 * <p>Результат анализа отдаётся тем же самодостаточным HTML-отчётом, что и команда
 * {@code analyze -f html}: страница интерфейса показывает его во фрейме, поэтому весь
 * интерактив (поиск, фильтр, раскрытие стеков) работает без дублирования кода.
 */
public final class UiServer implements AutoCloseable {

    /** Предел размера присланного фрагмента — защита от случайной отправки гигабайтного файла. */
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    /** Маппер для служебных ответов интерфейса (правила, шаблоны); отчёт сериализует свой writer. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final AnalyzerConfig config;
    private final HttpServer server;
    private final ExecutorService executor;

    public UiServer(AnalyzerConfig config, int port) throws IOException {
        this.config = config == null ? AnalyzerConfig.defaults() : config;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "log-analyzer-ui");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handlePage);
        server.createContext("/api/analyze", this::handleAnalyze);
        server.createContext("/api/rules", this::handleRules);
        server.createContext("/api/patterns", this::handlePatterns);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI url() {
        return URI.create("http://localhost:" + port() + "/");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ //
    // Обработчики                                                        //
    // ------------------------------------------------------------------ //

    private void handlePage(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"/".equals(path)) {
                send(exchange, 404, "text/plain", "Не найдено");
                return;
            }
            send(exchange, 200, "text/html", UiPage.html());
        }
    }

    private void handleAnalyze(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "Требуется POST");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES);
            String text = new String(body, StandardCharsets.UTF_8);

            try {
                String format = query.getOrDefault("format", "html");
                String report = analyse(text, query, format);
                String contentType = switch (format) {
                    case "json" -> "application/json";
                    case "text" -> "text/plain";
                    default -> "text/html";
                };
                send(exchange, 200, contentType, report);
            } catch (IllegalArgumentException | IllegalStateException e) {
                send(exchange, 400, "text/plain", message(e));
            } catch (RuntimeException e) {
                send(exchange, 500, "text/plain", "Внутренняя ошибка анализа: " + message(e));
            }
        }
    }

    /** Список правил анализа — раздел «Правила» в интерфейсе. */
    private void handleRules(HttpExchange exchange) throws IOException {
        try (exchange) {
            List<Map<String, Object>> rules = new java.util.ArrayList<>();
            for (io.github.loganalyzer.core.rules.Rule rule : new LogAnalyzer(config).getRuleEngine().getRules()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", rule.getName());
                item.put("description", rule.getDescription());
                item.put("priority", rule.getPriority());
                if (rule.getCause() != null) {
                    item.put("cause", rule.getCause().getTitle());
                    item.put("confidence", rule.getCause().getConfidence());
                    item.put("recommendation", rule.getCause().getRecommendation());
                }
                item.put("condition", describeCondition(rule.getWhen()));
                rules.add(item);
            }
            send(exchange, 200, "application/json", JSON.writeValueAsString(rules));
        }
    }

    /** Встроенные шаблоны строк лога и проверка конкретной строки — раздел «Форматы логов». */
    private void handlePatterns(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String line = new String(exchange.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8)
                        .split("\\R", 2)[0];
                send(exchange, 200, "application/json", JSON.writeValueAsString(testLine(line)));
                return;
            }
            List<Map<String, Object>> patterns = new java.util.ArrayList<>();
            for (io.github.loganalyzer.core.parse.LogPattern pattern
                    : io.github.loganalyzer.core.parse.LogPattern.builtins()) {
                patterns.add(Map.of("name", pattern.name(), "regex", pattern.pattern().pattern()));
            }
            send(exchange, 200, "application/json", JSON.writeValueAsString(patterns));
        }
    }

    /** Разбирает одну строку встроенными шаблонами — как команда {@code patterns --test}. */
    private Map<String, Object> testLine(String line) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (line == null || line.isBlank()) {
            result.put("matched", false);
            return result;
        }
        var timestamps = config.toParseOptions().timestampParser();
        for (io.github.loganalyzer.core.parse.LogPattern pattern
                : config.toParseOptions().effectivePatterns()) {
            var hit = pattern.match(line, timestamps);
            if (hit.isPresent()) {
                LogEvent event = hit.get().build();
                result.put("matched", true);
                result.put("pattern", pattern.name());
                result.put("timestamp", String.valueOf(event.getTimestamp()));
                result.put("level", event.getLevel().name());
                result.put("thread", event.getThread());
                result.put("logger", event.getLogger());
                result.put("traceId", event.getTraceId());
                result.put("service", event.getService());
                result.put("message", event.getMessage());
                return result;
            }
        }
        result.put("matched", false);
        return result;
    }

    /** Краткое человекочитаемое описание условия правила для интерфейса. */
    private static String describeCondition(io.github.loganalyzer.core.rules.Condition when) {
        if (when == null) {
            return "";
        }
        List<String> parts = new java.util.ArrayList<>();
        if (when.getExceptionType() != null) {
            parts.add("исключение: " + when.getExceptionType());
        }
        if (when.getMessageRegex() != null) {
            parts.add("сообщение: " + when.getMessageRegex());
        }
        if (!when.getAnyMessageRegex().isEmpty()) {
            parts.add("сообщение: " + String.join(" | ", when.getAnyMessageRegex()));
        }
        if (when.getHttpStatusClass() != null) {
            parts.add("HTTP " + when.getHttpStatusClass());
        }
        if (when.getMinDurationMs() != null) {
            parts.add("дольше " + when.getMinDurationMs() + " мс");
        }
        if (when.getLevelAtLeast() != null) {
            parts.add("уровень ≥ " + when.getLevelAtLeast());
        }
        return String.join("; ", parts);
    }

    /** Выполняет анализ и возвращает отчёт в запрошенном формате. */
    private String analyse(String text, Map<String, String> query, String format) {
        AnalyzerConfig cfg = configFor(query);
        LogAnalyzer analyzer = new LogAnalyzer(cfg);
        AnalysisReport report = new AnalysisReport();

        String path = query.get("path");
        List<LogEvent> events;
        if (path != null && !path.isBlank()) {
            List<Path> files = InputCollector.collect(
                    List.of(path), InputCollector.DEFAULT_INCLUDES, recursive(query));
            if (files.isEmpty()) {
                throw new IllegalArgumentException("По пути ничего не найдено: " + path);
            }
            events = analyzer.parseFiles(files, report);
        } else {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Пустой ввод: вставьте фрагмент лога или укажите путь к файлу.");
            }
            events = analyzer.parseText(text, "фрагмент", report);
        }

        events = ReportFilters.filterEvents(events, null, null, level(query));
        report = analyzer.analyzeEvents(events, report);
        ReportFilters.filterTimelines(
                report, query.get("trace"), flag(query, "onlyFailed"), intValue(query, "top"));

        ZoneId zone = cfg.getParse().getZone() == null || cfg.getParse().getZone().isBlank()
                ? ZoneId.systemDefault()
                : ZoneId.of(cfg.getParse().getZone());
        ReportWriter writer = switch (format) {
            case "json" -> new JsonReportWriter(true);
            case "text" -> new TextReportWriter(zone);
            default -> new HtmlReportWriter(zone);
        };
        return writer.writeToString(report);
    }

    /** Копия базовой конфигурации с поправками из запроса. */
    private AnalyzerConfig configFor(Map<String, String> query) {
        String zone = query.get("zone");
        if (zone == null || zone.isBlank()) {
            return config;
        }
        AnalyzerConfig copy = AnalyzerConfig.defaults();
        copy.setParse(config.getParse());
        copy.setCorrelation(config.getCorrelation());
        copy.setTimeline(config.getTimeline());
        copy.setRules(config.getRules());
        copy.setAnalysis(config.getAnalysis());
        copy.getParse().setZone(zone);
        return copy;
    }

    private static LogLevel level(Map<String, String> query) {
        String value = query.get("minLevel");
        if (value == null || value.isBlank()) {
            return null;
        }
        LogLevel level = LogLevel.parse(value);
        return level == LogLevel.UNKNOWN ? null : level;
    }

    private static boolean recursive(Map<String, String> query) {
        return flag(query, "recursive");
    }

    private static boolean flag(Map<String, String> query, String name) {
        String value = query.get(name);
        return value != null && (value.isEmpty() || "true".equalsIgnoreCase(value) || "1".equals(value));
    }

    private static int intValue(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ //
    // Вспомогательное                                                    //
    // ------------------------------------------------------------------ //

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        BiFunction<String, Integer, String> decode = (pair, index) -> {
            int eq = pair.indexOf('=');
            String part = index == 0
                    ? (eq < 0 ? pair : pair.substring(0, eq))
                    : (eq < 0 ? "" : pair.substring(eq + 1));
            return URLDecoder.decode(part, StandardCharsets.UTF_8);
        };
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            result.put(decode.apply(pair, 0), decode.apply(pair, 1));
        }
        return result;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String message(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
