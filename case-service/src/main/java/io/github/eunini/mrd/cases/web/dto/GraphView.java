package io.github.eunini.mrd.cases.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record GraphView(List<Node> nodes, List<Edge> edges) {

    /** role: source, sink, intermediary or member (ring member with no observed edge). */
    public record Node(String id, String label, String role, String bank, boolean ringMember, boolean alerted) {
    }

    /** detector lists every detector (or "alert") that cited the transaction, comma separated. */
    public record Edge(String source, String target, BigDecimal amountUsd, Long txId, Instant timestamp,
                       String detector, Boolean laundering, boolean alerted) {
    }
}
