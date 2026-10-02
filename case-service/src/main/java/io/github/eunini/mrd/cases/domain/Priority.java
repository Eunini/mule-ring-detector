package io.github.eunini.mrd.cases.domain;

import java.math.BigDecimal;

public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    private static final BigDecimal HIGH_AMOUNT = new BigDecimal("100000");
    private static final BigDecimal CRITICAL_AMOUNT = new BigDecimal("1000000");

    /**
     * Priority derived from the strongest model score, the number of alerts and the total
     * flagged value. Deterministic so that the same evidence always yields the same priority.
     */
    public static Priority of(double maxScore, int alertCount, BigDecimal totalAmountUsd) {
        BigDecimal total = totalAmountUsd == null ? BigDecimal.ZERO : totalAmountUsd;
        if ((maxScore >= 0.9 && alertCount >= 3) || total.compareTo(CRITICAL_AMOUNT) >= 0) {
            return CRITICAL;
        }
        if (maxScore >= 0.85 || alertCount >= 5 || total.compareTo(HIGH_AMOUNT) >= 0) {
            return HIGH;
        }
        if (maxScore >= 0.6) {
            return MEDIUM;
        }
        return LOW;
    }
}
