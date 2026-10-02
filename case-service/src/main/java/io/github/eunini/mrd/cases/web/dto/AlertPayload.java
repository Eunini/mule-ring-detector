package io.github.eunini.mrd.cases.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Alert contract pushed by the detection engine. Unknown JSON properties are ignored. */
public record AlertPayload(
        @NotBlank @Size(max = 128) String alertId,
        @NotNull Long txId,
        @NotNull Instant timestamp,
        @NotBlank @Size(max = 64) String fromAccount,
        @NotBlank @Size(max = 64) String toAccount,
        @NotNull @PositiveOrZero @DecimalMax("1000000000000") BigDecimal amountUsd,
        @Size(max = 32) String currency,
        @Size(max = 32) String paymentFormat,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double score,
        @DecimalMin("0.0") @DecimalMax("1.0") Double threshold,
        @Size(max = 64) String modelVersion,
        @Size(max = 64) String ringId,
        @Size(max = 500) List<@NotBlank @Size(max = 64) String> ringAccounts,
        @Size(max = 32) List<@Valid @NotNull Detector> detectors,
        @Size(max = 64) List<@Valid @NotNull FeatureContribution> topFeatures) {

    public record Detector(
            @NotBlank @Size(max = 32) String name,
            Double value,
            @Size(max = 1000) String detail,
            @Size(max = 500) List<@Valid @NotNull Edge> edges) {
    }

    public record Edge(
            Long txId,
            @NotBlank @Size(max = 64) String from,
            @NotBlank @Size(max = 64) String to,
            BigDecimal amountUsd,
            Instant timestamp,
            Boolean laundering) {
    }

    public record FeatureContribution(@NotBlank @Size(max = 64) String name, Double contribution) {
    }

    public List<String> ringAccountsOrEmpty() {
        return ringAccounts == null ? List.of() : ringAccounts;
    }

    public List<Detector> detectorsOrEmpty() {
        return detectors == null ? List.of() : detectors;
    }

    public List<FeatureContribution> topFeaturesOrEmpty() {
        return topFeatures == null ? List.of() : topFeatures;
    }
}
