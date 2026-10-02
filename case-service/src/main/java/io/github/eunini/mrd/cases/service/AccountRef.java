package io.github.eunini.mrd.cases.service;

/** Account identifiers arrive as "bankId:accountNumber"; anything else is treated as an account number. */
public record AccountRef(String bankId, String account) {

    public static AccountRef parse(String id) {
        int idx = id.indexOf(':');
        if (idx <= 0 || idx == id.length() - 1) {
            return new AccountRef("UNKNOWN", id);
        }
        return new AccountRef(id.substring(0, idx), id.substring(idx + 1));
    }

    public String label() {
        return account + " @" + bankId;
    }
}
