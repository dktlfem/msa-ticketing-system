package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.storage.entity.WaitingTokenEntity;
import org.springframework.stereotype.Component;

@Component
public class WaitingRoomMapper {

    // Entity -> Domain 변환
    // DB의 영속성 컨텍스트에서 분리된 순수한 자바 객체로 변환하여 Service 계층으로 전달
    public WaitingToken toDomain(WaitingTokenEntity entity) {
        if (entity == null) return null;

        return WaitingToken.builder()
                .tokenId(entity.getTokenId())
                .userId(entity.getUserId())
                .eventId(entity.getEventId())
                .status(entity.getStatus())
                .issuedAt(entity.getIssuedAt())
                .expiredAt(entity.getExpiredAt())
                .build();
    }

    // Domain -> Entity 변환
    public WaitingTokenEntity toEntity(WaitingToken domain) {
        if (domain == null) return null;

        return WaitingTokenEntity.builder()
                .tokenId(domain.getTokenId())
                .userId(domain.getUserId())
                .eventId(domain.getEventId())
                .status(domain.getStatus())
                .issuedAt(domain.getIssuedAt())
                .expiredAt(domain.getExpiredAt())
                .build();
    }
}
