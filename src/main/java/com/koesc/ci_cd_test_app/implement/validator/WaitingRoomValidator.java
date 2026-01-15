package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.global.error.CustomException;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class WaitingRoomValidator {

    private final EventRepository eventRepository;
    private final WaitingTokenRepository waitingTokenRepository;

    /**
     * 1. 대기열 진입 요청 검증 (Join)
     * - 기본 값 검증
     * - 공연 상태 검증
     * - 예매 오픈 시간 검증 (부정 출발 방지)
     */
    public void validateJoinRequest(Long eventId, Long userId) {

        // 1. 필수 값 체크
        if (eventId == null || userId == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 2. 공연 존재 여부 체크 (1차적으로 존재 여부만)
        if (!eventRepository.existsById(eventId)) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }

        // TODO: 추후 Event 도메인 고도화 시 '예매 오픈 시간' 검증 로직 추가 예정
        // TODO: [핵심] 예매 오픈 시간 전 진입 시도 차단 (스크립트 방지)
        // TODO: ex) 08시 오픈인데 07시 59분 55초에 들어오는 요청 차단
    }

    /**
     * [1차] 중복 진입 검증
     * - 이미 Active 토큰이 있는 유저가 또 줄 서는 것 방지 (중요)
     */
    public void validateDuplicateAccess(Long eventId, Long userId) {

        // 이미 활성 토큰이 있는 유저인지 체크
        if (waitingTokenRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitingTokenStatus.ACTIVE)) {
            throw new CustomException(ErrorCode.ALREADY_HAS_TOKEN);
        }
    }

    /**
     * [1차] 토큰 발급 시점 검증
     * TODO: - 지금은 간단하게 패스 (추후 Seat, Schedule 개발 시 '매진 여부' 체크 추가)
     */
    public void validateIssueToken(Long eventId) {
        // TODO: 추후 Seat 도메인 개발 완료 시 매진 검증 로직 추가 예정
    }

}
