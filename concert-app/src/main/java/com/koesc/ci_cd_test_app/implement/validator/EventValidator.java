package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventValidator {

    private final EventRepository eventRepository;

    /**
     * 1. 공연 제목 중복 검증
     * - 동일한 제목의 공연이 이미 등록되어 있는지 확인함.
     */
    public void validateDuplicateTitle(String title) {

        // 실제 운영 환경에서는 '공연 날짜'와 함께 복합적으로 체크할 수도 있음.
        if (eventRepository.existsByTitle(title)) {
            throw new IllegalArgumentException("이미 동일한 제목의 공연이 존재합니다.");
        }
    }

    /**
     * 2. 공연 존재 여부 검증 (수정/삭제 시 사용)
     */
    public void validateExists(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new IllegalArgumentException("존재하지 않는 공연 정보입니다. ID: " + eventId);
        }
    }

    /**
     * 3. 기본 정보 유효성 검사
     */
    public void validateBasicInfo(String title, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("공연 제목은 필수입니다.");
        }

        if (title.length() > 100) {
            throw new IllegalArgumentException("공연 제목은 100자를 초과할 수 없습니다.");
        }

        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("공연 상세 설명은 필수입니다.");
        }
    }

    /**
     * 4. 공연 시간 검증
     * 공연 시작 시간이 현재 시간보다 과거인지, 혹은 티켓 오픈 시간보다 뒤인지 검증하는 로직
     * TODO: 추후 Event 도메인 고도화 시 '예매 오픈 시간' 검증 로직 추가 예정
     * TODO: [핵심] 예매 오픈 시간 전 진입 시도 차단 (스크립트 방지)
     * TODO: ex) 08시 오픈인데 07시 59분 55초에 들어오는 요청 차단
     * public void validateEventTime
     */


    /**
     * 5. 공연 삭제 가능 여부 검증
     * 이미 티켓이 팔린 공연(Reservation이 존재하는 공연)은 삭제할 수 없어야 한다.
     * public void validateDeletable
     */
}
