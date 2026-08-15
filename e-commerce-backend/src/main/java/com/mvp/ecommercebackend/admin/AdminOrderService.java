package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminOrderResponse;
import com.mvp.ecommercebackend.admin.dto.AdminOrderSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.ShipOrderRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.commerce.OrderService;
import com.mvp.ecommercebackend.commerce.dto.OrderItemResponse;
import com.mvp.ecommercebackend.commerce.dto.ShippingAddressResponse;
import com.mvp.ecommercebackend.commerce.entity.Order;
import com.mvp.ecommercebackend.commerce.entity.OrderItem;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import com.mvp.ecommercebackend.commerce.entity.ShippingAddressSnapshot;
import com.mvp.ecommercebackend.commerce.repository.OrderRepository;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Order administration: read any customer's order, and move it through fulfilment.
 *
 * <p>The state machine is {@code PENDING_PAYMENT → PAID → SHIPPED → DELIVERED}, with
 * {@code PENDING_PAYMENT → CANCELLED}. Every move is forward: {@code DELIVERED} and
 * {@code CANCELLED} are terminal, and neither a shipment nor a payment can be reversed, because
 * undoing either means returning money and refunds do not exist yet.
 */
@Service
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final AdminEventService adminEventService;
    private final Clock clock;

    public AdminOrderService(OrderRepository orderRepository,
                             OrderService orderService,
                             AdminEventService adminEventService,
                             Clock clock) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.adminEventService = adminEventService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderSummaryResponse> list(OrderStatus status, UUID userId,
                                                        String orderNumber, Instant from, Instant to,
                                                        int page, int size, String sortProperty,
                                                        String direction) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortProperty));
        Page<Order> orders = orderRepository.findAll(
                filters(status, userId, orderNumber, from, to), pageRequest);

        // Inside the transaction, so the lazy user association resolves. One extra query per row, on
        // a page of at most 100 admin rows; a projection would be premature here.
        List<AdminOrderSummaryResponse> rows = orders.getContent().stream()
                .map(AdminOrderService::toSummary)
                .toList();
        return PageResponse.of(orders, rows);
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse getOrder(UUID orderId) {
        return toResponse(requireOrder(orderId));
    }

    /** {@code PAID → SHIPPED}. */
    @Transactional
    public AdminOrderResponse ship(UUID actorUserId, UUID orderId, ShipOrderRequest request) {
        Order order = requireOrder(orderId);
        OrderService.requireStatus(order, OrderStatus.PAID, "shipped");

        order.setStatus(OrderStatus.SHIPPED);
        order.setShippedAt(clock.instant());
        order.setTrackingReference(request.trackingReference().trim());

        adminEventService.record(actorUserId, AdminEventType.ORDER_SHIPPED, AdminTargetType.ORDER,
                orderId, "order=" + order.getOrderNumber()
                        + " tracking=" + order.getTrackingReference());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /** {@code SHIPPED → DELIVERED}, and the end of the line. */
    @Transactional
    public AdminOrderResponse deliver(UUID actorUserId, UUID orderId) {
        Order order = requireOrder(orderId);
        OrderService.requireStatus(order, OrderStatus.SHIPPED, "delivered");

        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(clock.instant());

        adminEventService.record(actorUserId, AdminEventType.ORDER_DELIVERED, AdminTargetType.ORDER,
                orderId, "order=" + order.getOrderNumber());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /**
     * Cancels an unpaid order and returns its stock.
     *
     * <p>Delegates to {@code OrderService} so the restock and its lock ordering live in one place. A
     * paid order is refused there, not here — the reason is the same for a customer and an
     * administrator: reversing a settled sale is a refund.
     */
    @Transactional
    public AdminOrderResponse cancel(UUID actorUserId, UUID orderId) {
        orderService.cancelAsAdministrator(orderId);

        Order order = requireOrder(orderId);
        adminEventService.record(actorUserId, AdminEventType.ORDER_CANCELLED, AdminTargetType.ORDER,
                orderId, "order=" + order.getOrderNumber());
        return toResponse(order);
    }

    private Order requireOrder(UUID orderId) {
        return orderRepository.findWithItemsById(orderId).orElseThrow(
                () -> new ResourceNotFoundException("Order " + orderId + " was not found"));
    }

    private static Specification<Order> filters(OrderStatus status, UUID userId, String orderNumber,
                                                Instant from, Instant to) {
        List<Specification<Order>> filters = new ArrayList<>();
        if (status != null) {
            filters.add(AdminOrderSpecifications.hasStatus(status));
        }
        if (userId != null) {
            filters.add(AdminOrderSpecifications.placedBy(userId));
        }
        if (orderNumber != null && !orderNumber.isBlank()) {
            filters.add(AdminOrderSpecifications.hasOrderNumber(orderNumber.trim()));
        }
        if (from != null) {
            filters.add(AdminOrderSpecifications.placedAtOrAfter(from));
        }
        if (to != null) {
            filters.add(AdminOrderSpecifications.placedBefore(to));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

    private static AdminOrderSummaryResponse toSummary(Order order) {
        return new AdminOrderSummaryResponse(order.getId(), order.getOrderNumber(),
                order.getStatus(), order.getUser().getId(), order.getUser().getEmail(),
                order.getCurrency(), order.getTotalAmount(), order.getPlacedAt(), order.getPaidAt(),
                order.getShippedAt(), order.getDeliveredAt(), order.getCancelledAt(),
                order.getTrackingReference());
    }

    private static AdminOrderResponse toResponse(Order order) {
        List<OrderItem> items = new ArrayList<>(order.getItems());
        // A bag comes back in whatever order the database chose, so the stored position decides.
        items.sort(Comparator.comparingInt(OrderItem::getLineNumber));
        List<OrderItemResponse> lines = items.stream()
                .map(item -> new OrderItemResponse(item.getId(), item.getProductId(),
                        item.getVariantId(), item.getProductName(), item.getVariantColor(),
                        item.getVariantSize(), item.getUnitPrice(), item.getQuantity(),
                        item.getLineTotal()))
                .toList();

        ShippingAddressSnapshot address = order.getShippingAddress();
        return new AdminOrderResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                order.getUser().getId(), order.getUser().getEmail(), order.getCurrency(),
                order.getSubtotalAmount(), order.getTotalAmount(), order.getPaymentReference(),
                order.getTrackingReference(),
                new ShippingAddressResponse(address.getRecipientName(), address.getPhone(),
                        address.getLine1(), address.getLine2(), address.getCity(),
                        address.getState(), address.getPostalCode(), address.getCountry()),
                lines, order.getPlacedAt(), order.getPaidAt(), order.getShippedAt(),
                order.getDeliveredAt(), order.getCancelledAt());
    }
}
