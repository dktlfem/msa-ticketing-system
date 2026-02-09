package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import org.springframework.stereotype.Component;

@Component
public class SeatMapper {

    // Entity -> Domain
    public Seat toDomain(SeatEntity entity) {
        if (entity == null) return null;

        return Seat.builder()
                .seatId(entity.getSeatId())
                .scheduleId(entity.getScheduleId())
                .seatNo(entity.getSeatNo())
                .price(entity.getPrice())
                .status(entity.getStatus())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    // Domain -> Entity
    public SeatEntity toEntity(Seat domain) {
        if (domain == null) return null;

        return SeatEntity.builder()
                .seatId(domain.getSeatId())
                .scheduleId(domain.getScheduleId())
                .seatNo(domain.getSeatNo())
                .price(domain.getPrice())
                .status(domain.getStatus())
                .version(domain.getVersion())
                .build();
    }

    // 기존 엔티티 수정용
    public void updateEntityFromDomain(Seat seat, SeatEntity entity) {
        entity.changeStatus(seat.getStatus());
    }
}
