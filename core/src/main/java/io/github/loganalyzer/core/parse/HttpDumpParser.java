package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.HttpExchange;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор «сырых» HTTP-дампов, которые часто кладут рядом с логами:
 * строка запроса/статуса, заголовки, пустая строка, тело.
 *
 * <pre>
 * GET /api/user?id=abc123 HTTP/1.1
 * Host: example.com
 *
 * HTTP/1.1 400 Bad Request
 * Content-Type: application/json
 *
 * {"status":400,"message":"Number format exception"}
 * </pre>
 */
public final class HttpDumpParser {

    private static final Pattern REQUEST_LINE = Pattern.compile(
            "^(?<method>GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)\\s+(?<url>\\S+)\\s+HTTP/(?<version>\\d(?:\\.\\d)?)\\s*$");

    private static final Pattern STATUS_LINE = Pattern.compile(
            "^HTTP/(?<version>\\d(?:\\.\\d)?)\\s+(?<status>[1-5]\\d{2})(?:\\s+(?<reason>.*))?$");

    private static final Pattern HEADER_LINE = Pattern.compile("^(?<name>[A-Za-z][A-Za-z0-9\\-_]*):\\s*(?<value>.*)$");

    private HttpDumpParser() {
    }

    public static boolean isRequestLine(String line) {
        return line != null && REQUEST_LINE.matcher(line.trim()).matches();
    }

    public static boolean isStatusLine(String line) {
        return line != null && STATUS_LINE.matcher(line.trim()).matches();
    }

    /** @return {@code true} если строка начинает HTTP-дамп (запрос или ответ). */
    public static boolean isDumpStart(String line) {
        return isRequestLine(line) || isStatusLine(line);
    }

    /**
     * Разбирает блок строк как HTTP-дамп.
     *
     * @param lines строки блока (первая — строка запроса или статуса)
     * @return обмен или {@code null}, если блок не является HTTP-дампом
     */
    public static HttpExchange parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        String first = lines.get(0).trim();
        HttpExchange.Builder b = HttpExchange.builder();

        Matcher req = REQUEST_LINE.matcher(first);
        Matcher res = STATUS_LINE.matcher(first);
        if (req.matches()) {
            b.method(req.group("method")).url(req.group("url"));
            b.direction(req.group("url").startsWith("http")
                    ? HttpExchange.Direction.OUTBOUND
                    : HttpExchange.Direction.INBOUND);
        } else if (res.matches()) {
            b.status(Integer.valueOf(res.group("status")));
            b.statusText(res.group("reason"));
        } else {
            return null;
        }

        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inBody) {
                if (line.isBlank()) {
                    inBody = true;
                    continue;
                }
                // Некоторые клиенты печатают тело сразу после заголовков с префиксом "Body:"
                if (line.regionMatches(true, 0, "Body:", 0, 5)) {
                    inBody = true;
                    String rest = line.substring(5).trim();
                    if (!rest.isEmpty()) {
                        body.append(rest);
                    }
                    continue;
                }
                Matcher h = HEADER_LINE.matcher(line.trim());
                if (h.matches()) {
                    String name = h.group("name");
                    String value = h.group("value");
                    b.header(name, value);
                    if ("Host".equalsIgnoreCase(name)) {
                        b.host(value);
                    }
                    continue;
                }
                // Строка не заголовок — считаем, что началось тело
                inBody = true;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line);
        }
        if (body.length() > 0) {
            b.body(body.toString());
        }
        return b.build();
    }
}
