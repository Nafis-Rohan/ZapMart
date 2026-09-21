package com.nafis.ZapMart.common.security;

import com.nafis.ZapMart.common.exception.ForbiddenException;
import com.nafis.ZapMart.user.Role;
import com.nafis.ZapMart.user.User;
import com.nafis.ZapMart.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final UserRepository userRepository;

    public void requireAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenException("Unknown user"));
        if (user.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Admin access required");
        }
    }
}