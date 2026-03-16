package com.koesc.ci_cd_test_app.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentRequestDTO(
        @NotNull @Positive Long reservationId
) {}
