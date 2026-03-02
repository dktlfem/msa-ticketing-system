package com.koesc.ci_cd_test_app.domain.eventschedule;

/**
 * Service가 유즈케이스 B를 처리하기 위한 최종 결과 묶음
 *
 * EventSchedule (회차) + BookableStatus (판정 결과지)를 같이 담아서 묶음.
 * DTO가 아닌 Domain이라 레이어링 깨지지 않음.
 */
public record EventScheduleDetail(
        EventSchedule schedule,
        BookableStatus bookableStatus
) {}
