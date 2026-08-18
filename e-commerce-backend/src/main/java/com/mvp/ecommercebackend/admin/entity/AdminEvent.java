package com.mvp.ecommercebackend.admin.entity;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One administrative action.
 *
 * <p>{@code actor} is nullable and its foreign key is {@code ON DELETE SET NULL}: removing an
 * administrator must not erase the record that the action took place.
 *
 * <p>{@code targetId} is a plain {@code UUID}, not an association. The row it names can be a
 * category, a product, a variant, a resource or an order, and it may have been deleted since —
 * neither of which a foreign key can express.
 */
@Entity
@Table(name = "admin_events")
@Getter
@Setter
public class AdminEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private AdminEventType action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private AdminTargetType targetType;

    @Column(name = "target_id")
    private UUID targetId;

    /** Free text for context a client would otherwise have to diff two rows to recover. */
    @Column(name = "detail", length = 1000)
    private String detail;
}
