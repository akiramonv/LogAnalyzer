package io.github.loganalyzer.core.parse;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Настройки разбора логов: кодировка, часовой пояс, дополнительные шаблоны строк и ограничения.
 * Заполняется из конфигурационного файла или ключей CLI.
 */
public final class ParseOptions {

    private Charset charset = StandardCharsets.UTF_8;
    private ZoneId zone = ZoneId.systemDefault();
    /** Дата, подставляемая в логи, где есть только время (дефолтный logback-паттерн). */
    private LocalDate defaultDate = LocalDate.now();
    /** Пользовательские шаблоны — применяются раньше встроенных. */
    private final List<LogPattern> customPatterns = new ArrayList<>();
    /** Использовать встроенные шаблоны Spring Boot / Logback / Log4j2. */
    private boolean useBuiltinPatterns = true;
    /** Искать traceId/sessionId/длительности внутри текста сообщения. */
    private boolean extractFromMessage = true;
    /** Максимальное число строк в одном многострочном блоке (защита от «взрывных» стеков). */
    private int maxMultilineLines = 2000;
    /** Ограничение на число событий из одного источника; 0 — без ограничения. */
    private int maxEvents;

    public Charset getCharset() {
        return charset;
    }

    public ParseOptions setCharset(Charset v) {
        this.charset = v == null ? StandardCharsets.UTF_8 : v;
        return this;
    }

    public ZoneId getZone() {
        return zone;
    }

    public ParseOptions setZone(ZoneId v) {
        this.zone = v == null ? ZoneId.systemDefault() : v;
        return this;
    }

    public LocalDate getDefaultDate() {
        return defaultDate;
    }

    public ParseOptions setDefaultDate(LocalDate v) {
        this.defaultDate = v == null ? LocalDate.now() : v;
        return this;
    }

    public List<LogPattern> getCustomPatterns() {
        return customPatterns;
    }

    public ParseOptions addPattern(LogPattern pattern) {
        if (pattern != null) {
            customPatterns.add(pattern);
        }
        return this;
    }

    public boolean isUseBuiltinPatterns() {
        return useBuiltinPatterns;
    }

    public ParseOptions setUseBuiltinPatterns(boolean v) {
        this.useBuiltinPatterns = v;
        return this;
    }

    public boolean isExtractFromMessage() {
        return extractFromMessage;
    }

    public ParseOptions setExtractFromMessage(boolean v) {
        this.extractFromMessage = v;
        return this;
    }

    public int getMaxMultilineLines() {
        return maxMultilineLines;
    }

    public ParseOptions setMaxMultilineLines(int v) {
        this.maxMultilineLines = v <= 0 ? Integer.MAX_VALUE : v;
        return this;
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public ParseOptions setMaxEvents(int v) {
        this.maxEvents = Math.max(0, v);
        return this;
    }

    /** @return итоговый список шаблонов: сначала пользовательские, затем встроенные. */
    public List<LogPattern> effectivePatterns() {
        List<LogPattern> all = new ArrayList<>(customPatterns);
        if (useBuiltinPatterns) {
            all.addAll(LogPattern.builtins());
        }
        return all;
    }

    /** @return парсер меток времени, настроенный этими опциями. */
    public TimestampParser timestampParser() {
        return new TimestampParser(zone, defaultDate);
    }
}
