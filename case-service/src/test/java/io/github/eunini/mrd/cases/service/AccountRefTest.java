package io.github.eunini.mrd.cases.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.mrd.cases.report.PaymentCodes;
import org.junit.jupiter.api.Test;

class AccountRefTest {

    @Test
    void parsesBankAndAccount() {
        assertThat(AccountRef.parse("021174:800737690")).isEqualTo(new AccountRef("021174", "800737690"));
        assertThat(AccountRef.parse("plain")).isEqualTo(new AccountRef("UNKNOWN", "plain"));
        assertThat(AccountRef.parse(":x").bankId()).isEqualTo("UNKNOWN");
        assertThat(AccountRef.parse("012:80011F990").label()).isEqualTo("80011F990 @012");
    }

    @Test
    void mapsPaymentFormatsAndCurrencies() {
        assertThat(PaymentCodes.transmode("Wire")).isEqualTo("WIRE");
        assertThat(PaymentCodes.transmode("Credit Card")).isEqualTo("CARD");
        assertThat(PaymentCodes.transmode(null)).isEqualTo("OTHER");
        assertThat(PaymentCodes.fundsCode("Cash")).isEqualTo("CASH");
        assertThat(PaymentCodes.fundsCode("ACH")).isEqualTo("ACCOUNT");
        assertThat(PaymentCodes.currencyCode("Yuan")).isEqualTo("CNY");
        assertThat(PaymentCodes.currencyCode("GBP")).isEqualTo("GBP");
        assertThat(PaymentCodes.currencyCode("Doubloon")).isEqualTo("XXX");
    }
}
