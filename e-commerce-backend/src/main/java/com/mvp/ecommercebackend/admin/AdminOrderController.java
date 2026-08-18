package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminOrderResponse;
import com.mvp.ecommercebackend.admin.dto.AdminOrderSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.ShipOrderRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Order administration and fulfilment. Security comes from the {@code /api/admin/**} path rule. */
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "Admin Orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    /**
     * @param status an {@code OrderStatus} name; an unknown value is a 400, because Spring cannot
     *               bind it to the enum
     * @param from   inclusive, ISO-8601 instant, e.g. 2026-08-01T00:00:00Z
     * @param to     exclusive, so consecutive windows do not double-count a boundary order
     */
    @GetMapping
    @Operation(summary = "List orders for administration",
            description = "Filters by status, customer, order number, and placement window. "
                    + "from is inclusive and to is exclusive.")
    public PageResponse<AdminOrderSummaryResponse> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @Size(max = 20) String orderNumber,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size,
            @RequestParam(defaultValue = "placedAt")
            @Pattern(regexp = "placedAt|totalAmount|orderNumber|status",
                    message = "must be one of placedAt, totalAmount, orderNumber, status")
            String sort,
            @RequestParam(defaultValue = "desc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {
        return adminOrderService.list(status, userId, orderNumber, from, to, page, size,
                sort, direction);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One order, whoever placed it",
            description = "Includes the customer's identity and the fulfilment fields, neither of "
                    + "which appears on the customer-facing shape.")
    public AdminOrderResponse getOrder(@PathVariable UUID id) {
        return adminOrderService.getOrder(id);
    }

    @PostMapping("/{id}/ship")
    @Operation(summary = "Mark a paid order shipped",
            description = "PAID only; anything else answers 409. trackingReference is required.")
    public AdminOrderResponse shipOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody ShipOrderRequest request) {
        return adminOrderService.ship(principal.id(), id, request);
    }

    @PostMapping("/{id}/deliver")
    @Operation(summary = "Mark a shipped order delivered",
            description = "SHIPPED only; anything else answers 409. DELIVERED is terminal.")
    public AdminOrderResponse deliverOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID id) {
        return adminOrderService.deliver(principal.id(), id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an unpaid order and return its stock",
            description = "PENDING_PAYMENT only. Cancelling a paid order would be a refund, which "
                    + "this API does not implement, so it answers 409.")
    public AdminOrderResponse cancelOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @PathVariable UUID id) {
        return adminOrderService.cancel(principal.id(), id);
    }
}
