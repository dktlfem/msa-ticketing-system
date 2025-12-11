package com.koesc.ci_cd_test_app.domain.user.entity;

import lombok.Getter;

@Getter
public enum SocialProviderType {

    NAVER("네이버"),
    GOOGLE("구글");

    private final String description;

    SocialProviderType(String descritption) {
        this.description = descritption;
    }
}
