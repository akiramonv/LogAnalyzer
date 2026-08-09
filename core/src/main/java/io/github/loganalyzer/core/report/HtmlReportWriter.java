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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Самодостаточный HTML-отчёт с визуальным таймлайном: одна страница без внешних ресурсов,
 * которую можно открыть в браузере, приложить к задаче или отправить коллеге.
 *
 * <p>Страница содержит шкалу инцидента, таблицу событий с раскрывающимися стек-трейсами,
 * поиск и фильтр «только ошибки».
 */
public final class HtmlReportWriter implements ReportWriter {

    private final ZoneId zone;
    private final DateTimeFormatter timeFormat;
    private boolean onlyFailed;

    public HtmlReportWriter() {
        this(ZoneId.systemDefault());
    }

    public HtmlReportWriter(ZoneId zone) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(this.zone);
    }

    public HtmlReportWriter setOnlyFailed(boolean v) {
        this.onlyFailed = v;
        return this;
    }

    @Override
    public void write(AnalysisReport report, Appendable out) throws IOException {
        out.append("<!doctype html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>Анализ логов</title>\n<style>\n").append(css()).append("\n</style>\n</head>\n<body>\n");

        writeHeader(report, out);
        writeControls(out);

        List<Timeline> timelines = report.getTimelines().stream()
                .filter(t -> !onlyFailed || t.isFailed())
                .toList();

        out.append("<main id=\"timelines\">\n");
        int index = 0;
        for (Timeline timeline : timelines) {
            writeTimeline(timeline, ++index, out);
        }
        if (timelines.isEmpty()) {
            out.append("<p class=\"empty\">Подходящих инцидентов не найдено.</p>\n");
        }
        out.append("</main>\n");

        if (!report.getWarnings().isEmpty()) {
            out.append("<section class=\"warnings\"><h2>Предупреждения разбора</h2><ul>\n");
            for (String warning : report.getWarnings()) {
                out.append("<li>").append(esc(warning)).append("</li>\n");
            }
            out.append("</ul></section>\n");
        }

        out.append("<script>\n").append(js()).append("\n</script>\n</body>\n</html>\n");
    }

    private void writeHeader(AnalysisReport report, Appendable out) throws IOException {
        AnalysisReport.Summary s = report.getSummary();
        out.append("<header>\n<h1>Анализ логов</h1>\n");
        out.append("<p class=\"meta\">Сформирован ")
                .append(esc(DateTimeFormatter.ISO_INSTANT.format(report.getGeneratedAt())))
                .append(" · источники: ").append(esc(String.join(", ", report.getInputs())))
                .append("</p>\n");
        out.append("<div class=\"metrics\">\n");
        metric(out, "События", String.valueOf(s.getTotalEvents()), null);
        metric(out, "Ошибки", String.valueOf(s.getErrorEvents()), s.getErrorEvents() > 0 ? "bad" : null);
        metric(out, "Предупреждения", String.valueOf(s.getWarningEvents()),
                s.getWarningEvents() > 0 ? "warn" : null);
        metric(out, "Инциденты", String.valueOf(s.getTimelines()), null);
        metric(out, "С ошибками", String.valueOf(s.getFailedTimelines()),
                s.getFailedTimelines() > 0 ? "bad" : null);
        out.append("</div>\n</header>\n");
    }

    private static void metric(Appendable out, String label, String value, String modifier) throws IOException {
        out.append("<div class=\"metric").append(modifier == null ? "" : " " + modifier).append("\">")
                .append("<span class=\"value\">").append(esc(value)).append("</span>")
                .append("<span class=\"label\">").append(esc(label)).append("</span></div>\n");
    }

    private static void writeControls(Appendable out) throws IOException {
        out.append("<div class=\"controls\">\n")
                .append("<input type=\"search\" id=\"search\" placeholder=\"Поиск по событиям…\">\n")
                .append("<label><input type=\"checkbox\" id=\"only-errors\"> только ошибки</label>\n")
                .append("<button id=\"expand-all\" type=\"button\">Развернуть стеки</button>\n")
                .append("</div>\n");
    }

    private void writeTimeline(Timeline timeline, int index, Appendable out) throws IOException {
        out.append("<section class=\"timeline").append(timeline.isFailed() ? " failed" : "").append("\">\n");
        out.append("<h2><span class=\"idx\">#").append(String.valueOf(index)).append("</span> ")
                .append(esc(kindLabel(timeline))).append(": <code>")
                .append(esc(timeline.getCorrelationId())).append("</code></h2>\n");

        out.append("<p class=\"meta\">событий: ").append(String.valueOf(timeline.getEventCount()));
        if (timeline.getDurationMs() != null) {
            out.append(" · длительность: ")
                    .append(esc(TimelineBuilder.formatDuration(Duration.ofMillis(timeline.getDurationMs()))));
        }
        if (timeline.getErrorCount() > 0) {
            out.append(" · ошибок: ").append(String.valueOf(timeline.getErrorCount()));
        }
        if (!timeline.getServices().isEmpty()) {
            out.append(" · сервисы: ").append(esc(String.join(", ", timeline.getServices())));
        }
        out.append("</p>\n");

        writeCause(timeline, out);
        writeScale(timeline, out);
        writeEntries(timeline, out);
        out.append("</section>\n");
    }

    private void writeCause(Timeline timeline, Appendable out) throws IOException {
        RootCause cause = timeline.getRootCause();
        if (cause == null) {
            if (timeline.isFailed()) {
                out.append("<div class=\"cause unknown\">Причина не определена автоматически</div>\n");
            }
            return;
        }
        int percent = (int) Math.round(cause.getConfidence() * 100);
        out.append("<div class=\"cause\">\n");
        out.append("<div class=\"cause-head\"><strong>Вероятная причина</strong>")
                .append("<span class=\"confidence\"><span class=\"bar\"><span style=\"width:")
                .append(String.valueOf(percent)).append("%\"></span></span>")
                .append(String.valueOf(percent)).append("%</span></div>\n");
        out.append("<p class=\"title\">").append(esc(cause.getTitle())).append("</p>\n");
        if (cause.getDescription() != null && !cause.getDescription().isBlank()) {
            out.append("<p>").append(esc(cause.getDescription())).append("</p>\n");
        }
        if (cause.getRecommendation() != null && !cause.getRecommendation().isBlank()) {
            out.append("<p class=\"rec\"><b>Что делать:</b> ").append(esc(cause.getRecommendation()))
                    .append("</p>\n");
        }
        if (!cause.getEvidence().isEmpty()) {
            out.append("<p class=\"evidence\">Основание: ");
            for (RootCause.Evidence e : cause.getEvidence()) {
                out.append("<a href=\"#\" class=\"ref\" data-ref=\"").append(esc(e.entryId())).append("\">")
                        .append(esc(e.entryId())).append("</a> ");
            }
            out.append("</p>\n");
        }
        if (!timeline.getAlternatives().isEmpty()) {
            out.append("<details class=\"alts\"><summary>Другие версии (")
                    .append(String.valueOf(timeline.getAlternatives().size())).append(")</summary><ul>");
            for (RootCause alt : timeline.getAlternatives()) {
                out.append("<li><span class=\"pct\">")
                        .append(String.valueOf(Math.round(alt.getConfidence() * 100))).append("%</span> ")
                        .append(esc(alt.getTitle())).append("</li>");
            }
            out.append("</ul></details>\n");
        }
        out.append("</div>\n");
    }

    /** Горизонтальная шкала инцидента: положение точки пропорционально времени события. */
    private void writeScale(Timeline timeline, Appendable out) throws IOException {
        Instant start = timeline.getStart();
        Instant end = timeline.getEnd();
        if (start == null || end == null || !end.isAfter(start)) {
            return;
        }
        long total = Duration.between(start, end).toMillis();
        out.append("<div class=\"scale\" title=\"Шкала инцидента\">\n");
        for (TimelineEntry entry : timeline.getEntries()) {
            Instant at = entry.getEvent().getTimestamp();
            if (at == null) {
                continue;
            }
            double position = 100.0 * Duration.between(start, at).toMillis() / total;
            String kind = entry.isError() ? "err"
                    : entry.getEvent().getLevel() == LogLevel.WARN ? "warn" : "ok";
            out.append("<span class=\"tick ").append(kind).append("\" style=\"left:")
                    .append(String.format(Locale.ROOT, "%.3f", Math.min(100, Math.max(0, position))))
                    .append("%\" data-ref=\"").append(esc(entry.getId())).append("\" title=\"")
                    .append(esc(firstLine(entry.getEvent().summary()))).append("\"></span>\n");
        }
        out.append("</div>\n");
    }

    private void writeEntries(Timeline timeline, Appendable out) throws IOException {
        out.append("<table class=\"events\">\n<thead><tr>")
                .append("<th>#</th><th>+</th><th>время</th><th>уровень</th><th>логгер</th><th>событие</th>")
                .append("</tr></thead>\n<tbody>\n");
        for (TimelineEntry entry : timeline.getEntries()) {
            LogEvent event = entry.getEvent();
            String level = event.getLevel() == LogLevel.UNKNOWN ? "" : event.getLevel().name();
            out.append("<tr id=\"").append(esc(entry.getId())).append("\" class=\"row")
                    .append(entry.isError() ? " err" : event.getLevel() == LogLevel.WARN ? " warn" : "")
                    .append("\">");
            out.append("<td class=\"id\">").append(esc(entry.getId())).append("</td>");
            out.append("<td class=\"offset\">")
                    .append(entry.getSinceStart() == null ? ""
                            : "+" + esc(TimelineBuilder.formatDuration(entry.getSinceStart())))
                    .append("</td>");
            out.append("<td class=\"time\">")
                    .append(event.getTimestamp() == null ? "—" : esc(timeFormat.format(event.getTimestamp())))
                    .append("</td>");
            out.append("<td><span class=\"lvl lvl-").append(level.toLowerCase(Locale.ROOT)).append("\">")
                    .append(esc(level)).append("</span></td>");
            out.append("<td class=\"logger\" title=\"").append(esc(event.getLogger())).append("\">")
                    .append(esc(TextReportWriter.shortLogger(event.getLogger()))).append("</td>");

            out.append("<td class=\"msg\">").append(esc(firstLine(event.summary())));
            if (entry.getRepeatCount() > 1) {
                out.append(" <span class=\"repeat\">×").append(String.valueOf(entry.getRepeatCount()))
                        .append("</span>");
            }
            for (EventAnnotation a : entry.distinctAnnotations()) {
                out.append(" <span class=\"chip chip-").append(a.type().name().toLowerCase(Locale.ROOT))
                        .append("\" title=\"").append(esc(a.rule())).append("\">")
                        .append(esc(a.label() == null ? a.type().name() : a.label())).append("</span>");
            }
            if (event.getException() != null) {
                out.append("<details class=\"stack\"><summary>")
                        .append(esc(event.getException().header())).append("</summary><pre>")
                        .append(esc(renderStack(event.getException()))).append("</pre></details>");
            }
            out.append("</td></tr>\n");
        }
        out.append("</tbody>\n</table>\n");
    }

    private static String renderStack(ExceptionInfo exception) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ExceptionInfo e : exception.chain()) {
            sb.append(first ? "" : "Caused by: ").append(e.header()).append('\n');
            for (StackFrame frame : e.getFrames()) {
                sb.append("    at ").append(frame).append('\n');
            }
            if (e.getFramesOmitted() > 0) {
                sb.append("    ... ").append(e.getFramesOmitted()).append(" more\n");
            }
            first = false;
        }
        return sb.toString();
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

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.split("\\R", 2)[0];
        return line.length() > 300 ? line.substring(0, 297) + "..." : line;
    }

    /** Экранирование HTML — все тексты из логов попадают на страницу только через него. */
    static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String css() {
        return """
                :root {
                  --bg: #f7f8fa; --fg: #1f2328; --muted: #6b7280; --card: #ffffff;
                  --border: #e3e6ea; --err: #d1242f; --warn: #bf8700; --ok: #1a7f37; --accent: #0969da;
                }
                @media (prefers-color-scheme: dark) {
                  :root {
                    --bg: #0d1117; --fg: #e6edf3; --muted: #8b949e; --card: #161b22;
                    --border: #30363d; --err: #ff7b72; --warn: #d29922; --ok: #3fb950; --accent: #58a6ff;
                  }
                }
                * { box-sizing: border-box; }
                body { margin: 0; padding: 24px; background: var(--bg); color: var(--fg);
                       font: 14px/1.5 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
                h1 { font-size: 22px; margin: 0 0 4px; }
                h2 { font-size: 16px; margin: 0 0 6px; }
                code { font-family: ui-monospace, "Cascadia Code", Consolas, monospace; }
                .meta { color: var(--muted); margin: 0 0 12px; font-size: 12px; }
                .metrics { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 18px; }
                .metric { background: var(--card); border: 1px solid var(--border); border-radius: 8px;
                          padding: 10px 14px; min-width: 110px; }
                .metric .value { display: block; font-size: 20px; font-weight: 600; }
                .metric .label { color: var(--muted); font-size: 12px; }
                .metric.bad .value { color: var(--err); }
                .metric.warn .value { color: var(--warn); }
                .controls { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 16px; }
                .controls input[type=search] { flex: 1 1 260px; padding: 7px 10px; border-radius: 6px;
                          border: 1px solid var(--border); background: var(--card); color: var(--fg); }
                .controls button { padding: 7px 12px; border-radius: 6px; border: 1px solid var(--border);
                          background: var(--card); color: var(--fg); cursor: pointer; }
                .timeline { background: var(--card); border: 1px solid var(--border); border-radius: 10px;
                            padding: 16px; margin-bottom: 18px; }
                .timeline.failed { border-left: 4px solid var(--err); }
                .idx { color: var(--muted); font-weight: 400; }
                .cause { border: 1px solid var(--border); border-radius: 8px; padding: 12px;
                         margin: 10px 0 14px; background: color-mix(in srgb, var(--accent) 6%, transparent); }
                .cause.unknown { color: var(--muted); }
                .cause-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
                .cause .title { font-size: 15px; font-weight: 600; margin: 6px 0; }
                .cause p { margin: 4px 0; }
                .confidence { display: flex; align-items: center; gap: 8px; font-variant-numeric: tabular-nums; }
                .bar { display: inline-block; width: 120px; height: 8px; border-radius: 4px;
                       background: var(--border); overflow: hidden; }
                .bar > span { display: block; height: 100%; background: var(--accent); }
                .scale { position: relative; height: 26px; margin: 12px 0 8px; border-radius: 4px;
                         background: linear-gradient(var(--border), var(--border)) center/100% 2px no-repeat; }
                .tick { position: absolute; top: 7px; width: 10px; height: 10px; border-radius: 50%;
                        transform: translateX(-50%); background: var(--ok); cursor: pointer; }
                .tick.warn { background: var(--warn); }
                .tick.err { background: var(--err); width: 13px; height: 13px; top: 5px; }
                table.events { width: 100%; border-collapse: collapse; font-size: 13px; }
                table.events th { text-align: left; color: var(--muted); font-weight: 500;
                                  border-bottom: 1px solid var(--border); padding: 6px 8px; }
                table.events td { padding: 6px 8px; border-bottom: 1px solid var(--border);
                                  vertical-align: top; }
                tr.row.err { background: color-mix(in srgb, var(--err) 8%, transparent); }
                tr.row.warn { background: color-mix(in srgb, var(--warn) 8%, transparent); }
                tr.row.highlight { outline: 2px solid var(--accent); }
                td.id, td.offset, td.time { color: var(--muted); white-space: nowrap;
                                  font-family: ui-monospace, Consolas, monospace; }
                td.logger { color: var(--muted); white-space: nowrap; }
                .lvl { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                .lvl-error, .lvl-fatal { color: var(--err); font-weight: 600; }
                .lvl-warn { color: var(--warn); }
                .lvl-info { color: var(--ok); }
                .chip { display: inline-block; padding: 1px 7px; border-radius: 10px; font-size: 11px;
                        border: 1px solid var(--border); color: var(--muted); white-space: nowrap; }
                .chip-exception, .chip-error, .chip-http_server_error { color: var(--err);
                        border-color: color-mix(in srgb, var(--err) 45%, transparent); }
                .chip-timeout, .chip-retry, .chip-slow, .chip-warning { color: var(--warn);
                        border-color: color-mix(in srgb, var(--warn) 45%, transparent); }
                .repeat { color: var(--muted); font-size: 12px; }
                details.stack { margin-top: 4px; }
                details.stack summary { cursor: pointer; color: var(--err); font-size: 12px; }
                details.stack pre { overflow-x: auto; background: var(--bg); padding: 8px;
                        border-radius: 6px; font-size: 12px; }
                .warnings { color: var(--muted); font-size: 13px; }
                .empty { color: var(--muted); }
                a.ref { color: var(--accent); text-decoration: none; }
                """;
    }

    private static String js() {
        return """
                (function () {
                  const search = document.getElementById('search');
                  const onlyErrors = document.getElementById('only-errors');
                  const expandAll = document.getElementById('expand-all');

                  function apply() {
                    const q = (search.value || '').toLowerCase();
                    const errOnly = onlyErrors.checked;
                    document.querySelectorAll('section.timeline').forEach(function (section) {
                      let visible = 0;
                      section.querySelectorAll('tr.row').forEach(function (row) {
                        const text = row.textContent.toLowerCase();
                        const matches = (!q || text.indexOf(q) >= 0) && (!errOnly || row.classList.contains('err'));
                        row.style.display = matches ? '' : 'none';
                        if (matches) visible++;
                      });
                      section.style.display = visible ? '' : 'none';
                    });
                  }

                  search.addEventListener('input', apply);
                  onlyErrors.addEventListener('change', apply);
                  expandAll.addEventListener('click', function () {
                    const details = document.querySelectorAll('details.stack');
                    const open = Array.prototype.some.call(details, function (d) { return !d.open; });
                    details.forEach(function (d) { d.open = open; });
                  });

                  function focusRef(id) {
                    const row = document.getElementById(id);
                    if (!row) return;
                    document.querySelectorAll('tr.highlight').forEach(function (r) {
                      r.classList.remove('highlight');
                    });
                    row.classList.add('highlight');
                    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
                  }

                  document.querySelectorAll('[data-ref]').forEach(function (el) {
                    el.addEventListener('click', function (e) {
                      e.preventDefault();
                      focusRef(el.getAttribute('data-ref'));
                    });
                  });
                })();
                """;
    }
}
