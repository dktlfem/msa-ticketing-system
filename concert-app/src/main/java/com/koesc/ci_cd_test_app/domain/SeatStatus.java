package com.koesc.ci_cd_test_app.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SeatStatus {
    AVAILABLE("예약 가능"),
    HOLD("임시 점유(결제 대기 중)"),
    SOLD("판매 완료");

    private final String description;
}
