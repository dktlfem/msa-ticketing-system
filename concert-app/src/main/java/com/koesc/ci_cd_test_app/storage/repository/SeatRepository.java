package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.domain.SeatStatus;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<SeatEntity, Long> {

    // findAllScheduleId : findAllScheduleId라는 이름의 필드를 기준으로 데이터를 찾음.
    // findAllByScheduleId : find..By 패턴 발견하여
    //                       By 앞부분은 행위로 인식, By 뒷부분은 조건으로 인식 (필드명 scheduleId를 찾음)
    // -> 결과 : SELECT * FROM seat WHERE schedule_id = ? 라는 SQL을 자동으로 생성함.

    // 특정 공연 회차의 모든 좌석 조회
    List<SeatEntity> findAllByScheduleId(Long scheduleId);

    // 특정 회차의 가용한(AVAILABLE) 좌석만 조회하는 기능
    List<SeatEntity> findAllByScheduleIdAndStatus(Long scheduleId, SeatStatus status);
}
