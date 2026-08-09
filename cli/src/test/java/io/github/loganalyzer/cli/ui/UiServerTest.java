package io.github.loganalyzer.cli.ui;

import io.github.loganalyzer.core.config.AnalyzerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Локальный веб-интерфейс")
class UiServerTest {

    private static final String LOG = """
            2026-08-09T10:04:11.204+03:00  INFO 1 --- [order-service] [http-1] [8f3c2a1b,4b2a9c1d] \
            c.e.o.web.OrderController : Получен запрос POST /api/v1/orders
            2026-08-09T10:04:11.261+03:00 ERROR 1 --- [order-service] [http-1] [8f3c2a1b,4b2a9c1d] \
            c.e.o.web.OrderController : Не удалось обработать заказ
            java.lang.NullPointerException: profile is null
            \tat com.example.orders.service.PricingService.applyDiscount(PricingService.java:88)
            """;

    private UiServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new UiServer(AnalyzerConfig.defaults(), 0);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base() + path))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    @Test
    @DisplayName("Сервер слушает только петлевой интерфейс")
    void bindsToLoopbackOnly() {
        assertThat(server.url().getHost()).isEqualTo("localhost");
        assertThat(server.port()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Главная страница отдаётся с формой ввода")
    void servesPage() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/html").contains("utf-8");
        assertThat(response.body())
                .contains("Анализатор логов")
                .contains("id=\"text\"")
                .contains("Анализировать");
    }

    @Test
    @DisplayName("Вставленный отрывок анализируется и возвращается HTML-отчёт")
    void analysesPastedSnippet() throws Exception {
        HttpResponse<String> response = post("/api/analyze?format=html", LOG);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .startsWith("<!doctype html>")
                .contains("Вероятная причина")
                .contains("NullPointerException");
    }

    @Test
    @DisplayName("Формат json возвращает машиночитаемый отчёт")
    void returnsJson() throws Exception {
        HttpResponse<String> response = post("/api/analyze?format=json", LOG);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("\"rootCause\"").contains("8f3c2a1b");
    }

    @Test
    @DisplayName("Анализ файла с диска по указанному пути")
    void analysesFileByPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.log");
        Files.writeString(file, LOG, StandardCharsets.UTF_8);

        HttpResponse<String> response = post(
                "/api/analyze?format=json&path=" + URLEncoder.encode(file.toString(), StandardCharsets.UTF_8), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("NullPointerException");
    }

    @Test
    @DisplayName("Фильтры traceId и «только с ошибками» применяются")
    void appliesFilters() throws Exception {
        String withTwoTraces = LOG + """
                2026-08-09T10:09:40.001+03:00  INFO 1 --- [order-service] [http-9] [2b3c4d5e,1a2b3c4d] \
                c.e.o.web.OrderController : Получен запрос GET /api/v1/orders/88300
                """;

        HttpResponse<String> all = post("/api/analyze?format=json", withTwoTraces);
        HttpResponse<String> filtered = post("/api/analyze?format=json&onlyFailed=true&trace=8f3c2a", withTwoTraces);

        assertThat(all.body()).contains("2b3c4d5e");
        assertThat(filtered.body()).contains("8f3c2a1b").doesNotContain("2b3c4d5e");
    }

    @Test
    @DisplayName("Пустой ввод и несуществующий путь дают понятную ошибку 400")
    void reportsBadRequests() throws Exception {
        HttpResponse<String> empty = post("/api/analyze", "   ");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(empty.body()).contains("Пустой ввод");

        HttpResponse<String> missing = post("/api/analyze?path="
                + URLEncoder.encode("нет-такого-файла.log", StandardCharsets.UTF_8), "");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("не найден");
    }

    @Test
    @DisplayName("GET на API и неизвестный путь обрабатываются корректно")
    void handlesWrongRoutes() throws Exception {
        assertThat(get("/api/analyze").statusCode()).isEqualTo(405);
        assertThat(get("/нет-такой-страницы").statusCode()).isEqualTo(404);
        assertThat(get("/favicon.ico").statusCode()).isEqualTo(204);
    }
}
