package com.mvp.ecommercebackend.commerce.entity;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A placed order.
 *
 * <p>{@code @Table(name = "orders")} is not optional: {@code order} is a reserved word in SQL, so
 * the default table name would produce statements Postgres refuses to parse.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The only identifier a customer quotes. Random, never sequential — see OrderNumberGenerator. */
    @Column(name = "order_number", nullable = false, length = 20, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /** ISO 4217. Single-currency for now; the column exists so adding a second is a migration. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "subtotal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalAmount;

    /** Equal to the subtotal in this slice: shipping, tax and coupons are out of scope. */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** The gateway's identifier for the successful charge. Null until the order is paid. */
    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Embedded
    private ShippingAddressSnapshot shippingAddress;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** No orphanRemoval: a line is never removed from a placed order. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items = new ArrayList<>();

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }
}
