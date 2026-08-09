package io.github.loganalyzer.core.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Загрузка наборов правил из YAML/JSON — из файла, ресурса classpath или строки.
 *
 * <p>Неизвестные ключи считаются ошибкой: опечатка в имени поля правила должна быть
 * замечена сразу, а не приводить к молча неработающему правилу.
 */
public final class RuleSetLoader {

    /** Ресурс со встроенным набором правил для типовых проблем Java/Spring-приложений. */
    public static final String DEFAULT_RULES_RESOURCE = "/rules/default-rules.yaml";

    private final ObjectMapper yaml;
    private final ObjectMapper json;

    public RuleSetLoader() {
        this.yaml = configure(com.fasterxml.jackson.databind.json.JsonMapper.builder(new YAMLFactory()));
        this.json = configure(com.fasterxml.jackson.databind.json.JsonMapper.builder());
    }

    private static ObjectMapper configure(com.fasterxml.jackson.databind.json.JsonMapper.Builder builder) {
        return builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    /** Загружает встроенный набор правил. */
    public RuleSet loadDefaults() {
        try (InputStream in = RuleSetLoader.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Встроенный набор правил не найден: " + DEFAULT_RULES_RESOURCE);
            }
            RuleSet set = yaml.readValue(in, RuleSet.class);
            validate(set, DEFAULT_RULES_RESOURCE);
            return set;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать встроенные правила", e);
        }
    }

    /**
     * Загружает набор правил из файла ({@code .yaml}, {@code .yml} или {@code .json}).
     *
     * @throws IllegalArgumentException если файл некорректен
     */
    public RuleSet load(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            RuleSet set = parse(content, file.toString());
            validate(set, file.toString());
            return set;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать файл правил: " + file, e);
        }
    }

    /** Разбирает содержимое как YAML или JSON (формат определяется по первому непустому символу). */
    public RuleSet parse(String content, String origin) {
        try {
            String trimmed = content.stripLeading();
            ObjectMapper mapper = trimmed.startsWith("{") ? json : yaml;
            RuleSet set = mapper.readValue(content, RuleSet.class);
            return set == null ? new RuleSet() : set;
        } catch (IOException e) {
            String reason = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    ? jpe.getOriginalMessage()
                    : e.getMessage();
            throw new IllegalArgumentException("Ошибка разбора правил (" + origin + "): " + reason, e);
        }
    }

    /** Загружает и объединяет несколько файлов правил. */
    public RuleSet loadAll(List<Path> files, boolean includeDefaults) {
        RuleSet result = includeDefaults ? loadDefaults() : new RuleSet();
        if (files != null) {
            for (Path file : files) {
                result.merge(load(file));
            }
        }
        return result;
    }

    /** Проверяет набор: имена уникальны, условия непустые. */
    public void validate(RuleSet set, String origin) {
        List<String> problems = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Rule rule : set.getRules()) {
            if (rule.getName() == null || rule.getName().isBlank()) {
                problems.add("правило без имени");
                continue;
            }
            if (seen.contains(rule.getName())) {
                problems.add("дублирующееся имя правила: " + rule.getName());
            }
            seen.add(rule.getName());
            if (rule.getWhen() == null || rule.getWhen().isEmpty()) {
                problems.add("правило '" + rule.getName() + "' не содержит условий в блоке when");
            }
            if (rule.getCause() != null
                    && (rule.getCause().getConfidence() < 0 || rule.getCause().getConfidence() > 1)) {
                problems.add("правило '" + rule.getName() + "': confidence должен быть в диапазоне 0..1");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Некорректный набор правил (" + origin + "): "
                    + String.join("; ", problems));
        }
    }
}
