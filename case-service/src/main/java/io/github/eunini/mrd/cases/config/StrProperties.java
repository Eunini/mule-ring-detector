package io.github.eunini.mrd.cases.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Reporting-entity details printed in STR reports. */
@Validated
@ConfigurationProperties("mrd.str")
public record StrProperties(
        @NotBlank String rentityId,
        @NotBlank String institutionName,
        String currencyCodeLocal,
        String transactionLocation,
        String action,
        Location location) {

    public StrProperties {
        currencyCodeLocal = blankToDefault(currencyCodeLocal, "USD");
        transactionLocation = blankToDefault(transactionLocation, "Electronic channel");
        action = blankToDefault(action,
                "Accounts placed under enhanced monitoring; outgoing transfers held for review pending FIU guidance.");
        location = location == null ? new Location(null, null, null, null) : location;
    }

    public record Location(String addressType, String address, String city, String countryCode) {
        public Location {
            addressType = blankToDefault(addressType, "B");
            address = blankToDefault(address, "Not provided");
            city = blankToDefault(city, "Not provided");
            countryCode = blankToDefault(countryCode, "XX");
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
