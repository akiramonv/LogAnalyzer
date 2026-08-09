package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.ExceptionInfo;
import io.github.loganalyzer.core.model.StackFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор стек-трейсов Java, включая цепочку {@code Caused by}, блоки {@code Suppressed}
 * и свёрнутые кадры {@code ... 42 more}.
 *
 * <p>Именно цепочка причин даёт анализатору главный сигнал: настоящая причина сбоя
 * почти всегда находится в самом глубоком {@code Caused by}, а не в верхнем исключении.
 */
public final class StackTraceParser {

    /** {@code java.lang.NullPointerException: Cannot invoke ...} */
    private static final Pattern HEADER = Pattern.compile(
            "^(?<type>[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)(?:\\s*:\\s?(?<msg>.*))?$", Pattern.DOTALL);

    /** {@code Exception in thread "main" java.lang.IllegalStateException: ...} */
    private static final Pattern IN_THREAD = Pattern.compile(
            "^Exception in thread \"(?<thread>[^\"]*)\"\\s+(?<rest>.*)$", Pattern.DOTALL);

    private static final Pattern CAUSED_BY = Pattern.compile("^\\s*Caused by:\\s*(?<rest>.*)$", Pattern.DOTALL);
    private static final Pattern SUPPRESSED = Pattern.compile("^\\s*Suppressed:\\s*(?<rest>.*)$", Pattern.DOTALL);
    private static final Pattern MORE = Pattern.compile("^\\s*\\.{3}\\s+(?<count>\\d+)\\s+(?:more|common frames omitted).*$");

    /** Типичные окончания имён классов исключений — усиливают уверенность распознавания заголовка. */
    private static final List<String> THROWABLE_SUFFIXES =
            List.of("Exception", "Error", "Throwable", "Failure", "Fault", "Violation", "Timeout");

    private StackTraceParser() {
    }

    /** @return {@code true} если строка похожа на заголовок исключения ({@code com.foo.BarException: текст}). */
    public static boolean looksLikeExceptionHeader(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String s = stripDecorations(line).trim();
        if (s.startsWith("Caused by:") || s.startsWith("Suppressed:") || s.startsWith("Exception in thread")) {
            return true;
        }
        int colon = s.indexOf(':');
        String head = colon > 0 ? s.substring(0, colon) : s;
        if (head.isEmpty() || head.contains(" ") || !head.contains(".")) {
            return false;
        }
        return THROWABLE_SUFFIXES.stream().anyMatch(head::endsWith);
    }

    /** @return {@code true} если строка — часть стек-трейса (кадр, «Caused by», «... N more», «Suppressed»). */
    public static boolean isStackTraceLine(String line) {
        if (line == null) {
            return false;
        }
        String s = stripDecorations(line);
        return StackFrame.isFrameLine(s)
                || CAUSED_BY.matcher(s).matches()
                || SUPPRESSED.matcher(s).matches()
                || MORE.matcher(s).matches();
    }

    /**
     * Разбирает блок строк в структуру исключения.
     *
     * @param lines строки блока: заголовок + кадры + вложенные {@code Caused by}
     * @return разобранное исключение или {@code null}, если блок не похож на стек-трейс
     */
    public static ExceptionInfo parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                cleaned.add(stripDecorations(line));
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        Cursor cursor = new Cursor(cleaned, 0);
        return parseOne(cursor);
    }

    /** Разбирает одно исключение начиная с позиции курсора, включая его причины. */
    private static ExceptionInfo parseOne(Cursor c) {
        if (c.eof()) {
            return null;
        }
        String header = c.next();
        Matcher inThread = IN_THREAD.matcher(header);
        if (inThread.matches()) {
            header = inThread.group("rest");
        }
        Matcher causedBy = CAUSED_BY.matcher(header);
        if (causedBy.matches()) {
            header = causedBy.group("rest");
        }
        Matcher suppressed = SUPPRESSED.matcher(header);
        if (suppressed.matches()) {
            header = suppressed.group("rest");
        }

        String type = header;
        String message = null;
        Matcher hm = HEADER.matcher(header);
        if (hm.matches()) {
            type = hm.group("type");
            message = hm.group("msg");
        } else {
            int colon = header.indexOf(": ");
            if (colon > 0) {
                type = header.substring(0, colon);
                message = header.substring(colon + 2);
            }
        }
        if (message != null && message.isBlank()) {
            message = null;
        }

        List<StackFrame> frames = new ArrayList<>();
        List<ExceptionInfo> suppressedList = new ArrayList<>();
        ExceptionInfo cause = null;
        int omitted = 0;

        while (!c.eof()) {
            String line = c.peek();
            if (StackFrame.isFrameLine(line)) {
                StackFrame f = StackFrame.parse(c.next());
                if (f != null) {
                    frames.add(f);
                }
                continue;
            }
            Matcher more = MORE.matcher(line);
            if (more.matches()) {
                c.next();
                omitted = Integer.parseInt(more.group("count"));
                continue;
            }
            if (SUPPRESSED.matcher(line).matches()) {
                ExceptionInfo s = parseOne(c);
                if (s != null) {
                    suppressedList.add(s);
                }
                continue;
            }
            if (CAUSED_BY.matcher(line).matches()) {
                cause = parseOne(c);
                break;
            }
            // Многострочное сообщение исключения: строка не кадр и не служебная —
            // считаем её продолжением сообщения, если кадров ещё не было.
            if (frames.isEmpty() && !looksLikeExceptionHeader(line)) {
                message = (message == null ? "" : message + "\n") + c.next().trim();
                continue;
            }
            break;
        }

        return new ExceptionInfo(type, message, frames, cause, suppressedList, omitted);
    }

    /**
     * Убирает «украшения» вокруг кадров стека, которые добавляют логгеры и агрегаторы:
     * ведущие символы продолжения ({@code \t}, {@code |}, {@code >}), суффиксы Spring Boot
     * {@code ~[classes/:na]} и префиксы вида {@code [main] }.
     */
    private static String stripDecorations(String line) {
        String s = line;
        // Docker/k8s-логи иногда добавляют префикс "stdout F "
        s = s.replaceFirst("^\\s*std(?:out|err)\\s+F\\s+", "");
        // Хвост Spring Boot: at com.foo.Bar(Bar.java:1) ~[classes/:na]
        s = s.replaceAll("\\s*[~\\[]\\[[^\\]]*\\]\\s*$", "");
        s = s.replaceAll("\\s*~\\[[^\\]]*\\]\\s*$", "");
        return s;
    }

    /** Простой курсор по списку строк. */
    private static final class Cursor {
        private final List<String> lines;
        private int index;

        Cursor(List<String> lines, int index) {
            this.lines = lines;
            this.index = index;
        }

        boolean eof() {
            return index >= lines.size();
        }

        String peek() {
            return lines.get(index);
        }

        String next() {
            return lines.get(index++);
        }
    }
}
