package io.github.loganalyzer.cli;

import io.github.loganalyzer.core.model.AnalysisReport;
import io.github.loganalyzer.core.model.LogEvent;
import io.github.loganalyzer.core.model.LogLevel;
import io.github.loganalyzer.core.model.Timeline;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Фильтры отчёта, общие для консольной команды и веб-интерфейса:
 * отбор событий до построения таймлайнов и отбор самих таймлайнов после анализа.
 */
public final class ReportFilters {

    private ReportFilters() {
    }

    /**
     * Отбор событий по времени и уровню — выполняется до корреляции,
     * поэтому отброшенные события не попадают в таймлайны вообще.
     */
    public static List<LogEvent> filterEvents(List<LogEvent> events, Instant from, Instant to, LogLevel minLevel) {
        if (from == null && to == null && minLevel == null) {
            return events;
        }
        List<LogEvent> filtered = new ArrayList<>(events.size());
        for (LogEvent event : events) {
            Instant ts = event.getTimestamp();
            if (from != null && ts != null && ts.isBefore(from)) {
                continue;
            }
            if (to != null && ts != null && ts.isAfter(to)) {
                continue;
            }
            if (minLevel != null && !event.getLevel().isAtLeast(minLevel)) {
                continue;
            }
            filtered.add(event);
        }
        return filtered;
    }

    /**
     * Отбор и ранжирование готовых таймлайнов; сводка отчёта пересчитывается,
     * чтобы цифры в шапке соответствовали показанному.
     */
    public static void filterTimelines(AnalysisReport report, String trace, boolean onlyFailed, int top) {
        List<Timeline> timelines = report.getTimelines();
        if (trace != null && !trace.isBlank()) {
            timelines.removeIf(t -> t.getCorrelationId() == null || !t.getCorrelationId().contains(trace));
        }
        if (onlyFailed) {
            timelines.removeIf(t -> !t.isFailed());
        }
        if (top > 0 && timelines.size() > top) {
            timelines.sort(Comparator
                    .comparingInt(Timeline::getErrorCount).reversed()
                    .thenComparing(t -> t.getDurationMs() == null ? 0L : t.getDurationMs(),
                            Comparator.reverseOrder()));
            List<Timeline> head = new ArrayList<>(timelines.subList(0, top));
            timelines.clear();
            timelines.addAll(head);
        }
        report.getSummary().setTimelines(timelines.size());
        report.getSummary().setFailedTimelines((int) timelines.stream().filter(Timeline::isFailed).count());
    }

    /** Принимает как полный ISO-момент, так и локальные дату-время. */
    public static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            // не полный ISO-момент — пробуем локальное время
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException("Не удалось разобрать время: " + value
                    + " (ожидается ISO-8601, например 2026-08-09T10:00:00)");
        }
    }
}
