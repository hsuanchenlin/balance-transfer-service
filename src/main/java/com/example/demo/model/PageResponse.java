package com.example.demo.model;

import java.util.List;

/**
 * A single page of results plus the paging metadata a client needs to fetch the
 * next one. {@code totalElements} is the full unpaged count for the query.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements) {
}
