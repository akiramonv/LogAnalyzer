package io.github.loganalyzer.core.report;

import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.model.TimelineEntry;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Диаграмма таймлайна в синтаксисе Mermaid ({@code gantt}) — вставляется в Markdown,
 * задачу трекера или страницу вики и рендерится там как визуальная шкала инцидента.
 * Ошибки отмечаются как {@code crit}, предупреждения — как {@code active}.
 */
public final class MermaidReportWriter implements ReportWriter {

    private final ZoneId zone;
    private final DateTimeFormatter format;
    private boolean onlyFailed = true;
    private int maxTimelines = 10;
    private int maxEntries = 40;

    public MermaidReportWriter() {
        this(ZoneId.systemDefault());
    }

    public MermaidReportWriter(ZoneId zone) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.format = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(this.zone);
    }

    public MermaidReportWriter setOnlyFailed(boolean v) {
        this.onlyFailed = v;
        return this;
    }

    public MermaidReportWriter setMaxTimelines(int v) {
        this.maxTimelines = Math.max(1, v);
        return this;
    }

    public MermaidReportWriter setMaxEntries(int v) {
        this.maxEntries = Math.max(1, v);
        return this;
    }

    @Override
    public void write(AnalysisReport report, Appendable out) throws IOException {
        List<Timeline> timelines = report.getTimelines().stream()
                .filter(t -> !onlyFailed || t.isFailed())
                .limit(maxTimelines)
                .toList();
        if (timelines.isEmpty()) {
            out.append("%% Подходящих таймлайнов не найдено\n");
            return;
        }
        for (Timeline timeline : timelines) {
            writeTimeline(timeline, out);
            out.append('\n');
        }
    }

    private void writeTimeline(Timeline timeline, Appendable out) throws IOException {
        out.append("```mermaid\n");
        out.append("gantt\n");
        out.append("    title Инцидент ").append(sanitize(timeline.getCorrelationId()));
        if (timeline.getRootCause() != null) {
            out.append(" — ").append(sanitize(timeline.getRootCause().getTitle()));
        }
        out.append('\n');
        out.append("    dateFormat HH:mm:ss.SSS\n");
        out.append("    axisFormat %H:%M:%S\n");

        String currentSection = null;
        List<TimelineEntry> entries = timeline.getEntries();
        for (int i = 0; i < entries.size() && i < maxEntries; i++) {
            TimelineEntry entry = entries.get(i);
            LogEvent event = entry.getEvent();
            Instant start = event.getTimestamp();
            if (start == null) {
                continue;
            }
            String section = event.getService() != null
                    ? event.getService()
                    : TextReportWriter.shortLogger(event.getLogger());
            if (section == null || section.isBlank()) {
                section = "приложение";
            }
            if (!section.equals(currentSection)) {
                out.append("    section ").append(sanitize(section)).append('\n');
                currentSection = section;
            }
            long durationMs = durationOf(entries, i, start);
            String status = event.getLevel().isError() || event.getException() != null
                    ? "crit"
                    : event.getLevel() == LogLevel.WARN ? "active" : "done";
            String label = sanitize(shorten(event.summary()));
            if (entry.getRepeatCount() > 1) {
                label = label + " x" + entry.getRepeatCount();
            }
            out.append("    ").append(label).append(" :").append(status).append(", ")
                    .append(entry.getId()).append(", ")
                    .append(format.format(start)).append(", ")
                    .append(String.valueOf(Math.max(1, durationMs))).append("ms\n");
        }
        out.append("```\n");
    }

    /** Длительность задачи — интервал до следующего события (для последнего берётся 1 мс). */
    private static long durationOf(List<TimelineEntry> entries, int index, Instant start) {
        for (int j = index + 1; j < entries.size(); j++) {
            Instant next = entries.get(j).getEvent().getTimestamp();
            if (next != null) {
                return Math.max(1, java.time.Duration.between(start, next).toMillis());
            }
        }
        return 1;
    }

    private static String shorten(String text) {
        String line = text == null ? "" : text.split("\\R", 2)[0];
        return line.length() > 60 ? line.substring(0, 57) + "..." : line;
    }

    /** Убирает символы, ломающие синтаксис gantt-строки. */
    private static String sanitize(String text) {
        if (text == null) {
            return "событие";
        }
        String s = text.replace(':', '-').replace(',', ';').replace('#', ' ').trim();
        return s.isEmpty() ? "событие" : s;
    }
}
