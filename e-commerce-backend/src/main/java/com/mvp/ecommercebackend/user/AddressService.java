package com.mvp.ecommercebackend.user;

import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import com.mvp.ecommercebackend.user.dto.AddressResponse;
import com.mvp.ecommercebackend.user.dto.CreateAddressRequest;
import com.mvp.ecommercebackend.user.dto.UpdateAddressRequest;
import com.mvp.ecommercebackend.user.entity.Address;
import com.mvp.ecommercebackend.user.repository.AddressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserService userService;

    public AddressService(AddressRepository addressRepository, UserService userService) {
        this.addressRepository = addressRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(UUID userId) {
        return addressRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(AddressService::toResponse)
                .toList();
    }

    @Transactional
    public AddressResponse create(UUID userId, CreateAddressRequest request) {
        Address address = new Address();
        // Goes through UserService, not UserRepository: a token naming a deleted user must not
        // create an orphan row, and the cross-feature rule is service-to-service.
        address.setUser(userService.requireUser(userId));
        address.setRecipientName(request.recipientName().trim());
        address.setPhone(blankToNull(request.phone()));
        address.setLine1(request.line1().trim());
        address.setLine2(blankToNull(request.line2()));
        address.setCity(request.city().trim());
        address.setState(blankToNull(request.state()));
        address.setPostalCode(request.postalCode().trim());
        address.setCountry(request.country().trim().toUpperCase());
        address.setDefaultShipping(Boolean.TRUE.equals(request.defaultShipping()));
        address.setDefaultBilling(Boolean.TRUE.equals(request.defaultBilling()));

        Address saved = addressRepository.save(address);
        demoteOtherDefaults(userId, saved);
        return toResponse(saved);
    }

    @Transactional
    public AddressResponse update(UUID userId, UUID addressId, UpdateAddressRequest request) {
        Address address = requireOwnedAddress(userId, addressId);

        if (request.recipientName() != null) {
            address.setRecipientName(request.recipientName().trim());
        }
        if (request.phone() != null) {
            address.setPhone(blankToNull(request.phone()));
        }
        if (request.line1() != null) {
            address.setLine1(request.line1().trim());
        }
        if (request.line2() != null) {
            address.setLine2(blankToNull(request.line2()));
        }
        if (request.city() != null) {
            address.setCity(request.city().trim());
        }
        if (request.state() != null) {
            address.setState(blankToNull(request.state()));
        }
        if (request.postalCode() != null) {
            address.setPostalCode(request.postalCode().trim());
        }
        if (request.country() != null) {
            address.setCountry(request.country().trim().toUpperCase());
        }
        if (request.defaultShipping() != null) {
            address.setDefaultShipping(request.defaultShipping());
        }
        if (request.defaultBilling() != null) {
            address.setDefaultBilling(request.defaultBilling());
        }

        Address saved = addressRepository.save(address);
        demoteOtherDefaults(userId, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        // Deleting the default does not promote a replacement: which address takes over is the
        // owner's decision, not something to guess.
        addressRepository.delete(requireOwnedAddress(userId, addressId));
    }

    private Address requireOwnedAddress(UUID userId, UUID addressId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address Not Found!"));
    }

    /**
     * Enforces at most one default of each kind per user.
     *
     * <p>Done by mutating the loaded entities rather than with a bulk {@code @Modifying} update: a
     * bulk update needs {@code clearAutomatically}, which would detach {@code promoted} and leave
     * the response reading from a stale instance.
     */
    private void demoteOtherDefaults(UUID userId, Address promoted) {
        if (!promoted.isDefaultShipping() && !promoted.isDefaultBilling()) {
            return;
        }

        for (Address other : addressRepository.findByUserIdOrderByCreatedAtAsc(userId)) {
            if (other.equals(promoted)) {
                continue;
            }
            if (promoted.isDefaultShipping()) {
                other.setDefaultShipping(false);
            }
            if (promoted.isDefaultBilling()) {
                other.setDefaultBilling(false);
            }
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AddressResponse toResponse(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getRecipientName(),
                address.getPhone(),
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getState(),
                address.getPostalCode(),
                address.getCountry(),
                address.isDefaultShipping(),
                address.isDefaultBilling(),
                address.getCreatedAt());
    }
}
