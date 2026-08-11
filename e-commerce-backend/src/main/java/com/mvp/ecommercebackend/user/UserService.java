package com.mvp.ecommercebackend.user;

import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import com.mvp.ecommercebackend.user.dto.UpdateProfileRequest;
import com.mvp.ecommercebackend.user.dto.UserProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        return toResponse(requireUser(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) {
            // An empty string is an explicit "clear this", distinct from omitting the field.
            String trimmed = request.phone().trim();
            user.setPhone(trimmed.isEmpty() ? null : trimmed);
        }

        return toResponse(userRepository.save(user));
    }

    /**
     * Loads the caller's own record. A valid signature does not guarantee the row still exists, so
     * a deleted account gets a 404 rather than a null-pointer 500.
     */
    User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User Not Found!"));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.isEmailVerified(),
                user.getRoles().stream().map(Role::getCode).map(Enum::name).sorted().toList(),
                user.getCreatedAt());
    }
}
