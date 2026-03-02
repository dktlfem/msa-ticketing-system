package com.koesc.ci_cd_test_app.global.api.pagination;

import org.springframework.data.domain.Sort;

import java.util.List;

public record SortMeta(String property, String direction) {

    public static List<SortMeta> from(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return List.of();
        }

        return sort.stream()
                .map(o -> new SortMeta(o.getProperty(), o.getDirection().name()))
                .toList();
    }
}
