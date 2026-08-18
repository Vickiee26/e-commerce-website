package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminEventResponse;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The audit trail, read-only.
 *
 * <p>There is no POST, PATCH, or DELETE here by design. Rows are written by the services performing
 * the mutations, inside the same transaction, so the trail and the change it describes cannot
 * disagree.
 */
@RestController
@RequestMapping("/api/admin/events")
@Tag(name = "Admin Audit")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminEventController {

    private final AdminEventService adminEventService;

    public AdminEventController(AdminEventService adminEventService) {
        this.adminEventService = adminEventService;
    }

    @GetMapping
    @Operation(summary = "Read the admin audit trail",
            description = "Newest first. Filters by target, acting administrator, and action. "
                    + "Events are never written or deleted through the API.")
    public PageResponse<AdminEventResponse> listEvents(
            @RequestParam(required = false) AdminTargetType targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) AdminEventType action,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size) {
        return adminEventService.list(targetType, targetId, actorUserId, action, page, size);
    }
}
