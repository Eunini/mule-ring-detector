package io.github.eunini.mrd.cases.domain;

import java.util.EnumSet;
import java.util.Set;

public enum CaseStatus {
    OPEN,
    INVESTIGATING,
    ESCALATED,
    CLOSED,
    /** Terminal: the case was absorbed into an older overlapping case. */
    MERGED;

    /** Statuses in which a case still accepts new alerts and can be merged. */
    public static final Set<CaseStatus> ACTIVE = EnumSet.of(OPEN, INVESTIGATING, ESCALATED);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
