package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringSourceResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceEnabledRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceRequest;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Monitoring Sources", description = "공공기관 게시판 모니터링 소스 관리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/monitoring-sources")
public class MonitoringSourceController {

    private final MonitoringSourceService monitoringSourceService;

    @Operation(summary = "모니터링 소스 등록", description = "수집할 공공기관 게시판과 상세 게시글 선별 규칙을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "소스 등록 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "409", description = "이미 등록된 목록 URL")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuccessResponse<MonitoringSourceResponse> create(
            @Valid @RequestBody CreateMonitoringSourceRequest request
    ) {
        return SuccessResponse.created(monitoringSourceService.create(request));
    }

    @Operation(summary = "모니터링 소스 목록 조회", description = "등록된 모니터링 소스 전체를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping
    public SuccessResponse<List<MonitoringSourceResponse>> findAll() {
        return SuccessResponse.ok(monitoringSourceService.findAll());
    }

    @Operation(summary = "모니터링 소스 단건 조회", description = "소스 식별자로 등록 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "단건 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 소스 식별자"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "404", description = "소스를 찾을 수 없음")
    })
    @GetMapping("/{sourceId}")
    public SuccessResponse<MonitoringSourceResponse> findById(
            @Parameter(description = "모니터링 소스 식별자", example = "1", required = true)
            @Positive @PathVariable Long sourceId
    ) {
        return SuccessResponse.ok(monitoringSourceService.findById(sourceId));
    }

    @Operation(summary = "모니터링 소스 수정", description = "등록된 모니터링 소스의 전체 설정을 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "소스 수정 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "404", description = "소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "다른 소스에서 사용 중인 목록 URL")
    })
    @PutMapping("/{sourceId}")
    public SuccessResponse<MonitoringSourceResponse> update(
            @Parameter(description = "모니터링 소스 식별자", example = "1", required = true)
            @Positive @PathVariable Long sourceId,
            @Valid @RequestBody UpdateMonitoringSourceRequest request
    ) {
        return SuccessResponse.ok(monitoringSourceService.update(sourceId, request));
    }

    @Operation(summary = "모니터링 소스 활성 상태 변경", description = "등록된 소스의 활성 또는 비활성 상태만 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "활성 상태 변경 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "404", description = "소스를 찾을 수 없음")
    })
    @PatchMapping("/{sourceId}/enabled")
    public SuccessResponse<MonitoringSourceResponse> changeEnabled(
            @Parameter(description = "모니터링 소스 식별자", example = "1", required = true)
            @Positive @PathVariable Long sourceId,
            @Valid @RequestBody UpdateMonitoringSourceEnabledRequest request
    ) {
        return SuccessResponse.ok(monitoringSourceService.changeEnabled(sourceId, request));
    }
}
