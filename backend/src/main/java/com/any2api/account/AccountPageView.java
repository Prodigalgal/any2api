package com.any2api.account;

import java.util.List;
import org.springframework.data.domain.Page;

public record AccountPageView(
    List<AccountListItemView> items,
    long totalElements,
    int page,
    int size,
    int totalPages
) {
    static AccountPageView from(Page<AccountEntity> accounts) {
        return new AccountPageView(
            accounts.getContent().stream().map(AccountListItemView::from).toList(),
            accounts.getTotalElements(), accounts.getNumber(), accounts.getSize(),
            accounts.getTotalPages());
    }
}
