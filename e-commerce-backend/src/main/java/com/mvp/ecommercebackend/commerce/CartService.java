package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.catalog.repository.ProductVariantRepository;
import com.mvp.ecommercebackend.commerce.dto.AddCartItemRequest;
import com.mvp.ecommercebackend.commerce.dto.CartItemResponse;
import com.mvp.ecommercebackend.commerce.dto.CartResponse;
import com.mvp.ecommercebackend.commerce.dto.UpdateCartItemRequest;
import com.mvp.ecommercebackend.commerce.entity.Cart;
import com.mvp.ecommercebackend.commerce.entity.CartItem;
import com.mvp.ecommercebackend.commerce.repository.CartItemRepository;
import com.mvp.ecommercebackend.commerce.repository.CartRepository;
import com.mvp.ecommercebackend.common.InsufficientStockException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public CartService(CartRepository cartRepository,
                       CartItemRepository cartItemRepository,
                       ProductVariantRepository variantRepository,
                       ProductRepository productRepository,
                       UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    /** An empty cart for a user who has never added anything, without writing a row to say so. */
    @Transactional(readOnly = true)
    public CartResponse getCart(UUID userId) {
        return cartRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(CartResponse::empty);
    }

    /**
     * Adds units to the cart, merging into the existing line for that variant rather than creating
     * a duplicate — which the unique constraint on (cart_id, product_variant_id) would reject
     * anyway.
     *
     * <p>Stock is checked against the resulting total, not the delta, so three separate additions
     * of one unit cannot walk past a stock of two.
     */
    @Transactional
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        Cart cart = cartRepository.findByUserId(userId).orElseGet(() -> createCart(userId));
        ProductVariant variant = variantRepository.findWithProductById(request.variantId())
                .orElseThrow(() -> new ResourceNotFoundException("Product Variant Not Found!"));

        CartItem existing = cart.getItems().stream()
                .filter(item -> item.getVariant().getId().equals(variant.getId()))
                .findFirst()
                .orElse(null);
        int resultingQuantity = (existing == null ? 0 : existing.getQuantity()) + request.quantity();
        requireStock(variant, resultingQuantity);

        if (existing == null) {
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setVariant(variant);
            item.setQuantity(request.quantity());
            cart.getItems().add(item);
        } else {
            existing.setQuantity(resultingQuantity);
        }
        return toResponse(cartRepository.saveAndFlush(cart));
    }

    /** Replaces a line's quantity. A quantity of zero is rejected by validation; use DELETE. */
    @Transactional
    public CartResponse updateItem(UUID userId, UUID itemId, UpdateCartItemRequest request) {
        CartItem item = requireOwnedItem(userId, itemId);
        requireStock(item.getVariant(), request.quantity());
        item.setQuantity(request.quantity());
        cartItemRepository.saveAndFlush(item);
        return getCart(userId);
    }

    @Transactional
    public CartResponse removeItem(UUID userId, UUID itemId) {
        CartItem item = requireOwnedItem(userId, itemId);
        // Removed through the parent so orphanRemoval fires and the in-memory cart stays consistent
        // with the row that was just deleted.
        Cart cart = item.getCart();
        cart.getItems().remove(item);
        return toResponse(cartRepository.saveAndFlush(cart));
    }

    @Transactional
    public void clear(UUID userId) {
        cartRepository.findByUserId(userId).ifPresent(cart -> {
            cart.getItems().clear();
            cartRepository.saveAndFlush(cart);
        });
    }

    private Cart createCart(UUID userId) {
        Cart cart = new Cart();
        // getReferenceById, not findById: the caller is authenticated, so the row exists, and this
        // only needs the foreign key.
        cart.setUser(userRepository.getReferenceById(userId));
        return cartRepository.saveAndFlush(cart);
    }

    private CartItem requireOwnedItem(UUID userId, UUID itemId) {
        return cartItemRepository.findByIdAndCartUserId(itemId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart Item Not Found!"));
    }

    private static void requireStock(ProductVariant variant, int wanted) {
        int available = variant.getStockQuantity() == null ? 0 : variant.getStockQuantity();
        if (wanted > available) {
            // The variant id, not the product name: the message is for a developer, and naming the
            // product adds nothing the client did not already send.
            throw new InsufficientStockException(
                    "Only " + available + " left for variant " + variant.getId());
        }
    }

    private CartResponse toResponse(Cart cart) {
        // Oldest line first, so a cart does not reshuffle itself between requests. The id is a
        // tiebreaker because two lines added in the same instant would otherwise order arbitrarily.
        List<CartItem> items = cart.getItems().stream()
                .sorted(Comparator.comparing(CartItem::getCreatedAt).thenComparing(CartItem::getId))
                .toList();
        if (items.isEmpty()) {
            return CartResponse.empty();
        }

        Map<UUID, String> thumbnails = thumbnailsFor(items);
        List<CartItemResponse> lines = items.stream()
                .map(item -> toLine(item, thumbnails))
                .toList();
        BigDecimal subtotal = lines.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        int totalQuantity = lines.stream().mapToInt(CartItemResponse::quantity).sum();
        return new CartResponse(lines, subtotal, totalQuantity);
    }

    /** One batched query for the whole cart rather than one per line. */
    private Map<UUID, String> thumbnailsFor(List<CartItem> items) {
        List<UUID> productIds = items.stream()
                .map(item -> item.getVariant().getProduct().getId())
                .distinct()
                .toList();
        return productRepository.findPrimaryThumbnails(productIds).stream()
                .collect(Collectors.toMap(ProductRepository.ProductThumbnail::getProductId,
                        ProductRepository.ProductThumbnail::getUrl,
                        (first, duplicate) -> first));
    }

    private static CartItemResponse toLine(CartItem item, Map<UUID, String> thumbnails) {
        ProductVariant variant = item.getVariant();
        Product product = variant.getProduct();
        BigDecimal unitPrice = product.getPrice();
        return new CartItemResponse(
                item.getId(),
                product.getId(),
                product.getName(),
                thumbnails.get(product.getId()),
                variant.getId(),
                variant.getColor(),
                variant.getSize(),
                unitPrice,
                item.getQuantity(),
                unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())).setScale(2),
                variant.getStockQuantity() == null ? 0 : variant.getStockQuantity());
    }
}
