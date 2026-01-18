package com.koesc.ci_cd_test_app.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 대기열 토큰의 생명주기(Lifecycle)를 관리하는 열거형
 * [상태 흐름]
 * 1. ACTIVE : Redis 대기열 통과 직후 -> DB에 저장될 때의 초기 상태. (입장 가능)
 * 2. USED : 사용자가 예매(Booking)를 완료했거나, 결제 페이지 진입에 성공했을 때 변환. (재사용 방지)
 * 3. EXPIRED : 유효 시간(10분)이 지났거나, 스케줄러에 의해 만료 처리됨.
 * 4. CANCELED : 부정 행위 감지 등으로 관리자가 강제 만료시킴.
 */
@Getter
@AllArgsConstructor
public enum WaitingTokenStatus {

    ACTIVE("활성화"), // 대기열 통과, 예매 시도 가능
    USED("사용완료"), // 이미 예매 로직을 수행함 (중복 진입 차단)
    EXPIRED("만료됨"), // 유효 시간 경과
    CANCELED("취소됨"); // 관리자 직권 취소

    private final String description;
}
