package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import org.springframework.stereotype.Component;

@Component
public class ReservationMapper {

    // Entity -> Domain
    public Reservation toDomain(ReservationEntity entity) {
        return Reservation.builder()
                .reservationId(entity.getReservationId())
                .userId(entity.getUserId())
                .seatId(entity.getSeatId())
                .status(entity.getStatus())
                .reservedAt(entity.getReservedAt())
                .expiredAt(entity.getExpiredAt())
                .build();
    }

    // Domain -> Entity
    public ReservationEntity toEntity(Reservation reservation) {
        return ReservationEntity.builder()
                .reservationId(reservation.getReservationId())
                .userId(reservation.getUserId())
                .seatId(reservation.getSeatId())
                .status(reservation.getStatus())
                .reservedAt(reservation.getReservedAt())
                .expiredAt(reservation.getExpiredAt())
                .build();
    }

    /**
     * Domain -> 기존 Entity 반영
     * 현재 Reservation은 상태 변경이 핵심이므로 status 중심으로 갱신
     */
    public void updateEntityFromDomain(Reservation reservation, ReservationEntity entity) {
        entity.changeStatus(reservation.getStatus());
    }
}
