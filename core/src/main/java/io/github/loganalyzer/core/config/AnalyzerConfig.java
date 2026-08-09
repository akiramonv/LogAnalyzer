package io.github.loganalyzer.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.loganalyzer.core.analyze.AnalysisOptions;
import io.github.loganalyzer.core.correlate.CorrelationOptions;
import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.parse.LogPattern;
import io.github.loganalyzer.core.parse.ParseOptions;
import io.github.loganalyzer.core.timeline.TimelineOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Конфигурация анализа целиком: разбор, корреляция, таймлайн, правила, вывод причины.
 * Читается из YAML или JSON; любые секции необязательны — отсутствующие берут значения по умолчанию.
 *
 * <pre>
 * parse:
 *   charset: UTF-8
 *   zone: Europe/Moscow
 *   patterns:
 *     - name: my-format
 *       regex: '^(?&lt;ts&gt;\S+) (?&lt;level&gt;\w+) (?&lt;msg&gt;.*)$'
 * correlation:
 *   threadGapSeconds: 30
 * timeline:
 *   slowMs: 1500
 * rules:
 *   builtin: true
 *   files: [ ./my-rules.yaml ]
 * analysis:
 *   minConfidence: 0.3
 *   applicationPackages: [ com.mycompany ]
 * </pre>
 */
public final class AnalyzerConfig {

    /** Описание пользовательского шаблона строки лога. */
    public static final class PatternDef {
        private String name = "custom";
        private String regex;

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getRegex() {
            return regex;
        }

        public void setRegex(String v) {
            this.regex = v;
        }
    }

    /** Секция {@code parse}. */
    public static final class ParseSection {
        private String charset = "UTF-8";
        private String zone;
        private String defaultDate;
        private List<PatternDef> patterns = new ArrayList<>();
        private boolean builtinPatterns = true;
        private boolean extractFromMessage = true;
        private int maxEvents;
        private int maxMultilineLines = 2000;

        public String getCharset() {
            return charset;
        }

        public void setCharset(String v) {
            this.charset = v;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String v) {
            this.zone = v;
        }

        public String getDefaultDate() {
            return defaultDate;
        }

        public void setDefaultDate(String v) {
            this.defaultDate = v;
        }

        public List<PatternDef> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<PatternDef> v) {
            this.patterns = v == null ? new ArrayList<>() : v;
        }

        public boolean isBuiltinPatterns() {
            return builtinPatterns;
        }

        public void setBuiltinPatterns(boolean v) {
            this.builtinPatterns = v;
        }

        public boolean isExtractFromMessage() {
            return extractFromMessage;
        }

        public void setExtractFromMessage(boolean v) {
            this.extractFromMessage = v;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int v) {
            this.maxEvents = v;
        }

        public int getMaxMultilineLines() {
            return maxMultilineLines;
        }

        public void setMaxMultilineLines(int v) {
            this.maxMultilineLines = v;
        }
    }

    /** Секция {@code correlation}. */
    public static final class CorrelationSection {
        private List<CorrelationKind> priority = new ArrayList<>(List.of(
                CorrelationKind.TRACE, CorrelationKind.REQUEST, CorrelationKind.SESSION, CorrelationKind.THREAD));
        private long threadGapSeconds = 30;
        private boolean inheritTraceFromThread = true;
        private long inheritWindowSeconds = 10;
        private List<String> additionalKeys = new ArrayList<>();
        private boolean fallbackToFile = true;
        private int minEventsPerTimeline;

        public List<CorrelationKind> getPriority() {
            return priority;
        }

        public void setPriority(List<CorrelationKind> v) {
            this.priority = v == null ? new ArrayList<>() : v;
        }

        public long getThreadGapSeconds() {
            return threadGapSeconds;
        }

        public void setThreadGapSeconds(long v) {
            this.threadGapSeconds = v;
        }

        public boolean isInheritTraceFromThread() {
            return inheritTraceFromThread;
        }

        public void setInheritTraceFromThread(boolean v) {
            this.inheritTraceFromThread = v;
        }

        public long getInheritWindowSeconds() {
            return inheritWindowSeconds;
        }

        public void setInheritWindowSeconds(long v) {
            this.inheritWindowSeconds = v;
        }

        public List<String> getAdditionalKeys() {
            return additionalKeys;
        }

        public void setAdditionalKeys(List<String> v) {
            this.additionalKeys = v == null ? new ArrayList<>() : v;
        }

        public boolean isFallbackToFile() {
            return fallbackToFile;
        }

        public void setFallbackToFile(boolean v) {
            this.fallbackToFile = v;
        }

        public int getMinEventsPerTimeline() {
            return minEventsPerTimeline;
        }

        public void setMinEventsPerTimeline(int v) {
            this.minEventsPerTimeline = v;
        }
    }

    /** Секция {@code timeline}. */
    public static final class TimelineSection {
        private boolean dedup = true;
        private boolean dedupNormalize = true;
        private long dedupWindowSeconds = 300;
        private long slowMs = 1000;
        private boolean linkHttp = true;
        private long suspiciousGapSeconds = 5;

        public boolean isDedup() {
            return dedup;
        }

        public void setDedup(boolean v) {
            this.dedup = v;
        }

        public boolean isDedupNormalize() {
            return dedupNormalize;
        }

        public void setDedupNormalize(boolean v) {
            this.dedupNormalize = v;
        }

        public long getDedupWindowSeconds() {
            return dedupWindowSeconds;
        }

        public void setDedupWindowSeconds(long v) {
            this.dedupWindowSeconds = v;
        }

        public long getSlowMs() {
            return slowMs;
        }

        public void setSlowMs(long v) {
            this.slowMs = v;
        }

        public boolean isLinkHttp() {
            return linkHttp;
        }

        public void setLinkHttp(boolean v) {
            this.linkHttp = v;
        }

        public long getSuspiciousGapSeconds() {
            return suspiciousGapSeconds;
        }

        public void setSuspiciousGapSeconds(long v) {
            this.suspiciousGapSeconds = v;
        }
    }

    /** Секция {@code rules}. */
    public static final class RulesSection {
        private boolean builtin = true;
        private List<String> files = new ArrayList<>();

        public boolean isBuiltin() {
            return builtin;
        }

        public void setBuiltin(boolean v) {
            this.builtin = v;
        }

        public List<String> getFiles() {
            return files;
        }

        public void setFiles(List<String> v) {
            this.files = v == null ? new ArrayList<>() : v;
        }
    }

    /** Секция {@code analysis}. */
    public static final class AnalysisSection {
        private double minConfidence = 0.3;
        private int maxAlternatives = 3;
        private List<String> applicationPackages = new ArrayList<>();
        private boolean detectCascade = true;

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double v) {
            this.minConfidence = v;
        }

        public int getMaxAlternatives() {
            return maxAlternatives;
        }

        public void setMaxAlternatives(int v) {
            this.maxAlternatives = v;
        }

        public List<String> getApplicationPackages() {
            return applicationPackages;
        }

        public void setApplicationPackages(List<String> v) {
            this.applicationPackages = v == null ? new ArrayList<>() : v;
        }

        public boolean isDetectCascade() {
            return detectCascade;
        }

        public void setDetectCascade(boolean v) {
            this.detectCascade = v;
        }
    }

    private ParseSection parse = new ParseSection();
    private CorrelationSection correlation = new CorrelationSection();
    private TimelineSection timeline = new TimelineSection();
    private RulesSection rules = new RulesSection();
    private AnalysisSection analysis = new AnalysisSection();

    public ParseSection getParse() {
        return parse;
    }

    public void setParse(ParseSection v) {
        this.parse = v == null ? new ParseSection() : v;
    }

    public CorrelationSection getCorrelation() {
        return correlation;
    }

    public void setCorrelation(CorrelationSection v) {
        this.correlation = v == null ? new CorrelationSection() : v;
    }

    public TimelineSection getTimeline() {
        return timeline;
    }

    public void setTimeline(TimelineSection v) {
        this.timeline = v == null ? new TimelineSection() : v;
    }

    public RulesSection getRules() {
        return rules;
    }

    public void setRules(RulesSection v) {
        this.rules = v == null ? new RulesSection() : v;
    }

    public AnalysisSection getAnalysis() {
        return analysis;
    }

    public void setAnalysis(AnalysisSection v) {
        this.analysis = v == null ? new AnalysisSection() : v;
    }

    /** @return конфигурация со значениями по умолчанию. */
    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig();
    }

    /** Загружает конфигурацию из YAML/JSON-файла. */
    public static AnalyzerConfig load(Path file) {
        try {
            String content = Files.readString(file);
            boolean json = content.stripLeading().startsWith("{");
            ObjectMapper mapper = (json
                    ? com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    : com.fasterxml.jackson.databind.json.JsonMapper.builder(new YAMLFactory()))
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                    .build();
            AnalyzerConfig config = mapper.readValue(content, AnalyzerConfig.class);
            return config == null ? defaults() : config;
        } catch (IOException e) {
            String reason = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    ? jpe.getOriginalMessage()
                    : e.getMessage();
            throw new UncheckedIOException("Не удалось прочитать конфигурацию " + file + ": " + reason, e);
        }
    }

    /** @return настройки разбора логов. */
    public ParseOptions toParseOptions() {
        ParseOptions options = new ParseOptions()
                .setCharset(Charset.forName(parse.getCharset() == null ? "UTF-8" : parse.getCharset()))
                .setUseBuiltinPatterns(parse.isBuiltinPatterns())
                .setExtractFromMessage(parse.isExtractFromMessage())
                .setMaxEvents(parse.getMaxEvents())
                .setMaxMultilineLines(parse.getMaxMultilineLines());
        if (parse.getZone() != null && !parse.getZone().isBlank()) {
            options.setZone(ZoneId.of(parse.getZone()));
        }
        if (parse.getDefaultDate() != null && !parse.getDefaultDate().isBlank()) {
            options.setDefaultDate(LocalDate.parse(parse.getDefaultDate()));
        }
        for (PatternDef def : parse.getPatterns()) {
            if (def.getRegex() != null && !def.getRegex().isBlank()) {
                options.addPattern(LogPattern.of(def.getName(), def.getRegex()));
            }
        }
        return options;
    }

    /** @return настройки корреляции. */
    public CorrelationOptions toCorrelationOptions() {
        CorrelationOptions options = new CorrelationOptions()
                .setPriority(correlation.getPriority())
                .setThreadGap(Duration.ofSeconds(correlation.getThreadGapSeconds()))
                .setInheritTraceFromThread(correlation.isInheritTraceFromThread())
                .setInheritWindow(Duration.ofSeconds(correlation.getInheritWindowSeconds()))
                .setFallbackToFile(correlation.isFallbackToFile())
                .setMinEventsPerTimeline(correlation.getMinEventsPerTimeline());
        correlation.getAdditionalKeys().forEach(options::addAdditionalKey);
        return options;
    }

    /** @return настройки построения таймлайна. */
    public TimelineOptions toTimelineOptions() {
        return new TimelineOptions()
                .setDedupEnabled(timeline.isDedup())
                .setDedupNormalize(timeline.isDedupNormalize())
                .setDedupWindow(Duration.ofSeconds(timeline.getDedupWindowSeconds()))
                .setSlowMs(timeline.getSlowMs())
                .setLinkHttp(timeline.isLinkHttp())
                .setSuspiciousGap(Duration.ofSeconds(timeline.getSuspiciousGapSeconds()));
    }

    /** @return настройки анализа причин. */
    public AnalysisOptions toAnalysisOptions() {
        AnalysisOptions options = new AnalysisOptions()
                .setMinConfidence(analysis.getMinConfidence())
                .setMaxAlternatives(analysis.getMaxAlternatives())
                .setDetectCascade(analysis.isDetectCascade());
        analysis.getApplicationPackages().forEach(options::addApplicationPackage);
        return options;
    }
}
