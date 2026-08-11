package com.mvp.ecommercebackend.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A page of results in a shape this API controls.
 *
 * <p>Spring Data's {@code Page} is deliberately not returned from a controller: its JSON is an
 * implementation detail that has changed between versions, and Boot logs a warning when you
 * serialise it. This record pins the contract instead.
 *
 * @param content       the rows on this page, already mapped to DTOs
 * @param page          zero-based index of this page
 * @param size          the requested page size, not the number of rows returned
 * @param totalElements rows matching the query across every page
 * @param totalPages    zero when nothing matches
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    /**
     * Copies the pagination metadata off {@code page} while taking the rows from {@code content},
     * so the caller can map entities to DTOs without a second query.
     */
    public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
