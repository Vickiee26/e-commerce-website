package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.commerce.dto.OrderResponse;
import com.mvp.ecommercebackend.commerce.dto.OrderSummaryResponse;
import com.mvp.ecommercebackend.commerce.dto.PayOrderRequest;
import com.mvp.ecommercebackend.commerce.dto.PlaceOrderRequest;
import com.mvp.ecommercebackend.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Checkout and order history for the caller.
 *
 * <p>Browsing the catalogue is anonymous, but this is where login becomes mandatory: an order needs
 * an owner to ship to, to charge, and to show a history to.
 */
@RestController
@RequestMapping("/api/me/orders")
@Tag(name = "Orders")
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Place an order from the cart",
            description = "Empties the cart and takes the stock. Answers 409 when the cart is empty "
                    + "or an item is no longer in stock, and 404 for an address that is not the "
                    + "caller's.")
    public ResponseEntity<OrderResponse> placeOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PlaceOrderRequest request) {
        OrderResponse order = orderService.placeOrder(principal.id(), request);
        return ResponseEntity.created(URI.create("/api/me/orders/" + order.id())).body(order);
    }

    @GetMapping
    @Operation(summary = "The caller's orders, newest first")
    public PageResponse<OrderSummaryResponse> listOrders(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size) {
        return orderService.listOrders(principal.id(), page, size);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "One order in full",
            description = "Another user's order answers 404, not 403.")
    public OrderResponse getOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID orderId) {
        return orderService.getOrder(principal.id(), orderId);
    }

    @PostMapping("/{orderId}/pay")
    @Operation(summary = "Pay for an order",
            description = "Answers 402 when the charge is declined, leaving the order payable, and "
                    + "409 when the order is not awaiting payment.")
    public OrderResponse pay(@AuthenticationPrincipal AuthenticatedUser principal,
                             @PathVariable UUID orderId,
                             @Valid @RequestBody PayOrderRequest request) {
        return orderService.pay(principal.id(), orderId, request);
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an unpaid order",
            description = "Returns the stock to the catalogue. A paid order answers 409: reversing a "
                    + "completed sale is a refund, which is not part of this API yet.")
    public OrderResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                @PathVariable UUID orderId) {
        return orderService.cancel(principal.id(), orderId);
    }
}
