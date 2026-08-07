package com.publicmonitor.backend.domain.auth.web.controller;

import com.publicmonitor.backend.domain.auth.service.AuthService;
import com.publicmonitor.backend.domain.auth.web.dto.LoginRequest;
import com.publicmonitor.backend.domain.auth.web.dto.LoginResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public SuccessResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return SuccessResponse.ok(authService.login(request));
    }
}
