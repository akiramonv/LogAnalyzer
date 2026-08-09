package io.github.loganalyzer.core.analyze;

import io.github.loganalyzer.core.model.EventAnnotation;
import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.StackFrame;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.model.TimelineEntry;
import io.github.loganalyzer.core.timeline.TimelineBuilder;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Формирует текстовое описание инцидента и запрос к языковой модели.
 *
 * <p>Класс намеренно не зависит от какого-либо клиента LLM: полученный промпт можно
 * отправить в модель программно (модуль {@code llm}), скопировать в чат или приложить к задаче.
 * Таймлайн подаётся в сжатом виде — только то, что нужно для вывода о причине.
 */
public final class IncidentPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            Ты — инженер по надёжности, разбирающий инцидент в Java/Spring-приложении по логам.
            Тебе даётся хронология событий одного запроса (traceId) с пометками анализатора.
            Задача: определить наиболее вероятную первопричину сбоя.

            Правила разбора:
            - В Java верхнее исключение почти всегда обёртка; настоящая причина — в конце цепочки Caused by.
            - Первая ошибка в цепочке обычно порождает последующие; разбирай именно её.
            - Таймауты, отказы соединения и ответы 5xx указывают на внешнюю систему, а не на место падения.
            - Не выдумывай факты, которых нет в логах. Если данных недостаточно — снижай уверенность.

            Отвечай кратко и по существу, на русском языке.
            """;

    private final ZoneId zone;
    private final DateTimeFormatter timeFormat;
    private int maxEntries = 60;
    private int maxStackFrames = 6;

    public IncidentPromptBuilder() {
        this(ZoneId.systemDefault());
    }

    public IncidentPromptBuilder(ZoneId zone) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(this.zone);
    }

    public IncidentPromptBuilder setMaxEntries(int v) {
        this.maxEntries = Math.max(1, v);
        return this;
    }

    public IncidentPromptBuilder setMaxStackFrames(int v) {
        this.maxStackFrames = Math.max(0, v);
        return this;
    }

    /** @return системная инструкция для модели. */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /** @return текст пользовательского сообщения: описание инцидента и задание. */
    public String userPrompt(Timeline timeline) {
        StringBuilder sb = new StringBuilder();
        sb.append("Инцидент: ").append(timeline.getCorrelationKind())
                .append(' ').append(timeline.getCorrelationId()).append('\n');
        if (timeline.getDurationMs() != null) {
            sb.append("Длительность: ")
                    .append(TimelineBuilder.formatDuration(java.time.Duration.ofMillis(timeline.getDurationMs())))
                    .append('\n');
        }
        if (!timeline.getServices().isEmpty()) {
            sb.append("Сервисы: ").append(String.join(", ", timeline.getServices())).append('\n');
        }
        sb.append("Событий: ").append(timeline.getEventCount())
                .append(", ошибок: ").append(timeline.getErrorCount()).append("\n\n");

        sb.append("Хронология:\n");
        List<TimelineEntry> entries = timeline.getEntries();
        int shown = Math.min(entries.size(), maxEntries);
        for (int i = 0; i < shown; i++) {
            appendEntry(sb, entries.get(i));
        }
        if (entries.size() > shown) {
            sb.append("... ещё ").append(entries.size() - shown).append(" событий опущено\n");
        }

        sb.append("""

                Задание:
                1. Назови наиболее вероятную первопричину сбоя одной фразой.
                2. Объясни, на основании каких событий (укажи их идентификаторы e1, e2, ...) ты сделал вывод.
                3. Дай оценку уверенности числом от 0 до 1.
                4. Предложи конкретный следующий шаг для проверки или устранения.
                """);
        return sb.toString();
    }

    private void appendEntry(StringBuilder sb, TimelineEntry entry) {
        LogEvent event = entry.getEvent();
        sb.append(entry.getId()).append(' ');
        sb.append(event.getTimestamp() == null ? "--:--:--" : timeFormat.format(event.getTimestamp()));
        sb.append(' ').append(event.getLevel());
        if (event.getLogger() != null) {
            sb.append(' ').append(event.getLogger());
        }
        sb.append(" | ").append(oneLine(event.summary()));
        if (entry.getRepeatCount() > 1) {
            sb.append(" (повторов: ").append(entry.getRepeatCount()).append(')');
        }
        if (!entry.getAnnotations().isEmpty()) {
            sb.append(" [").append(entry.distinctAnnotations().stream()
                    .map(EventAnnotation::type)
                    .map(Enum::name)
                    .collect(Collectors.joining(","))).append(']');
        }
        sb.append('\n');

        ExceptionInfo exception = event.getException();
        if (exception != null) {
            boolean first = true;
            for (ExceptionInfo e : exception.chain()) {
                sb.append("    ").append(first ? "" : "Caused by: ").append(e.header()).append('\n');
                List<StackFrame> frames = e.getFrames();
                int limit = Math.min(maxStackFrames, frames.size());
                for (int i = 0; i < limit; i++) {
                    sb.append("        at ").append(frames.get(i)).append('\n');
                }
                first = false;
            }
        }
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 297) + "..." : s;
    }
}
