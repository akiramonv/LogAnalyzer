package io.github.loganalyzer.core.timeline;

import io.github.loganalyzer.core.model.LogEvent;

import java.util.regex.Pattern;

/**
 * Вычисляет «отпечаток» события для дедупликации.
 *
 * <p>Повторяющиеся сообщения почти всегда отличаются идентификаторами и числами
 * ({@code Retry attempt 3 for order 4815162342}), поэтому в режиме нормализации
 * такие фрагменты заменяются плейсхолдерами — это и позволяет схлопнуть серию ретраев
 * в одну строку таймлайна с пометкой «×5».
 */
public final class EventFingerprint {

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-fA-F]{8,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern TIMESTAMP_LIKE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?");

    private EventFingerprint() {
    }

    /**
     * @param event      событие
     * @param normalize  заменять ли переменные части сообщения плейсхолдерами
     * @return строка-отпечаток; равные отпечатки означают «то же самое событие»
     */
    public static String of(LogEvent event, boolean normalize) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.getLevel()).append('|');
        sb.append(event.getLogger() == null ? "" : event.getLogger()).append('|');
        sb.append(normalize ? normalize(event.getMessage()) : event.getMessage());
        if (event.getException() != null) {
            sb.append('|').append(event.getException().fingerprint());
        }
        if (event.getHttp() != null) {
            sb.append('|').append(event.getHttp().getMethod())
                    .append(' ').append(normalize
                            ? normalize(event.getHttp().getUrl())
                            : event.getHttp().getUrl())
                    .append(" -> ").append(event.getHttp().getStatus());
        }
        return sb.toString();
    }

    /** Заменяет переменные фрагменты текста на плейсхолдеры. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = TIMESTAMP_LIKE.matcher(text).replaceAll("<ts>");
        s = UUID.matcher(s).replaceAll("<uuid>");
        s = HEX.matcher(s).replaceAll("<hex>");
        s = NUMBER.matcher(s).replaceAll("<n>");
        return s;
    }
}
