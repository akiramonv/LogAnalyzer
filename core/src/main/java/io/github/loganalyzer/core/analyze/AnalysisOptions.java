package io.github.loganalyzer.core.analyze;

import java.util.ArrayList;
import java.util.List;

/** Настройки анализа первопричины. */
public final class AnalysisOptions {

    /** Гипотезы с уверенностью ниже порога не попадают в отчёт. */
    private double minConfidence = 0.3;

    /** Сколько альтернативных гипотез сохранять помимо основной. */
    private int maxAlternatives = 3;

    /** Пакеты прикладного кода — кадры стека из них считаются «своими» и повышают точность вывода. */
    private final List<String> applicationPackages = new ArrayList<>();

    /** Учитывать каскад: считать первую ошибку причиной последующих. */
    private boolean detectCascade = true;

    public double getMinConfidence() {
        return minConfidence;
    }

    public AnalysisOptions setMinConfidence(double v) {
        this.minConfidence = Math.max(0, Math.min(1, v));
        return this;
    }

    public int getMaxAlternatives() {
        return maxAlternatives;
    }

    public AnalysisOptions setMaxAlternatives(int v) {
        this.maxAlternatives = Math.max(0, v);
        return this;
    }

    public List<String> getApplicationPackages() {
        return applicationPackages;
    }

    public AnalysisOptions addApplicationPackage(String pkg) {
        if (pkg != null && !pkg.isBlank()) {
            applicationPackages.add(pkg.trim());
        }
        return this;
    }

    public boolean isDetectCascade() {
        return detectCascade;
    }

    public AnalysisOptions setDetectCascade(boolean v) {
        this.detectCascade = v;
        return this;
    }
}
