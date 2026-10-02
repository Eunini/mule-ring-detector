package io.github.eunini.mrd.cases.web.dto;

import java.util.List;

public record IngestResponse(int received, int accepted, int duplicates, List<Result> results) {

    public enum Outcome { ACCEPTED, DUPLICATE }

    public record Result(String alertId, Outcome outcome, Long caseId, String caseReference) {
    }
}
