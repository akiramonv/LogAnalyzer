package io.github.loganalyzer.core.report;

import io.github.loganalyzer.core.model.AnalysisReport;

import java.io.IOException;

/** Формат вывода отчёта. */
public interface ReportWriter {

    /** Записывает отчёт в приёмник (файл, stdout, буфер). */
    void write(AnalysisReport report, Appendable out) throws IOException;

    /** @return отчёт в виде строки. */
    default String writeToString(AnalysisReport report) {
        StringBuilder sb = new StringBuilder();
        try {
            write(report, sb);
        } catch (IOException e) {
            throw new IllegalStateException("Ошибка формирования отчёта", e);
        }
        return sb.toString();
    }
}
