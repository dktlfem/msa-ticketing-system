package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import com.koesc.ci_cd_test_app.storage.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByOrderId(String orderId);

    Optional<PaymentEntity> findByPaymentKey(String paymentKey);

    Optional<PaymentEntity> findByReservationId(Long reservationId);

    boolean existsByReservationId(Long reservationId);

    /**
     * 특정 상태의 결제 목록 조회.
     * CANCEL_FAILED 복구 스케줄러에서 사용한다.
     */
    List<PaymentEntity> findAllByStatus(PaymentStatus status);
}
