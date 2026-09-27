package io.sessioninsights.domain.session;

/** Lifecycle of a session through AI analysis and human review (F6, F7). */
public enum AnalysisStatus {
    PENDING, PROCESSING, AI_ANALYZED, REVIEW_REQUIRED, HUMAN_APPROVED, HUMAN_REJECTED, FAILED
}
