package com.koesc.ci_cd_test_app.global.api.pagination;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * API 외부 계약 : EventSchedule를 담아서 반환
 */

public record PageResponse<T>(
        List<T> content, // scheduleDTO 목록
        PageMeta page, // 페이지 메타데이터
        List<SortMeta> sort // 정렬 기준이 무엇인지
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                PageMeta.from(page),
                SortMeta.from(page.getSort())
        );
    }

    /**
     * Domain Page를 받아서 DTO로 변환까지 한 번에 처리하고 싶을 때.
     * Controller에서 코드가 매우 깔끔해짐
     */
    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                PageMeta.from(page),
                SortMeta.from(page.getSort())
        );
    }
}
