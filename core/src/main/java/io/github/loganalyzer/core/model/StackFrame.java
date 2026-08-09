package io.github.loganalyzer.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Один кадр стек-трейса Java.
 *
 * @param declaringClass полное имя класса, например {@code com.example.Service}
 * @param methodName     имя метода
 * @param fileName       имя файла исходника (может отсутствовать)
 * @param lineNumber     номер строки (null для native/unknown)
 * @param nativeMethod   признак native-метода
 * @param raw            исходная строка кадра без отступов
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StackFrame(
        String declaringClass,
        String methodName,
        String fileName,
        Integer lineNumber,
        boolean nativeMethod,
        String raw) {

    /**
     * {@code at com.example.Service.process(Service.java:50)}, а также варианты
     * с модулем/загрузчиком ({@code at java.base/java.util.Objects.requireNonNull(Objects.java:233)})
     * и с {@code ~[jar:...]} суффиксом от Spring Boot.
     */
    private static final Pattern AT_LINE = Pattern.compile(
            "^\\s*at\\s+(?:[\\w.@$/]+/)?" +          // необязательный модуль/класслоадер
            "([\\w.$<>\\[\\]]+)\\.([\\w$<>]+)" +     // класс.метод
            "\\(([^)]*)\\).*$");

    /** @return {@code true} если строка похожа на кадр стек-трейса ({@code at ...}). */
    public static boolean isFrameLine(String line) {
        return line != null && AT_LINE.matcher(line).matches();
    }

    /**
     * Разбирает строку вида {@code at com.example.Service.process(Service.java:50)}.
     *
     * @return кадр или {@code null}, если строка не является кадром
     */
    public static StackFrame parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = AT_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String cls = m.group(1);
        String method = m.group(2);
        String location = m.group(3);
        String file = null;
        Integer lineNo = null;
        boolean isNative = false;
        if (location != null && !location.isEmpty()) {
            if (location.contains("Native Method")) {
                isNative = true;
            } else {
                int colon = location.lastIndexOf(':');
                if (colon > 0) {
                    file = location.substring(0, colon);
                    try {
                        lineNo = Integer.valueOf(location.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // "Unknown Source" и подобное — номера строки нет
                    }
                } else {
                    file = location;
                }
            }
        }
        return new StackFrame(cls, method, file, lineNo, isNative, line.trim());
    }

    /** @return {@code true} если кадр принадлежит пользовательскому коду, а не JDK/фреймворку. */
    public boolean isApplicationFrame(java.util.List<String> appPackages) {
        if (declaringClass == null) {
            return false;
        }
        if (appPackages == null || appPackages.isEmpty()) {
            return !declaringClass.startsWith("java.")
                    && !declaringClass.startsWith("javax.")
                    && !declaringClass.startsWith("jdk.")
                    && !declaringClass.startsWith("sun.")
                    && !declaringClass.startsWith("org.springframework.")
                    && !declaringClass.startsWith("org.apache.")
                    && !declaringClass.startsWith("io.netty.")
                    && !declaringClass.startsWith("reactor.")
                    && !declaringClass.startsWith("org.hibernate.");
        }
        return appPackages.stream().anyMatch(declaringClass::startsWith);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(declaringClass).append('.').append(methodName).append('(');
        if (nativeMethod) {
            sb.append("Native Method");
        } else if (fileName != null) {
            sb.append(fileName);
            if (lineNumber != null) {
                sb.append(':').append(lineNumber);
            }
        } else {
            sb.append("Unknown Source");
        }
        return sb.append(')').toString();
    }
}
