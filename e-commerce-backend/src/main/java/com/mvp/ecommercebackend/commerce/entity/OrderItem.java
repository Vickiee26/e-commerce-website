package com.mvp.ecommercebackend.commerce.entity;

import com.mvp.ecommercebackend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order, with everything needed to render it years later.
 *
 * <p>{@code productId} and {@code variantId} are plain UUID columns rather than associations. Two
 * reasons: the catalogue row may be gone (the foreign keys are {@code ON DELETE SET NULL}), and
 * mapping them as associations would pull {@code ProductVariant} into the persistence context when
 * an order is read — which would make the stock figures the checkout path locks and updates
 * unreliable, since Hibernate keeps the already-loaded copy and discards the fresh row.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "product_variant_id")
    private UUID variantId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "variant_color", length = 60)
    private String variantColor;

    @Column(name = "variant_size", length = 30)
    private String variantSize;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** One-based position in the cart at checkout, so the lines always read back in that order. */
    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;
}
