package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

@Embeddable
public class AlertAccount {

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 8)
    private AccountRole role;

    protected AlertAccount() {
    }

    public AlertAccount(String accountId, AccountRole role) {
        this.accountId = accountId;
        this.role = role;
    }

    public String getAccountId() {
        return accountId;
    }

    public AccountRole getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AlertAccount other
                && Objects.equals(accountId, other.accountId)
                && role == other.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, role);
    }
}
