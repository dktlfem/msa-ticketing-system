package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
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
     * 공연 존재 여부/상태는 waitingroom-app이 직접 DB로 확인하지 않음(타 도메인 침범 방지)
     * -> 필수 값 존재 여부만 검증하도록 리팩토링
     */
    public void validateJoinRequest(Long eventId, Long userId) {

        // 1. 필수 값 체크
        if (eventId == null || userId == null) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "필수 값 누락: eventId/userId는 필수입니다."
            );
        }

        // [삭제] 타 도메인(Concert)의 Repository 직접 참조 로직 제거
        // 공연 존재 여부는 호출하는 Service 계층에서 Concert 서비스 API를 통해 확인하거나,
        // 진입 전 단계(Gateway/Controller)에서 검증하는 것이 MSA 원칙에 부합함.
    }

    /**
     * [1차] 중복 진입 검증
     * - 이미 Active 토큰이 있는 유저가 또 줄 서지 못하게 차단 (중요)
     * - 본인 도메인(WaitingToken)의 데이터만 사용하므로 유지
     */
    public void validateDuplicateAccess(Long eventId, Long userId) {
        boolean hasActive =
                waitingTokenRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitingTokenStatus.ACTIVE);

        // 이미 활성 토큰이 있는 유저인지 체크
        if (hasActive) {
            throw new BusinessException(
                    ErrorCode.ALREADY_HAS_TOKEN,
                    "이미 유효한 토큰이 존재합니다. eventId = " + eventId + ", userId = " + userId
            );
        }
    }

    /**
     * [1차] 토큰 발급 시점 검증
     */
    public void validateIssueToken(Long eventId) {
        // TODO: 추후 Seat/Schedule 도메인 개발 완료 시 매진 등 정책 검증 추가
    }
}
