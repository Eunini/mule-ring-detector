package io.github.eunini.mrd.cases.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Analyst workflow:
 * <pre>
 * OPEN -> INVESTIGATING -> ESCALATED -> CLOSED
 *              |    ^          |
 *              |    +----------+   (send back)
 *              +-> CLOSED          (FALSE_POSITIVE / NO_FURTHER_ACTION only)
 * </pre>
 * Closing with {@link Disposition#STR_FILED} is never a manual transition; it happens only
 * when a supervisor approves a filing request raised by someone else.
 */
public final class CaseStateMachine {

    private static final Map<CaseStatus, Set<CaseStatus>> TRANSITIONS = new EnumMap<>(CaseStatus.class);

    static {
        TRANSITIONS.put(CaseStatus.OPEN, EnumSet.of(CaseStatus.INVESTIGATING));
        TRANSITIONS.put(CaseStatus.INVESTIGATING, EnumSet.of(CaseStatus.ESCALATED, CaseStatus.CLOSED));
        TRANSITIONS.put(CaseStatus.ESCALATED, EnumSet.of(CaseStatus.INVESTIGATING, CaseStatus.CLOSED));
        TRANSITIONS.put(CaseStatus.CLOSED, EnumSet.noneOf(CaseStatus.class));
        TRANSITIONS.put(CaseStatus.MERGED, EnumSet.noneOf(CaseStatus.class));
    }

    private CaseStateMachine() {
    }

    public static boolean canTransition(CaseStatus from, CaseStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<CaseStatus> allowedFrom(CaseStatus from) {
        Set<CaseStatus> allowed = TRANSITIONS.get(from);
        return allowed == null || allowed.isEmpty() ? EnumSet.noneOf(CaseStatus.class) : EnumSet.copyOf(allowed);
    }

    /** Dispositions an analyst may choose when closing manually. */
    public static Set<Disposition> manualCloseDispositions() {
        return EnumSet.of(Disposition.FALSE_POSITIVE, Disposition.NO_FURTHER_ACTION);
    }
}
