package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.commerce.dto.AddCartItemRequest;
import com.mvp.ecommercebackend.commerce.dto.CartResponse;
import com.mvp.ecommercebackend.commerce.dto.UpdateCartItemRequest;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The caller's own cart, under {@code /api/me} for the same reason as the profile and addresses:
 * the owner comes from the token, never from the path.
 *
 * <p>Every mutation answers with the whole cart rather than the line that changed. A client
 * showing a cart needs the new subtotal after any edit, so returning less just guarantees a second
 * request.
 */
@RestController
@RequestMapping("/api/me/cart")
@Tag(name = "Cart")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@PreAuthorize("isAuthenticated()")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "The authenticated user's cart",
            description = "Returns an empty cart rather than 404 when nothing has been added yet.")
    public CartResponse getCart(@AuthenticationPrincipal AuthenticatedUser principal) {
        return cartService.getCart(principal.id());
    }

    @PostMapping("/items")
    @Operation(summary = "Add units to the cart",
            description = "Merges into the existing line when the variant is already in the cart. "
                    + "Answers 409 when the resulting quantity exceeds available stock.")
    public CartResponse addItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(principal.id(), request);
    }

    @PatchMapping("/items/{itemId}")
    @Operation(summary = "Set a line's quantity",
            description = "A line belonging to another user answers 404, not 403.")
    public CartResponse updateItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable UUID itemId,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItem(principal.id(), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove a line from the cart")
    public CartResponse removeItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable UUID itemId) {
        return cartService.removeItem(principal.id(), itemId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Empty the cart")
    public void clear(@AuthenticationPrincipal AuthenticatedUser principal) {
        cartService.clear(principal.id());
    }
}
