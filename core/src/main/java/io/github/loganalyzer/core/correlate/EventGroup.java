package io.github.loganalyzer.core.correlate;

import io.github.loganalyzer.core.model.CorrelationKind;
import io.github.loganalyzer.core.model.LogEvent;

import java.util.ArrayList;
import java.util.List;

/** Группа событий, отнесённых к одному инциденту. */
public final class EventGroup {

    private final String key;
    private final CorrelationKind kind;
    private final List<LogEvent> events = new ArrayList<>();

    public EventGroup(String key, CorrelationKind kind) {
        this.key = key;
        this.kind = kind;
    }

    public String getKey() {
        return key;
    }

    public CorrelationKind getKind() {
        return kind;
    }

    public List<LogEvent> getEvents() {
        return events;
    }

    public void add(LogEvent event) {
        events.add(event);
    }

    public int size() {
        return events.size();
    }

    @Override
    public String toString() {
        return kind + ":" + key + " (" + events.size() + ")";
    }
}
