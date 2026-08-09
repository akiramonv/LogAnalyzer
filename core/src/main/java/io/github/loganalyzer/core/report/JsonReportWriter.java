package io.github.loganalyzer.core.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.loganalyzer.core.model.AnalysisReport;

import java.io.IOException;

/**
 * JSON-отчёт — основной машиночитаемый формат: удобен для CI, дашбордов и передачи в LLM.
 * Метки времени пишутся в ISO-8601, пустые поля опускаются.
 */
public final class JsonReportWriter implements ReportWriter {

    private final ObjectMapper mapper;

    public JsonReportWriter() {
        this(true);
    }

    public JsonReportWriter(boolean pretty) {
        this.mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(SerializationFeature.INDENT_OUTPUT, pretty)
                .serializationInclusion(JsonInclude.Include.NON_EMPTY)
                .build();
    }

    /** @return настроенный маппер (нужен, например, для сериализации отдельных таймлайнов). */
    public ObjectMapper mapper() {
        return mapper;
    }

    @Override
    public void write(AnalysisReport report, Appendable out) throws IOException {
        out.append(mapper.writeValueAsString(report));
        out.append(System.lineSeparator());
    }
}
