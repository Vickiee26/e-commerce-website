package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminEventResponse;
import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.admin.repository.AdminEventRepository;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Writes the administrative audit trail. */
@Service
public class AdminEventService {

    /** Matches admin_events.detail. */
    private static final int DETAIL_LIMIT = 1000;

    private final AdminEventRepository adminEventRepository;
    private final UserRepository userRepository;

    public AdminEventService(AdminEventRepository adminEventRepository,
                             UserRepository userRepository) {
        this.adminEventRepository = adminEventRepository;
        this.userRepository = userRepository;
    }

    /**
     * Joins the caller's transaction, deliberately. A mutation and the record of it must commit
     * together: a trail that survives a rolled-back change would claim something happened that did
     * not, which is worse than no trail at all.
     *
     * @param actorUserId the authenticated administrator, taken from the principal and never from a
     *                    request body
     * @param detail      optional context; truncated rather than allowed to fail the insert
     */
    @Transactional
    public void record(UUID actorUserId, AdminEventType action, AdminTargetType targetType,
                       UUID targetId, String detail) {
        AdminEvent event = new AdminEvent();
        // A reference, not a fetch: only the foreign key is needed.
        event.setActor(actorUserId == null ? null : userRepository.getReferenceById(actorUserId));
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setDetail(truncate(detail));
        adminEventRepository.save(event);
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_LIMIT) {
            return detail;
        }
        return detail.substring(0, DETAIL_LIMIT);
    }

    /**
     * The audit trail, newest first.
     *
     * <p>There is deliberately no sort parameter: a trail is read backwards from the most recent
     * action, and sorting by {@code detail} or {@code action} serves nobody while widening the
     * surface. Nor is there a write or delete path — an audit trail an administrator can edit records
     * nothing.
     *
     * <p>Sorts by {@code createdAt} descending then {@code id} descending. The {@code id} tiebreaker
     * ensures pagination stability: with only {@code createdAt}, Postgres may return rows in any order
     * among events with identical timestamps, causing the same event to appear on two pages or be
     * skipped as a caller walks the trail.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminEventResponse> list(AdminTargetType targetType, UUID targetId,
                                                 UUID actorUserId, AdminEventType action,
                                                 int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AdminEvent> events = adminEventRepository.findAll(
                filters(targetType, targetId, actorUserId, action), pageRequest);

        List<AdminEventResponse> rows = events.getContent().stream()
                .map(AdminEventService::toResponse)
                .toList();
        return PageResponse.of(events, rows);
    }

    private static Specification<AdminEvent> filters(AdminTargetType targetType, UUID targetId,
                                                     UUID actorUserId, AdminEventType action) {
        List<Specification<AdminEvent>> filters = new ArrayList<>();
        if (targetType != null) {
            filters.add(AdminEventSpecifications.hasTargetType(targetType));
        }
        if (targetId != null) {
            filters.add(AdminEventSpecifications.hasTargetId(targetId));
        }
        if (actorUserId != null) {
            filters.add(AdminEventSpecifications.hasActor(actorUserId));
        }
        if (action != null) {
            filters.add(AdminEventSpecifications.hasAction(action));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

    private static AdminEventResponse toResponse(AdminEvent event) {
        User actor = event.getActor();
        return new AdminEventResponse(event.getId(),
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getEmail(),
                event.getAction(), event.getTargetType(), event.getTargetId(), event.getDetail(),
                event.getCreatedAt());
    }
}
