package io.sessioninsights.domain.insight;

/** A replay moment an insight refers to: offset from session start, plus what happened there. */
public record Evidence(long offsetMs, String description) {
}
