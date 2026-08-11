package com.mvp.ecommercebackend.auth.entity;

public enum AuthEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    TOKEN_REFRESH,
    TOKEN_REUSE_DETECTED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED
}
