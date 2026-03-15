package com.koesc.ci_cd_test_app.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReservationStatus {
    PENDING("임시 예약(결제 대기 중)"),
    CONFIRMED("예약 확정"),
    CANCELLED("예약 취소");

    private final String description;
}
