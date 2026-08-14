package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.admin.repository.AdminEventRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
