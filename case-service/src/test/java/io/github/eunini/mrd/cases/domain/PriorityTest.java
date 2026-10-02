package io.github.eunini.mrd.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriorityTest {

    @Test
    void priorityReflectsScoreVolumeAndValue() {
        assertThat(Priority.of(0.95, 3, new BigDecimal("5000"))).isEqualTo(Priority.CRITICAL);
        assertThat(Priority.of(0.72, 1, new BigDecimal("2500000"))).isEqualTo(Priority.CRITICAL);
        assertThat(Priority.of(0.95, 1, new BigDecimal("5000"))).isEqualTo(Priority.HIGH);
        assertThat(Priority.of(0.70, 1, new BigDecimal("150000"))).isEqualTo(Priority.HIGH);
        assertThat(Priority.of(0.70, 1, new BigDecimal("500"))).isEqualTo(Priority.MEDIUM);
        assertThat(Priority.of(0.40, 1, BigDecimal.ONE)).isEqualTo(Priority.LOW);
    }
}
