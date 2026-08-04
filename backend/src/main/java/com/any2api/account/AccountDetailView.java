package com.any2api.account;

import com.any2api.credential.CredentialSummary;

public record AccountDetailView(
    AccountView account,
    CredentialSummary credential
) {
}
