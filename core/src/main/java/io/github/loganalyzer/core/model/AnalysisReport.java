package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Итог анализа: все построенные таймлайны, сводка и предупреждения парсеров.
 * Именно этот объект сериализуется в JSON-отчёт.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class AnalysisReport {

    /** Числовая сводка по разбору. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class Summary {
        private int totalEvents;
        private int unparsedLines;
        private int errorEvents;
        private int warningEvents;
        private int exceptionEvents;
        private int timelines;
        private int failedTimelines;
        private int filesAnalyzed;

        public int getTotalEvents() {
            return totalEvents;
        }

        public void setTotalEvents(int v) {
            this.totalEvents = v;
        }

        public int getUnparsedLines() {
            return unparsedLines;
        }

        public void setUnparsedLines(int v) {
            this.unparsedLines = v;
        }

        public int getErrorEvents() {
            return errorEvents;
        }

        public void setErrorEvents(int v) {
            this.errorEvents = v;
        }

        public int getWarningEvents() {
            return warningEvents;
        }

        public void setWarningEvents(int v) {
            this.warningEvents = v;
        }

        public int getExceptionEvents() {
            return exceptionEvents;
        }

        public void setExceptionEvents(int v) {
            this.exceptionEvents = v;
        }

        public int getTimelines() {
            return timelines;
        }

        public void setTimelines(int v) {
            this.timelines = v;
        }

        public int getFailedTimelines() {
            return failedTimelines;
        }

        public void setFailedTimelines(int v) {
            this.failedTimelines = v;
        }

        public int getFilesAnalyzed() {
            return filesAnalyzed;
        }

        public void setFilesAnalyzed(int v) {
            this.filesAnalyzed = v;
        }
    }

    private final Instant generatedAt = Instant.now();
    private String tool = "log-analyzer";
    private String version = "0.1.0";
    private final List<String> inputs = new ArrayList<>();
    private final Summary summary = new Summary();
    private final List<Timeline> timelines = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String v) {
        this.tool = v;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String v) {
        this.version = v;
    }

    public List<String> getInputs() {
        return inputs;
    }

    public Summary getSummary() {
        return summary;
    }

    public List<Timeline> getTimelines() {
        return timelines;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void addWarning(String message) {
        if (message != null && !warnings.contains(message)) {
            warnings.add(message);
        }
    }
}
