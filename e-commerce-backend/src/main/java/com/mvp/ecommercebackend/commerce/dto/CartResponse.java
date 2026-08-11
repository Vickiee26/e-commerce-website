package com.mvp.ecommercebackend.commerce.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The caller's whole cart.
 *
 * <p>No cart id: there is exactly one cart per user and lines are addressed by their own ids, so
 * exposing it would only invite a client to send it back. Its absence also means {@code GET
 * /api/me/cart} never has to create a row to have something to return.
 *
 * @param subtotal      sum of the line totals; shipping and tax are not part of this slice
 * @param totalQuantity units across all lines, which is what a header badge shows
 */
public record CartResponse(List<CartItemResponse> items, BigDecimal subtotal, int totalQuantity) {

    public static CartResponse empty() {
        return new CartResponse(List.of(), BigDecimal.ZERO.setScale(2), 0);
    }
}
