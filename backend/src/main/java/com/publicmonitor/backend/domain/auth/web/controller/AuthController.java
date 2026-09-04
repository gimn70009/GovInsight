package com.publicmonitor.backend.domain.auth.web.controller;

import com.publicmonitor.backend.domain.auth.service.AuthService;
import com.publicmonitor.backend.domain.auth.web.dto.LoginRequest;
import com.publicmonitor.backend.domain.auth.web.dto.LoginResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "관리자 로그인과 JWT 발급 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "관리자 로그인",
            description = "로그인 아이디와 비밀번호를 검증하고 API 인증에 사용할 JWT 액세스 토큰을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호 불일치")
    })
    @PostMapping("/login")
    public SuccessResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return SuccessResponse.ok(authService.login(request));
    }
}
