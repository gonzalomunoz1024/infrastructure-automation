package com.iaf.console.drift.drift.adapters.web;

import java.util.List;

import com.iaf.console.kit.paging.Page;

/** A page as the contract answers it: {@code items} and {@code next}, which is null on the last page. */
public record Listing<T>(List<T> items, String next) {

    public static <T> Listing<T> of(Page<T> page) {
        return new Listing<>(page.items(), page.next());
    }
}
