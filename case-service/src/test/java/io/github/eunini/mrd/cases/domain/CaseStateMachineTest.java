package io.github.eunini.mrd.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaseStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "OPEN, INVESTIGATING, true",
            "OPEN, ESCALATED, false",
            "OPEN, CLOSED, false",
            "INVESTIGATING, ESCALATED, true",
            "INVESTIGATING, CLOSED, true",
            "INVESTIGATING, OPEN, false",
            "ESCALATED, CLOSED, true",
            "ESCALATED, INVESTIGATING, true",
            "ESCALATED, OPEN, false",
            "CLOSED, OPEN, false",
            "CLOSED, INVESTIGATING, false",
            "MERGED, OPEN, false"
    })
    void transitions(CaseStatus from, CaseStatus to, boolean allowed) {
        assertThat(CaseStateMachine.canTransition(from, to)).isEqualTo(allowed);
    }

    @org.junit.jupiter.api.Test
    void strFiledIsNotAManualCloseDisposition() {
        assertThat(CaseStateMachine.manualCloseDispositions())
                .containsExactlyInAnyOrder(Disposition.FALSE_POSITIVE, Disposition.NO_FURTHER_ACTION);
        assertThat(CaseStateMachine.allowedFrom(CaseStatus.CLOSED)).isEmpty();
    }
}
