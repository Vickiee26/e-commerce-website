package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** The optional filters behind {@code GET /api/admin/events}. */
final class AdminEventSpecifications {

    private AdminEventSpecifications() {
    }

    static Specification<AdminEvent> hasTargetType(AdminTargetType targetType) {
        return (root, query, builder) -> builder.equal(root.get("targetType"), targetType);
    }

    static Specification<AdminEvent> hasTargetId(UUID targetId) {
        return (root, query, builder) -> builder.equal(root.get("targetId"), targetId);
    }

    /**
     * Filters on the foreign key rather than joining {@code users}: the actor may since have been
     * deleted, and an inner join would hide exactly the rows an auditor most wants to see.
     */
    static Specification<AdminEvent> hasActor(UUID actorUserId) {
        return (root, query, builder) -> builder.equal(root.get("actor").get("id"), actorUserId);
    }

    static Specification<AdminEvent> hasAction(AdminEventType action) {
        return (root, query, builder) -> builder.equal(root.get("action"), action);
    }
}
