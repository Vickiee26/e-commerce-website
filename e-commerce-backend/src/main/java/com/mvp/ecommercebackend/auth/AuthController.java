package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.dto.LoginRequest;
import com.mvp.ecommercebackend.auth.dto.LogoutRequest;
import com.mvp.ecommercebackend.auth.dto.RefreshRequest;
import com.mvp.ecommercebackend.auth.dto.RegisterRequest;
import com.mvp.ecommercebackend.auth.dto.TokenPairResponse;
import com.mvp.ecommercebackend.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
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

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the presented refresh token")
    public void logout(@Valid @RequestBody LogoutRequest request,
                        @AuthenticationPrincipal AuthenticatedUser principal,
                        HttpServletRequest httpRequest) {
        authService.logout(principal.id(), request.refreshToken(),
                RequestContext.from(httpRequest));
    }
}
