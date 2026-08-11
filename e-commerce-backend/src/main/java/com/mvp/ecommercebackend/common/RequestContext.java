package com.mvp.ecommercebackend.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client details recorded alongside every auth event.
 *
 * <p>{@code X-Forwarded-For} is honoured because the application is expected to sit behind a proxy
 * in production. It is client-supplied and therefore spoofable: it is good enough for an audit
 * trail, and must not be treated as an authorisation input.
 */
public record RequestContext(String ipAddress, String userAgent) {

    private static final int MAX_USER_AGENT_LENGTH = 255;
    private static final int MAX_IP_LENGTH = 45;

    public static RequestContext from(HttpServletRequest request) {
        return new RequestContext(
                truncate(resolveIpAddress(request), MAX_IP_LENGTH),
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH));
    }

    private static String resolveIpAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
