package io.github.eunini.mrd.cases.report;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project-specific mapping from engine detector names to report indicator codes.
 * These "MRD-*" codes are defined by this project for demonstration only; they are not
 * the indicator lookup values of any Financial Intelligence Unit.
 */
public final class DetectorIndicators {

    public record Indicator(String code, String description) {
    }

    public static final Indicator MODEL_SCORE = new Indicator("MRD-MODEL", "Model risk score above alert threshold");
    public static final Indicator OTHER = new Indicator("MRD-OTHER", "Other suspicious network pattern");

    private static final Map<String, Indicator> BY_DETECTOR = new LinkedHashMap<>();

    static {
        BY_DETECTOR.put("fan_out", new Indicator("MRD-FANOUT", "Rapid dispersal of funds to many accounts (fan-out)"));
        BY_DETECTOR.put("fan_in", new Indicator("MRD-FANIN", "Funds collected from many accounts (fan-in)"));
        BY_DETECTOR.put("cycle", new Indicator("MRD-CYCLE", "Circular movement of funds returning to origin"));
        BY_DETECTOR.put("scatter_gather", new Indicator("MRD-SCATGATH", "Scatter-gather layering through intermediaries"));
        BY_DETECTOR.put("gather_scatter", new Indicator("MRD-GATHSCAT", "Gather-scatter consolidation then dispersal"));
        BY_DETECTOR.put("pass_through", new Indicator("MRD-PASSTHRU", "Pass-through account: funds in and out within a short window"));
        BY_DETECTOR.put("velocity", new Indicator("MRD-VELOCITY", "Unusual transaction velocity"));
        BY_DETECTOR.put("ring", new Indicator("MRD-RING", "Member of a connected money-mule ring"));
    }

    private DetectorIndicators() {
    }

    public static Indicator forDetector(String detectorName) {
        return BY_DETECTOR.getOrDefault(detectorName, OTHER);
    }

    public static Map<String, Indicator> all() {
        return Map.copyOf(BY_DETECTOR);
    }
}
