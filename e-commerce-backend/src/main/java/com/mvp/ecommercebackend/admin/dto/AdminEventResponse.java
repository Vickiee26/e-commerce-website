package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit row.
 *
 * @param actorUserId null when the administrator has since been deleted; the column is
 *                    {@code ON DELETE SET NULL} so that removing a person does not erase the record
 *                    that they acted
 * @param actorEmail  resolved alongside the id, because "who did this" is the question every reader
 *                    of an audit trail asks first; null whenever {@code actorUserId} is
 * @param detail      free text written by the acting service, capped at 1000 characters
 */
public record AdminEventResponse(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        AdminEventType action,
        AdminTargetType targetType,
        UUID targetId,
        String detail,
        Instant createdAt) {
}
