package com.koesc.ci_cd_test_app.domain.eventschedule;

/**
 * 왜 그런지 분류표(라벨), 카테고리로 표현
 */

public enum BookableReason {
    BOOKABLE("예매 가능"),
    ALREADY_STARTED("공연 시작 시간 지남"),
    NOT_OPEN_YET("예매 오픈 전 (정책 필요)"),
    EVENT_CLOSED("회차/공연 종료/취소");

    private final String description;

    BookableReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
