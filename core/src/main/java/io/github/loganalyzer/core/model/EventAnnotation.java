package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Пометка на событии таймлайна: что это за событие с точки зрения анализа.
 *
 * @param type  категория
 * @param label человекочитаемая метка, например {@code "Таймаут вызова платёжного шлюза"}
 * @param rule  имя сработавшего правила (или {@code "builtin"} для встроенной эвристики)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventAnnotation(AnnotationType type, String label, String rule) {

    public static EventAnnotation of(AnnotationType type, String label) {
        return new EventAnnotation(type, label, "builtin");
    }

    @Override
    public String toString() {
        return label == null ? type.name() : type.name() + ": " + label;
    }
}
