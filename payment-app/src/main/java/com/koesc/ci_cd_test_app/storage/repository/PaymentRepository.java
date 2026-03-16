package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.storage.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByOrderId(String orderId);

    Optional<PaymentEntity> findByPaymentKey(String paymentKey);

    Optional<PaymentEntity> findByReservationId(Long reservationId);

    boolean existsByReservationId(Long reservationId);
}
