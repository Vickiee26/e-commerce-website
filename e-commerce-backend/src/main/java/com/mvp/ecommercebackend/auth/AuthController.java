package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.dto.LoginRequest;
import com.mvp.ecommercebackend.auth.dto.LogoutRequest;
import com.mvp.ecommercebackend.auth.dto.PasswordResetConfirmRequest;
import com.mvp.ecommercebackend.auth.dto.PasswordResetRequest;
import com.mvp.ecommercebackend.auth.dto.RefreshRequest;
import com.mvp.ecommercebackend.auth.dto.RegisterRequest;
import com.mvp.ecommercebackend.auth.dto.TokenPairResponse;
import com.mvp.ecommercebackend.common.RequestContext;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer account and return a token pair")
    public TokenPairResponse register(@Valid @RequestBody RegisterRequest request,
                                       HttpServletRequest httpRequest) {
        return authService.register(request, RequestContext.from(httpRequest));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and return a token pair")
    public TokenPairResponse login(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest httpRequest) {
        return authService.login(request, RequestContext.from(httpRequest));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh token and return a new token pair")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request,
                                      HttpServletRequest httpRequest) {
        // Public on purpose: the whole point is to recover after the access token has expired.
        return authService.refresh(request.refreshToken(), RequestContext.from(httpRequest));
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a password reset token; always accepted",
            description = "Returns 202 whether or not the email is registered, so the endpoint "
                    + "cannot be used to discover which addresses have accounts.")
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                      HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request, RequestContext.from(httpRequest));
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Consume a reset token, set a new password, and end every session")
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request,
                                      HttpServletRequest httpRequest) {
        passwordResetService.confirmReset(request, RequestContext.from(httpRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the presented refresh token")
    // The one /auth endpoint that needs a token: revoking a session requires knowing whose it is.
    @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
    public void logout(@Valid @RequestBody LogoutRequest request,
                        @AuthenticationPrincipal AuthenticatedUser principal,
                        HttpServletRequest httpRequest) {
        authService.logout(principal.id(), request.refreshToken(),
                RequestContext.from(httpRequest));
    }
}
