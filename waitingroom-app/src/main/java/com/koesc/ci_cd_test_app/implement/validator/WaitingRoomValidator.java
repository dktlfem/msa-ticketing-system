package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.global.error.CustomException;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WaitingRoomValidator {

    private final WaitingTokenRepository waitingTokenRepository;

    /**
     * 1. 대기열 진입 요청 검증 (Join)
     * - 기본 값 검증
     * - 공연 상태 검증
     * - 예매 오픈 시간 검증 (부정 출발 방지)
     *
     * -> 필수 값 존재 여부만 검증하도록 리팩토링
     */
    public void validateJoinRequest(Long eventId, Long userId) {

        // 1. 필수 값 체크
        if (eventId == null || userId == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // [삭제] 타 도메인(Concert)의 Repository 직접 참조 로직 제거
        // 공연 존재 여부는 호출하는 Service 계층에서 Concert 서비스 API를 통해 확인하거나,
        // 진입 전 단계(Gateway/Controller)에서 검증하는 것이 MSA 원칙에 부합함.
    }

    /**
     * [1차] 중복 진입 검증
     * - 이미 Active 토큰이 있는 유저가 또 줄 서는 것 방지 (중요)
     * - 본인 도메인(WaitingToken)의 데이터만 사용하므로 유지
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
