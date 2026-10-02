package io.github.eunini.mrd.cases.web.dto;

import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.domain.Disposition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CaseRequests {

    private CaseRequests() {
    }

    public record AssignRequest(@NotBlank @Size(max = 64) String assignee) {
    }

    public record TransitionRequest(@NotNull CaseStatus to, Disposition disposition,
                                    @Size(max = 2000) String comment) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record FilingRequestBody(@Size(max = 2000) String comment) {
    }

    public record FilingDecisionBody(@Size(max = 2000) String comment) {
    }

    public record FilingRejectionBody(@NotBlank @Size(max = 2000) String reason) {
    }
}
