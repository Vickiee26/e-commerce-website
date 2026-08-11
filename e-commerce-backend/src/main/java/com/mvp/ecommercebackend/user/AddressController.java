package com.mvp.ecommercebackend.user;

import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.user.dto.AddressResponse;
import com.mvp.ecommercebackend.user.dto.CreateAddressRequest;
import com.mvp.ecommercebackend.user.dto.UpdateAddressRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own addresses, nested under {@code /api/me} for the same reason as the profile: the
 * owner comes from the token, never from the path.
 */
@RestController
@RequestMapping("/api/me/addresses")
@Tag(name = "Addresses")
@PreAuthorize("isAuthenticated()")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    @Operation(summary = "List the authenticated user's addresses, oldest first")
    public List<AddressResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return addressService.list(principal.id());
    }

    @PostMapping
    @Operation(summary = "Create an address for the authenticated user")
    public ResponseEntity<AddressResponse> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateAddressRequest request) {
        AddressResponse created = addressService.create(principal.id(), request);
        return ResponseEntity.created(URI.create("/api/me/addresses/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update one of the authenticated user's addresses",
            description = "Omitted or null fields are left unchanged. An empty string clears an "
                    + "optional field. An address belonging to another user answers 404, not 403.")
    public AddressResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody UpdateAddressRequest request) {
        return addressService.update(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one of the authenticated user's addresses")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID id) {
        addressService.delete(principal.id(), id);
    }
}
