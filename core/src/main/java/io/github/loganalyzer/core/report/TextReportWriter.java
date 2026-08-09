package io.github.loganalyzer.core.report;

import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.EventAnnotation;
import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.RootCause;
import io.github.loganalyzer.core.model.StackFrame;
import io.github.loganalyzer.core.model.Timeline;
import io.github.loganalyzer.core.model.TimelineEntry;
import io.github.loganalyzer.core.timeline.TimelineBuilder;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Человекочитаемый отчёт для консоли: сводка, таймлайны с аннотациями и вывод о причине.
 * Это основной формат для работы «глазами» — им же удобно делиться в задаче или чате.
 */
public final class TextReportWriter implements ReportWriter {

    private static final String LINE = "═".repeat(78);
    private static final String THIN = "─".repeat(78);

    private final ZoneId zone;
    private final DateTimeFormatter timeFormat;
    private boolean showStackTraces = true;
    private int maxStackFrames = 8;
    private int maxEntriesPerTimeline;
    private boolean onlyFailed;

    public TextReportWriter() {
        this(ZoneId.systemDefault());
    }

    public TextReportWriter(ZoneId zone) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(this.zone);
    }

    public TextReportWriter setShowStackTraces(boolean v) {
        this.showStackTraces = v;
        return this;
    }

    public TextReportWriter setMaxStackFrames(int v) {
        this.maxStackFrames = Math.max(0, v);
        return this;
    }

    public TextReportWriter setMaxEntriesPerTimeline(int v) {
        this.maxEntriesPerTimeline = Math.max(0, v);
        return this;
    }

    public TextReportWriter setOnlyFailed(boolean v) {
        this.onlyFailed = v;
        return this;
    }

    @Override
    public void write(AnalysisReport report, Appendable out) throws IOException {
        AnalysisReport.Summary s = report.getSummary();
        out.append(LINE).append('\n');
        out.append("  Анализ логов — ").append(DateTimeFormatter.ISO_INSTANT.format(report.getGeneratedAt()))
                .append('\n');
        out.append("  Источников: ").append(String.valueOf(s.getFilesAnalyzed()))
                .append("   событий: ").append(String.valueOf(s.getTotalEvents()))
                .append("   ошибок: ").append(String.valueOf(s.getErrorEvents()))
                .append("   предупреждений: ").append(String.valueOf(s.getWarningEvents()))
                .append('\n');
        out.append("  Таймлайнов: ").append(String.valueOf(s.getTimelines()))
                .append("   из них с ошибками: ").append(String.valueOf(s.getFailedTimelines()))
                .append(s.getUnparsedLines() > 0
                        ? "   нераспознанных строк: " + s.getUnparsedLines() : "")
                .append('\n');
        out.append("  Время показано в поясе ").append(zone.getId()).append('\n');
        out.append(LINE).append("\n\n");

        if (!report.getWarnings().isEmpty()) {
            out.append("Предупреждения разбора:\n");
            for (String warning : report.getWarnings()) {
                out.append("  ! ").append(warning).append('\n');
            }
            out.append('\n');
        }

        List<Timeline> timelines = report.getTimelines().stream()
                .filter(t -> !onlyFailed || t.isFailed())
                .toList();

        if (timelines.isEmpty()) {
            out.append(onlyFailed
                    ? "Инцидентов с ошибками не найдено.\n"
                    : "Не найдено ни одного таймлайна — проверьте формат логов.\n");
            return;
        }

        int index = 0;
        for (Timeline timeline : timelines) {
            writeTimeline(timeline, ++index, out);
        }
    }

    private void writeTimeline(Timeline timeline, int index, Appendable out) throws IOException {
        out.append('[').append(String.valueOf(index)).append("] ");
        if (timeline.getCorrelationKind() == io.github.loganalyzer.core.model.CorrelationKind.FILE) {
            // Для источника без корреляционных признаков префикс «файл» ничего не добавляет
            out.append(timeline.getCorrelationId()).append('\n');
        } else {
            out.append(kindLabel(timeline)).append(' ').append(timeline.getCorrelationId()).append('\n');
        }

        StringBuilder meta = new StringBuilder("    событий: ").append(timeline.getEventCount());
        if (timeline.getDurationMs() != null) {
            meta.append(", длительность: ")
                    .append(TimelineBuilder.formatDuration(java.time.Duration.ofMillis(timeline.getDurationMs())));
        }
        if (timeline.getErrorCount() > 0) {
            meta.append(", ошибок: ").append(timeline.getErrorCount());
        }
        if (!timeline.getServices().isEmpty()) {
            meta.append(", сервисы: ").append(String.join(", ", timeline.getServices()));
        }
        out.append(meta).append('\n');

        RootCause cause = timeline.getRootCause();
        if (cause != null) {
            out.append("    ┌ Вероятная причина (").append(percent(cause.getConfidence())).append("): ")
                    .append(cause.getTitle()).append('\n');
            if (cause.getDescription() != null && !cause.getDescription().isBlank()) {
                out.append("    │ ").append(wrap(cause.getDescription(), "    │ ")).append('\n');
            }
            if (cause.getRecommendation() != null && !cause.getRecommendation().isBlank()) {
                out.append("    │ Что делать: ").append(wrap(cause.getRecommendation(), "    │ ")).append('\n');
            }
            if (!cause.getSteps().isEmpty()) {
                int step = 0;
                for (String item : cause.getSteps()) {
                    out.append("    │   ").append(String.valueOf(++step)).append(". ")
                            .append(wrap(item, "    │      ")).append('\n');
                }
            }
            if (!cause.getEvidence().isEmpty()) {
                out.append("    │ Основание: ").append(cause.getEvidence().stream()
                        .map(RootCause.Evidence::entryId).collect(Collectors.joining(", "))).append('\n');
            }
            if (cause.getRule() != null) {
                out.append("    │ Правило: ").append(cause.getRule()).append('\n');
            }
            out.append("    └").append("\n");

            if (!timeline.getAlternatives().isEmpty()) {
                out.append("    Другие версии:\n");
                for (RootCause alt : timeline.getAlternatives()) {
                    out.append("      · (").append(percent(alt.getConfidence())).append(") ")
                            .append(alt.getTitle()).append('\n');
                }
            }
        } else if (timeline.isFailed()) {
            out.append("    Причина не определена автоматически.\n");
        }

        out.append("    ").append(THIN).append('\n');

        int shown = 0;
        for (TimelineEntry entry : timeline.getEntries()) {
            if (maxEntriesPerTimeline > 0 && shown >= maxEntriesPerTimeline) {
                out.append("    ... ещё ")
                        .append(String.valueOf(timeline.getEntries().size() - shown))
                        .append(" событий\n");
                break;
            }
            writeEntry(entry, out);
            shown++;
        }
        out.append('\n');
    }

    private void writeEntry(TimelineEntry entry, Appendable out) throws IOException {
        LogEvent event = entry.getEvent();
        String offset = entry.getSinceStart() == null
                ? "".repeat(0)
                : "+" + TimelineBuilder.formatDuration(entry.getSinceStart());
        out.append("    ").append(pad(entry.getId(), 5))
                .append(pad(offset, 11))
                .append(pad(formatTime(event.getTimestamp()), 14))
                .append(pad(levelLabel(event.getLevel()), 6))
                .append(pad(shortLogger(event.getLogger()), 30))
                .append(' ').append(firstLine(event.summary()));

        if (entry.getRepeatCount() > 1) {
            out.append(" ×").append(String.valueOf(entry.getRepeatCount()));
        }
        String annotations = entry.distinctAnnotations().stream()
                .map(a -> a.label() == null ? a.type().name() : a.type().name() + ": " + a.label())
                .collect(Collectors.joining("; "));
        if (!annotations.isEmpty()) {
            out.append("\n          ⟨").append(annotations).append('⟩');
        }
        if (!entry.getRelatedIds().isEmpty()) {
            out.append("\n          → связано с ").append(String.join(", ", entry.getRelatedIds()));
        }
        out.append('\n');

        if (showStackTraces && event.getException() != null) {
            writeException(event.getException(), out);
        }
    }

    private void writeException(ExceptionInfo exception, Appendable out) throws IOException {
        boolean first = true;
        for (ExceptionInfo e : exception.chain()) {
            out.append("          ").append(first ? "" : "Caused by: ").append(e.header()).append('\n');
            List<StackFrame> frames = e.getFrames();
            int limit = Math.min(maxStackFrames, frames.size());
            for (int i = 0; i < limit; i++) {
                out.append("              at ").append(frames.get(i).toString()).append('\n');
            }
            if (frames.size() > limit) {
                out.append("              ... ещё ").append(String.valueOf(frames.size() - limit))
                        .append(" кадров\n");
            }
            first = false;
        }
    }

    private String formatTime(Instant instant) {
        return instant == null ? "—" : timeFormat.format(instant);
    }

    private static String kindLabel(Timeline timeline) {
        return switch (timeline.getCorrelationKind()) {
            case TRACE -> "traceId";
            case REQUEST -> "requestId";
            case SESSION -> "sessionId";
            case THREAD -> "поток";
            case FILE -> "файл";
        };
    }

    private static String levelLabel(LogLevel level) {
        return level == LogLevel.UNKNOWN ? "" : level.name();
    }

    /** {@code com.example.service.PaymentClient} -> {@code c.e.s.PaymentClient}. */
    static String shortLogger(String logger) {
        if (logger == null) {
            return "";
        }
        if (logger.length() <= 26 || !logger.contains(".")) {
            return logger;
        }
        String[] parts = logger.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].charAt(0)).append('.');
            }
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        String line = nl >= 0 ? text.substring(0, nl) : text;
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }

    private static String pad(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width - 1) + " ";
        }
        return v + " ".repeat(width - v.length());
    }

    private static String percent(double confidence) {
        return Math.round(confidence * 100) + "%";
    }

    /** Переносит длинный текст, выравнивая по префиксу. */
    private static String wrap(String text, String prefix) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        int width = 70;
        if (normalized.length() <= width) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + width, normalized.length());
            if (end < normalized.length()) {
                int space = normalized.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                }
            }
            if (start > 0) {
                sb.append('\n').append(prefix);
            }
            sb.append(normalized, start, end);
            start = end + 1;
        }
        return sb.toString();
    }
}
