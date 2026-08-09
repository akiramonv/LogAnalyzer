package io.github.loganalyzer.core.parse;

import io.github.loganalyzer.core.model.ExceptionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Разбор стек-трейсов")
class StackTraceParserTest {

    @Test
    @DisplayName("Простое исключение с кадрами стека")
    void parsesSimpleException() {
        ExceptionInfo e = StackTraceParser.parse(List.of(
                "java.lang.NullPointerException: Cannot invoke \"Foo.bar()\" because \"foo\" is null",
                "\tat com.example.Service.process(Service.java:50)",
                "\tat com.example.App.main(App.java:10)"));

        assertThat(e).isNotNull();
        assertThat(e.getType()).isEqualTo("java.lang.NullPointerException");
        assertThat(e.simpleType()).isEqualTo("NullPointerException");
        assertThat(e.getMessage()).contains("because \"foo\" is null");
        assertThat(e.getFrames()).hasSize(2);
        assertThat(e.topFrame().declaringClass()).isEqualTo("com.example.Service");
        assertThat(e.topFrame().lineNumber()).isEqualTo(50);
    }

    @Test
    @DisplayName("Цепочка Caused by разворачивается до первопричины")
    void unwrapsCausedByChain() {
        ExceptionInfo e = StackTraceParser.parse(List.of(
                "org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection",
                "\tat org.springframework.jdbc.datasource.DataSourceUtils.getConnection(DataSourceUtils.java:82)",
                "Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,"
                        + " request timed out after 30001ms",
                "\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)",
                "\t... 41 common frames omitted"));

        assertThat(e.chain()).hasSize(2);
        assertThat(e.rootCause().simpleType()).isEqualTo("SQLTransientConnectionException");
        assertThat(e.rootCause().getMessage()).contains("Connection is not available");
        assertThat(e.rootCause().getFramesOmitted()).isEqualTo(41);
    }

    @Test
    @DisplayName("Префикс «Exception in thread» не мешает разбору")
    void handlesExceptionInThreadPrefix() {
        ExceptionInfo e = StackTraceParser.parse(List.of(
                "Exception in thread \"main\" java.lang.IllegalStateException: broken",
                "\tat com.example.App.main(App.java:12)"));

        assertThat(e.simpleType()).isEqualTo("IllegalStateException");
        assertThat(e.getMessage()).isEqualTo("broken");
    }

    @Test
    @DisplayName("Хвост Spring Boot вида ~[classes/:na] отбрасывается")
    void stripsSpringBootJarSuffix() {
        ExceptionInfo e = StackTraceParser.parse(List.of(
                "java.lang.IllegalArgumentException: bad",
                "\tat com.example.App.run(App.java:5) ~[classes/:na]"));

        assertThat(e.topFrame().declaringClass()).isEqualTo("com.example.App");
        assertThat(e.topFrame().fileName()).isEqualTo("App.java");
    }

    @Test
    @DisplayName("Строки стека и заголовки исключений распознаются")
    void recognisesStackLines() {
        assertThat(StackTraceParser.isStackTraceLine("\tat com.example.App.main(App.java:10)")).isTrue();
        assertThat(StackTraceParser.isStackTraceLine("Caused by: java.io.IOException: nope")).isTrue();
        assertThat(StackTraceParser.isStackTraceLine("\t... 12 more")).isTrue();
        assertThat(StackTraceParser.isStackTraceLine("обычное сообщение")).isFalse();

        assertThat(StackTraceParser.looksLikeExceptionHeader("java.lang.NullPointerException: msg")).isTrue();
        assertThat(StackTraceParser.looksLikeExceptionHeader("feign.RetryableException: connect timed out")).isTrue();
        assertThat(StackTraceParser.looksLikeExceptionHeader("Обработка заказа: начало")).isFalse();
    }
}
