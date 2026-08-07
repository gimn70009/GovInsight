package com.publicmonitor.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.auth.exception.InvalidCredentialsException;
import com.publicmonitor.backend.domain.auth.web.dto.LoginRequest;
import com.publicmonitor.backend.domain.auth.web.dto.LoginResponse;
import com.publicmonitor.backend.domain.user.entity.Role;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import com.publicmonitor.backend.global.security.JwtProperties;
import com.publicmonitor.backend.global.security.JwtTokenProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String LOGIN_ID = "admin";
    private static final String PASSWORD = "password123!";
    private static final String SECRET = "VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        JwtProperties properties = new JwtProperties(SECRET, Duration.ofHours(1));
        JwtTokenProvider tokenProvider = new JwtTokenProvider(
                properties,
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC)
        );
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider);
    }

    @Test
    void 올바른_계정으로_로그인하면_JWT를_발급한다() {
        User user = User.create(LOGIN_ID, passwordEncoder.encode(PASSWORD), Role.ADMIN);
        given(userRepository.findByLoginId(LOGIN_ID)).willReturn(Optional.of(user));

        LoginResponse response = authService.login(new LoginRequest(LOGIN_ID, PASSWORD));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
    }

    @Test
    void 존재하지_않는_아이디로_로그인하면_실패한다() {
        given(userRepository.findByLoginId(LOGIN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(LOGIN_ID, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void 비밀번호가_다르면_로그인에_실패한다() {
        User user = User.create(LOGIN_ID, passwordEncoder.encode(PASSWORD), Role.ADMIN);
        given(userRepository.findByLoginId(LOGIN_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(LOGIN_ID, "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

}
