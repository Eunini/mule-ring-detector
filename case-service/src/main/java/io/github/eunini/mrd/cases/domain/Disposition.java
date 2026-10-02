package io.github.eunini.mrd.cases.domain;

public enum Disposition {
    FALSE_POSITIVE,
    NO_FURTHER_ACTION,
    /** Only reachable through the four-eyes filing approval. */
    STR_FILED
}
