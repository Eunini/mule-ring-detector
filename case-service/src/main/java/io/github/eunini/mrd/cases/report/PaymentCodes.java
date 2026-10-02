package io.github.eunini.mrd.cases.report;

import java.util.Locale;
import java.util.Map;

/**
 * Project-specific transmission-mode, funds-type and currency mappings used in STR output.
 * Demonstration values only, not an FIU lookup table.
 */
public final class PaymentCodes {

    private static final Map<String, String> TRANSMODE = Map.of(
            "ach", "ACH",
            "wire", "WIRE",
            "cheque", "CHEQUE",
            "credit card", "CARD",
            "cash", "CASH",
            "bitcoin", "VIRTUAL",
            "reinvestment", "INTERNAL");

    private static final Map<String, String> CURRENCY = Map.ofEntries(
            Map.entry("us dollar", "USD"), Map.entry("euro", "EUR"), Map.entry("yuan", "CNY"),
            Map.entry("yen", "JPY"), Map.entry("rupee", "INR"), Map.entry("swiss franc", "CHF"),
            Map.entry("shekel", "ILS"), Map.entry("canadian dollar", "CAD"),
            Map.entry("australian dollar", "AUD"), Map.entry("uk pound", "GBP"),
            Map.entry("mexican peso", "MXN"), Map.entry("brazil real", "BRL"), Map.entry("ruble", "RUB"),
            Map.entry("saudi riyal", "SAR"), Map.entry("bitcoin", "XBT"));

    private PaymentCodes() {
    }

    public static String transmode(String paymentFormat) {
        return paymentFormat == null ? "OTHER" : TRANSMODE.getOrDefault(key(paymentFormat), "OTHER");
    }

    public static String fundsCode(String paymentFormat) {
        String mode = transmode(paymentFormat);
        return switch (mode) {
            case "CASH" -> "CASH";
            case "VIRTUAL" -> "VIRTUAL";
            case "CHEQUE" -> "CHEQUE";
            default -> "ACCOUNT";
        };
    }

    public static String currencyCode(String currency) {
        if (currency == null || currency.isBlank()) {
            return "XXX";
        }
        String trimmed = currency.trim();
        if (trimmed.matches("[A-Z]{3}")) {
            return trimmed;
        }
        return CURRENCY.getOrDefault(key(trimmed), "XXX");
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
