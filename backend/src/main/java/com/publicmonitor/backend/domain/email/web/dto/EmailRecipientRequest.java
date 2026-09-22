package com.publicmonitor.backend.domain.email.web.dto;
import jakarta.validation.constraints.*;
public record EmailRecipientRequest(
    @NotBlank @Email @Size(max = 254)
    @Pattern(regexp = "^[^\\s<>;,]+@[^\\s<>;,]+$", message = "이메일 주소 한 개를 입력해 주세요.") String address,
    @NotNull @Size(max = 100) String name, @NotNull Boolean enabled
) {}
