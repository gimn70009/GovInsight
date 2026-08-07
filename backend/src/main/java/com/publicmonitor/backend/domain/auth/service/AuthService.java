package com.publicmonitor.backend.domain.auth.service;

import com.publicmonitor.backend.domain.auth.exception.InvalidCredentialsException;
import com.publicmonitor.backend.domain.auth.web.dto.LoginRequest;
import com.publicmonitor.backend.domain.auth.web.dto.LoginResponse;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import com.publicmonitor.backend.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new LoginResponse(
                jwtTokenProvider.createAccessToken(user),
                "Bearer",
                jwtTokenProvider.getAccessTokenExpirationSeconds()
        );
    }
}
