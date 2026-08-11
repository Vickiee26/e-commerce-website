package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEvent;
import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.repository.AuthEventRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Writes the auth audit trail. {@code userId} may be null when an attempt names no known user. */
@Service
public class AuthEventService {

    private final AuthEventRepository authEventRepository;
    private final UserRepository userRepository;

    public AuthEventService(AuthEventRepository authEventRepository, UserRepository userRepository) {
        this.authEventRepository = authEventRepository;
        this.userRepository = userRepository;
    }

    /** Joins the caller's transaction. Use for outcomes that commit, such as a successful login. */
    @Transactional
    public void record(UUID userId, AuthEventType type, RequestContext context) {
        authEventRepository.save(build(userId, type, context));
    }

    /**
     * Commits in its own transaction, so the record survives the caller rolling back — which is
     * exactly what happens when the caller throws to produce a 401.
     *
     * <p>Only safe for users whose row is already committed. A brand-new user is still invisible to
     * this transaction and would fail the foreign key, so registration must use {@link #record}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAndCommit(UUID userId, AuthEventType type, RequestContext context) {
        authEventRepository.save(build(userId, type, context));
    }

    private AuthEvent build(UUID userId, AuthEventType type, RequestContext context) {
        AuthEvent event = new AuthEvent();
        // A reference, not a fetch: only the foreign key is needed.
        event.setUser(userId == null ? null : userRepository.getReferenceById(userId));
        event.setEventType(type);
        event.setIpAddress(context == null ? null : context.ipAddress());
        event.setUserAgent(context == null ? null : context.userAgent());
        return event;
    }
}
