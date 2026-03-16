package com.koesc.ci_cd_test_app.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentStatus {

    READY("결제 대기"),
    APPROVED("결제 완료"),
    FAILED("결제 실패"),
    CANCEL_PENDING("취소 요청 중"),
    REFUNDED("환불 완료"),
    CANCEL_FAILED("취소 실패 - 수동 처리 필요");

    private final String description;
}
