package io.github.eunini.mrd.cases.report;

import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.AuditEvent;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.web.dto.AlertPayload;
import io.github.eunini.mrd.cases.web.dto.GraphView;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Renders the printable case summary as well-formed XHTML (so the same markup feeds the PDF
 * renderer) with an inline SVG of the evidence graph in a deterministic circular layout.
 */
@Component
public class CaseSummaryRenderer {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Map<String, String> ROLE_COLOURS = Map.of(
            "source", "#c0392b",
            "intermediary", "#d68910",
            "sink", "#1f618d",
            "member", "#7f8c8d");

    public record SummaryInput(CaseEntity caseEntity, List<AlertEntity> alerts,
                               Function<AlertEntity, AlertPayload> evidence, GraphView graph,
                               List<AuditEvent> audit, String generatedBy, Instant generatedAt) {
    }

    public String render(SummaryInput in) {
        CaseEntity c = in.caseEntity();
        StringBuilder h = new StringBuilder(32_768);
        h.append("<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\"/>\n<title>").append(esc(c.getReference())).append(" case summary</title>\n")
                .append("<style>\n").append(CSS).append("</style>\n</head>\n<body>\n");

        h.append("<header><div class=\"brand\">mule-ring-detector &#183; case summary</div>")
                .append("<h1>").append(esc(c.getReference())).append("</h1>")
                .append("<p class=\"meta\">Generated ").append(esc(TS.format(in.generatedAt())))
                .append(" by ").append(esc(in.generatedBy())).append("</p></header>\n");

        h.append("<section><h2>Case</h2><table class=\"kv\">");
        kv(h, "Status", c.getStatus().name());
        kv(h, "Disposition", c.getDisposition() == null ? "-" : c.getDisposition().name());
        kv(h, "Priority", c.getPriority().name());
        kv(h, "Assignee", c.getAssignee() == null ? "unassigned" : c.getAssignee());
        kv(h, "Opened", TS.format(c.getCreatedAt()));
        kv(h, "Last updated", TS.format(c.getUpdatedAt()));
        if (c.getClosedAt() != null) {
            kv(h, "Closed", TS.format(c.getClosedAt()));
        }
        h.append("</table></section>\n");

        h.append("<section><h2>Risk</h2><table class=\"kv\">");
        kv(h, "Maximum model score", String.format(Locale.ROOT, "%.3f", c.getMaxScore()));
        kv(h, "Alerts", String.valueOf(c.getAlertCount()));
        kv(h, "Accounts", String.valueOf(c.getAccounts().size()));
        kv(h, "Total flagged value (USD)", c.getTotalAmountUsd().setScale(2, RoundingMode.HALF_UP).toPlainString());
        h.append("</table></section>\n");

        h.append("<section><h2>Evidence graph</h2>");
        h.append(svg(in.graph()));
        h.append("<p class=\"legend\">");
        for (String role : List.of("source", "intermediary", "sink", "member")) {
            h.append("<span class=\"dot\" style=\"background:").append(ROLE_COLOURS.get(role)).append("\"></span>")
                    .append(role).append(" &#160; ");
        }
        h.append("<span class=\"line alerted\"></span>alerted transaction &#160; ")
                .append("<span class=\"line\"></span>detector evidence</p></section>\n");

        Map<String, Integer> alertsPerAccount = new HashMap<>();
        in.alerts().forEach(a -> {
            alertsPerAccount.merge(a.getFromAccount(), 1, Integer::sum);
            alertsPerAccount.merge(a.getToAccount(), 1, Integer::sum);
        });
        h.append("<section><h2>Accounts</h2><table class=\"grid\"><thead><tr><th>Account</th><th>Bank</th>")
                .append("<th>Graph role</th><th>Ring member</th><th>Alerted txns</th></tr></thead><tbody>");
        for (GraphView.Node node : in.graph().nodes()) {
            h.append("<tr><td class=\"mono\">").append(esc(node.id())).append("</td><td>").append(esc(node.bank()))
                    .append("</td><td>").append(esc(node.role())).append("</td><td>").append(node.ringMember() ? "yes" : "no")
                    .append("</td><td>").append(alertsPerAccount.getOrDefault(node.id(), 0)).append("</td></tr>");
        }
        h.append("</tbody></table></section>\n");

        h.append("<section><h2>Alert timeline</h2><table class=\"grid\"><thead><tr><th>Time</th><th>Alert</th>")
                .append("<th>From</th><th>To</th><th class=\"num\">USD</th><th>Format</th><th class=\"num\">Score</th>")
                .append("<th>Ring</th></tr></thead><tbody>");
        for (AlertEntity a : in.alerts()) {
            h.append("<tr><td>").append(esc(TS.format(a.getEventTs()))).append("</td><td class=\"mono\">")
                    .append(esc(a.getAlertId())).append("</td><td class=\"mono\">").append(esc(a.getFromAccount()))
                    .append("</td><td class=\"mono\">").append(esc(a.getToAccount())).append("</td><td class=\"num\">")
                    .append(a.getAmountUsd().toPlainString()).append("</td><td>").append(esc(nz(a.getPaymentFormat())))
                    .append("</td><td class=\"num\">").append(String.format(Locale.ROOT, "%.3f", a.getScore()))
                    .append("</td><td>").append(esc(nz(a.getRingId()))).append("</td></tr>");
        }
        h.append("</tbody></table></section>\n");

        h.append("<section><h2>Detector evidence</h2><table class=\"grid\"><thead><tr><th>Alert</th><th>Detector</th>")
                .append("<th>Indicator</th><th class=\"num\">Value</th><th>Detail</th><th class=\"num\">Edges</th>")
                .append("</tr></thead><tbody>");
        for (AlertEntity a : in.alerts()) {
            AlertPayload payload = in.evidence().apply(a);
            for (AlertPayload.Detector d : payload.detectorsOrEmpty()) {
                h.append("<tr><td class=\"mono\">").append(esc(a.getAlertId())).append("</td><td>").append(esc(d.name()))
                        .append("</td><td class=\"mono\">").append(esc(DetectorIndicators.forDetector(d.name()).code()))
                        .append("</td><td class=\"num\">")
                        .append(d.value() == null ? "-" : String.format(Locale.ROOT, "%.2f", d.value()))
                        .append("</td><td>").append(esc(nz(d.detail()))).append("</td><td class=\"num\">")
                        .append(d.edges() == null ? 0 : d.edges().size()).append("</td></tr>");
            }
        }
        h.append("</tbody></table></section>\n");

        h.append("<section><h2>Audit trail</h2><table class=\"grid\"><thead><tr><th>#</th><th>Time</th><th>Actor</th>")
                .append("<th>Action</th><th>Status</th><th>Details</th></tr></thead><tbody>");
        for (AuditEvent e : in.audit()) {
            String status = e.getFromStatus() == null && e.getToStatus() == null ? "-"
                    : (e.getFromStatus() == null ? "" : e.getFromStatus().name())
                    + (e.getFromStatus() == e.getToStatus() ? "" : " &#8594; " + (e.getToStatus() == null ? "" : e.getToStatus().name()));
            h.append("<tr><td>").append(e.getId()).append("</td><td>").append(esc(TS.format(e.getCreatedAt())))
                    .append("</td><td>").append(esc(e.getActor())).append("</td><td>").append(esc(e.getAction().name()))
                    .append("</td><td>").append(status).append("</td><td>").append(esc(nz(e.getDetails())))
                    .append("</td></tr>");
        }
        h.append("</tbody></table></section>\n");

        h.append("<footer>Model scores and detector findings are investigative leads, not determinations of "
                + "wrongdoing. Indicator codes are project-specific demonstration values.</footer>\n");
        h.append("</body>\n</html>\n");
        return h.toString();
    }

    /** Circular layout: nodes evenly spaced on an ellipse, edges drawn as straight arrows. */
    static String svg(GraphView graph) {
        int width = 680;
        int height = 540;
        double cx = width / 2.0;
        double cy = height / 2.0;
        double rx = width / 2.0 - 140;
        double ry = height / 2.0 - 40;
        List<GraphView.Node> nodes = graph.nodes();
        Map<String, double[]> pos = new HashMap<>();
        int n = nodes.size();
        for (int i = 0; i < n; i++) {
            double angle = -Math.PI / 2 + 2 * Math.PI * i / Math.max(n, 1);
            double scale = n == 1 ? 0 : 1;
            pos.put(nodes.get(i).id(),
                    new double[] {cx + scale * rx * Math.cos(angle), cy + scale * ry * Math.sin(angle), angle});
        }
        StringBuilder s = new StringBuilder(8_192);
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"graph\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" role=\"img\" aria-label=\"Evidence graph\">")
                .append("<defs>")
                .append("<marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"18\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\">")
                .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"#95a5a6\"/></marker>")
                .append("<marker id=\"arrow-alert\" viewBox=\"0 0 10 10\" refX=\"18\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\">")
                .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"#c0392b\"/></marker>")
                .append("</defs>")
                .append("<rect x=\"0\" y=\"0\" width=\"").append(width).append("\" height=\"").append(height)
                .append("\" fill=\"#ffffff\" stroke=\"#d5d8dc\"/>");
        for (GraphView.Edge e : graph.edges()) {
            double[] a = pos.get(e.source());
            double[] b = pos.get(e.target());
            if (a == null || b == null || e.source().equals(e.target())) {
                continue;
            }
            boolean alerted = e.alerted();
            s.append("<line x1=\"").append(f(a[0])).append("\" y1=\"").append(f(a[1])).append("\" x2=\"")
                    .append(f(b[0])).append("\" y2=\"").append(f(b[1])).append("\" stroke=\"")
                    .append(alerted ? "#c0392b" : "#95a5a6").append("\" stroke-width=\"").append(alerted ? "2" : "1")
                    .append("\" marker-end=\"url(#").append(alerted ? "arrow-alert" : "arrow").append(")\"/>");
        }
        int fontSize = n > 30 ? 7 : 9;
        for (GraphView.Node node : nodes) {
            double[] p = pos.get(node.id());
            String colour = ROLE_COLOURS.getOrDefault(node.role(), "#7f8c8d");
            s.append("<circle cx=\"").append(f(p[0])).append("\" cy=\"").append(f(p[1])).append("\" r=\"8\" fill=\"")
                    .append(colour).append("\" stroke=\"").append(node.alerted() ? "#000000" : "#ffffff")
                    .append("\" stroke-width=\"1.5\"/>");
            double lx = p[0] + 20 * Math.cos(p[2]);
            double ly = p[1] + 20 * Math.sin(p[2]) + 3;
            String anchor = Math.cos(p[2]) > 0.2 ? "start" : Math.cos(p[2]) < -0.2 ? "end" : "middle";
            s.append("<text x=\"").append(f(lx)).append("\" y=\"").append(f(ly)).append("\" font-size=\"")
                    .append(fontSize).append("\" font-family=\"sans-serif\" fill=\"#2c3e50\" text-anchor=\"")
                    .append(anchor).append("\">").append(esc(node.label())).append("</text>");
        }
        s.append("</svg>");
        return s.toString();
    }

    private static void kv(StringBuilder h, String key, String value) {
        h.append("<tr><th>").append(esc(key)).append("</th><td>").append(esc(value)).append("</td></tr>");
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String nz(String v) {
        return v == null ? "-" : v;
    }

    static String esc(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> {
                    if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') {
                        sb.append(' ');
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static final String CSS = """
            @page { size: A4; margin: 14mm; }
            body { font-family: sans-serif; font-size: 10pt; color: #1c2833; margin: 0; }
            header { border-bottom: 2px solid #1c2833; margin-bottom: 10px; padding-bottom: 6px; }
            .brand { font-size: 8pt; text-transform: uppercase; letter-spacing: 1px; color: #566573; }
            h1 { font-size: 18pt; margin: 4px 0; }
            h2 { font-size: 12pt; margin: 14px 0 6px; border-bottom: 1px solid #d5d8dc; padding-bottom: 2px; }
            .meta { color: #566573; margin: 0; font-size: 9pt; }
            table { border-collapse: collapse; width: 100%; }
            table.kv { width: auto; }
            table.kv th { text-align: left; font-weight: normal; color: #566573; padding: 2px 16px 2px 0; }
            table.kv td { font-weight: bold; padding: 2px 0; }
            table.grid th, table.grid td { border: 1px solid #d5d8dc; padding: 3px 5px; text-align: left; vertical-align: top; font-size: 8.5pt; }
            table.grid th { background: #f2f3f4; }
            td.num, th.num { text-align: right; }
            .mono { font-family: monospace; font-size: 8pt; }
            tr { page-break-inside: avoid; }
            svg.graph { display: block; margin: 0 auto; }
            .legend { font-size: 8pt; color: #566573; text-align: center; }
            .dot { display: inline-block; width: 8px; height: 8px; border-radius: 4px; margin-right: 3px; }
            .line { display: inline-block; width: 16px; height: 0; border-top: 2px solid #95a5a6; margin: 0 3px 2px 0; }
            .line.alerted { border-top-color: #c0392b; }
            footer { margin-top: 16px; font-size: 8pt; color: #566573; border-top: 1px solid #d5d8dc; padding-top: 4px; }
            """;
}
