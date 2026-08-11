package com.mvp.ecommercebackend.user;

import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.user.dto.UpdateProfileRequest;
import com.mvp.ecommercebackend.user.dto.UserProfileResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own profile. There is no user id in any path: the subject always comes from the
 * verified token, so one customer cannot address another's record at all.
 */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Profile")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@PreAuthorize("isAuthenticated()")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Return the authenticated user's profile")
    public UserProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.getProfile(principal.id());
    }

    @PatchMapping
    @Operation(summary = "Update the authenticated user's name or phone",
            description = "Omitted or null fields are left unchanged. An empty phone clears it. "
                    + "Email, roles, status and password cannot be changed here.")
    public UserProfileResponse updateProfile(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(principal.id(), request);
    }
}
