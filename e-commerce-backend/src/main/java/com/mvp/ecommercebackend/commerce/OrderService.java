package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.repository.ProductVariantRepository;
import com.mvp.ecommercebackend.commerce.dto.OrderItemResponse;
import com.mvp.ecommercebackend.commerce.dto.OrderResponse;
import com.mvp.ecommercebackend.commerce.dto.OrderSummaryResponse;
import com.mvp.ecommercebackend.commerce.dto.PayOrderRequest;
import com.mvp.ecommercebackend.commerce.dto.PlaceOrderRequest;
import com.mvp.ecommercebackend.commerce.dto.ShippingAddressResponse;
import com.mvp.ecommercebackend.commerce.entity.Order;
import com.mvp.ecommercebackend.commerce.entity.OrderItem;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import com.mvp.ecommercebackend.commerce.entity.ShippingAddressSnapshot;
import com.mvp.ecommercebackend.commerce.payment.PaymentGateway;
import com.mvp.ecommercebackend.commerce.payment.PaymentResult;
import com.mvp.ecommercebackend.commerce.repository.CartItemRepository;
import com.mvp.ecommercebackend.commerce.repository.OrderRepository;
import com.mvp.ecommercebackend.common.InsufficientStockException;
import com.mvp.ecommercebackend.common.InvalidOrderStateException;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.common.PaymentDeclinedException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import com.mvp.ecommercebackend.user.entity.Address;
import com.mvp.ecommercebackend.user.repository.AddressRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Checkout and the order lifecycle.
 *
 * <p>Placement is one transaction: lock the variants being bought, verify and decrement their stock,
 * write the order, empty the cart. Anything that fails rolls all of it back, so there is no state in
 * which stock has been taken but no order exists to account for it.
 */
@Service
public class OrderService {

    /**
     * Single-currency for now. A constant rather than configuration because prices in the catalogue
     * carry no currency of their own, so changing this alone would silently reinterpret them.
     */
    private static final String CURRENCY = "USD";

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final OrderNumberGenerator orderNumbers;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository variantRepository,
                        AddressRepository addressRepository,
                        UserRepository userRepository,
                        OrderNumberGenerator orderNumbers,
                        PaymentGateway paymentGateway,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
        this.orderNumbers = orderNumbers;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    /**
     * Turns the caller's cart into an order and takes the stock.
     *
     * <p>Stock is decremented here rather than at payment. The alternative — reserving at payment —
     * means two customers can both hold the last unit until one of them pays, and the loser finds
     * out after entering their card details. Cancelling puts the stock back.
     */
    @Transactional
    public OrderResponse placeOrder(UUID userId, PlaceOrderRequest request) {
        List<CartItemRepository.CheckoutLine> lines = cartItemRepository.findCheckoutLines(userId);
        if (lines.isEmpty()) {
            throw new InvalidOrderStateException("Cart is empty");
        }
        Address address = addressRepository
                .findByIdAndUserId(request.shippingAddressId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address Not Found!"));

        Map<UUID, ProductVariant> locked = lockVariants(
                lines.stream().map(CartItemRepository.CheckoutLine::getVariantId).toList());

        Order order = new Order();
        // getReferenceById: the caller is authenticated, so the row exists and only the FK is needed.
        order.setUser(userRepository.getReferenceById(userId));
        order.setOrderNumber(orderNumbers.next());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setCurrency(CURRENCY);
        order.setShippingAddress(snapshotOf(address));
        order.setPlacedAt(clock.instant());

        BigDecimal subtotal = BigDecimal.ZERO;
        int lineNumber = 0;
        for (CartItemRepository.CheckoutLine line : lines) {
            lineNumber++;
            ProductVariant variant = locked.get(line.getVariantId());
            if (variant == null) {
                // Not reachable through the API: cart_items.product_variant_id is a not-null foreign
                // key with ON DELETE CASCADE, so a deleted variant takes its cart lines with it.
                throw new ResourceNotFoundException("Product Variant Not Found!");
            }
            takeStock(variant, line.getQuantity(), line.getProductName());

            OrderItem item = new OrderItem();
            item.setProductId(line.getProductId());
            item.setVariantId(line.getVariantId());
            item.setProductName(line.getProductName());
            item.setVariantColor(line.getColor());
            item.setVariantSize(line.getSize());
            // The price is re-read from the catalogue here, never carried on the cart line, so a cart
            // left open for a week cannot lock in last week's price.
            item.setUnitPrice(line.getUnitPrice());
            item.setQuantity(line.getQuantity());
            item.setLineTotal(line.getUnitPrice()
                    .multiply(BigDecimal.valueOf(line.getQuantity())).setScale(2));
            item.setLineNumber(lineNumber);
            order.addItem(item);

            subtotal = subtotal.add(item.getLineTotal());
        }
        order.setSubtotalAmount(subtotal.setScale(2));
        // Shipping and tax are out of scope, so the total is the subtotal.
        order.setTotalAmount(subtotal.setScale(2));

        Order placed = orderRepository.saveAndFlush(order);
        cartItemRepository.deleteByCartUserId(userId);
        return toResponse(placed);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listOrders(UUID userId, int page, int size) {
        // Newest first: an order history is read from the top.
        Page<Order> orders = orderRepository.findByUserId(userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt")));
        List<OrderSummaryResponse> rows = orders.getContent().stream()
                .map(order -> new OrderSummaryResponse(order.getId(), order.getOrderNumber(),
                        order.getStatus(), order.getCurrency(), order.getTotalAmount(),
                        order.getPlacedAt()))
                .toList();
        return PageResponse.of(orders, rows);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID userId, UUID orderId) {
        return toResponse(requireOwnedOrder(userId, orderId));
    }

    /** Charges the order. A decline leaves it payable, so the customer can try another instrument. */
    @Transactional
    public OrderResponse pay(UUID userId, UUID orderId, PayOrderRequest request) {
        Order order = requireOwnedOrder(userId, orderId);
        requirePendingPayment(order, "paid");

        PaymentResult result = paymentGateway.charge(order.getOrderNumber(), order.getTotalAmount(),
                order.getCurrency(), request.paymentMethodToken());
        if (!result.approved()) {
            // Thrown, not returned: the transaction must roll back so nothing about the order moves.
            throw new PaymentDeclinedException(result.declineReason());
        }

        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(clock.instant());
        order.setPaymentReference(result.transactionId());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /**
     * Cancels an unpaid order and returns its stock to the catalogue.
     *
     * <p>A paid order is refused. Reversing a completed sale needs a record of who authorised the
     * refund and how much came back, which is a slice of its own.
     */
    @Transactional
    public OrderResponse cancel(UUID userId, UUID orderId) {
        Order order = requireOwnedOrder(userId, orderId);
        requirePendingPayment(order, "cancelled");

        List<UUID> variantIds = order.getItems().stream()
                .map(OrderItem::getVariantId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, ProductVariant> locked = lockVariants(variantIds);
        for (OrderItem item : order.getItems()) {
            ProductVariant variant = locked.get(item.getVariantId());
            // Null when the variant has since been withdrawn: there is nothing left to restock, and
            // the order still records what was sold.
            if (variant != null) {
                variant.setStockQuantity(variant.getStockQuantity() + item.getQuantity());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(clock.instant());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    private Order requireOwnedOrder(UUID userId, UUID orderId) {
        return orderRepository.findWithItemsByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order Not Found!"));
    }

    private static void requirePendingPayment(Order order, String attemptedAction) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateException("Order " + order.getOrderNumber() + " is "
                    + order.getStatus() + " and cannot be " + attemptedAction);
        }
    }

    /**
     * Takes a pessimistic write lock on every variant involved, in a fixed order.
     *
     * <p>The lock is what makes the stock check safe: without it two orders for the last unit both
     * read a stock of one and both succeed. The repository query orders by id so that two
     * concurrent orders sharing variants always take their locks in the same sequence and cannot
     * deadlock.
     */
    private Map<UUID, ProductVariant> lockVariants(List<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return variantRepository.lockAllByIdIn(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, variant -> variant));
    }

    private static void takeStock(ProductVariant variant, int quantity, String productName) {
        int available = variant.getStockQuantity() == null ? 0 : variant.getStockQuantity();
        if (quantity > available) {
            // The product name, unlike in the cart, because by now the customer is looking at a
            // checkout page listing several items and needs to know which one to change.
            throw new InsufficientStockException(
                    "Only " + available + " left of " + productName);
        }
        variant.setStockQuantity(available - quantity);
    }

    private static ShippingAddressSnapshot snapshotOf(Address address) {
        ShippingAddressSnapshot snapshot = new ShippingAddressSnapshot();
        snapshot.setRecipientName(address.getRecipientName());
        snapshot.setPhone(address.getPhone());
        snapshot.setLine1(address.getLine1());
        snapshot.setLine2(address.getLine2());
        snapshot.setCity(address.getCity());
        snapshot.setState(address.getState());
        snapshot.setPostalCode(address.getPostalCode());
        snapshot.setCountry(address.getCountry());
        return snapshot;
    }

    private static OrderResponse toResponse(Order order) {
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
        return new OrderResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                order.getCurrency(), order.getSubtotalAmount(), order.getTotalAmount(),
                new ShippingAddressResponse(address.getRecipientName(), address.getPhone(),
                        address.getLine1(), address.getLine2(), address.getCity(),
                        address.getState(), address.getPostalCode(), address.getCountry()),
                lines, order.getPlacedAt(), order.getPaidAt(), order.getCancelledAt());
    }
}
