package io.github.eunini.mrd.cases.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.mrd.cases.web.dto.GraphView;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class CaseSummaryRendererTest {

    @Test
    void svgPlacesEveryNodeAndEdgeAndIsWellFormed() throws Exception {
        GraphView graph = new GraphView(
                List.of(new GraphView.Node("1:A", "A @1", "source", "1", true, true),
                        new GraphView.Node("2:B", "B @2", "intermediary", "2", true, false),
                        new GraphView.Node("3:<C>", "<C> @3", "sink", "3", false, false)),
                List.of(new GraphView.Edge("1:A", "2:B", BigDecimal.TEN, 1L, null, "alert", null, true),
                        new GraphView.Edge("2:B", "3:<C>", BigDecimal.ONE, 2L, null, "pass_through", true, false)));

        String svg = CaseSummaryRenderer.svg(graph);

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(doc.getElementsByTagName("circle").getLength()).isEqualTo(3);
        assertThat(doc.getElementsByTagName("line").getLength()).isEqualTo(2);
        assertThat(svg).contains("&lt;C&gt; @3").contains("arrow-alert");
    }
}
