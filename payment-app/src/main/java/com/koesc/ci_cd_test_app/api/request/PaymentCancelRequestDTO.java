package com.koesc.ci_cd_test_app.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentCancelRequestDTO(
        @NotBlank @Size(max = 200) String cancelReason
) {}
