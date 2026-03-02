package com.koesc.ci_cd_test_app.domain.eventschedule;

/**
 * EventScheduleBookableStatus : 값만 담는 불변 객체(Value Object)
 * (bookable : 가능/불가능) + (code : 왜 그런지 코드) + (message : 사용자들이 읽을 설명) = 판정 결과지 (EventScheduleBookableStatus)
 */
public record BookableStatus(
        boolean bookable, // 예약 가능한지
        BookableReason code, // 상태 코드
        String message // 사용자들이 읽을 설명
) {}
