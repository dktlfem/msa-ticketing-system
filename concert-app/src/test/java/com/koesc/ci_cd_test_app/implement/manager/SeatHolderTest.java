package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.domain.SeatStatus;
import com.koesc.ci_cd_test_app.implement.mapper.SeatMapper;
import com.koesc.ci_cd_test_app.implement.reader.SeatReader;
import com.koesc.ci_cd_test_app.implement.validator.SeatValidator;
import com.koesc.ci_cd_test_app.implement.writer.SeatWriter;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import com.koesc.ci_cd_test_app.storage.repository.SeatRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * SeatHolderTest -> Mockito 단위 테스트(로직의 정합성)
 * "내 로직이 리포지토리를 잘 호출하는가?"와 "리포지토리에서 락 예외가 발생했을 때 내가 잘 던지는가?"를 검증 (속도가 매우 빠름)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeatHolder 단위 테스트 :  (Mock)")
public class SeatHolderTest {

    @Mock
    private SeatReader seatReader;

    @Mock
    private SeatMapper seatMapper;

    @Mock
    private SeatValidator seatValidator;

    @Mock
    private SeatWriter seatWriter;

    @InjectMocks
    private SeatHolder seatHolder;

    @Test
    @DisplayName("좌석 점유 성공 : AVAILABLE 상태의 좌석을 조회하여 HOLD 상태로 저장한다.")
    void hold_Success() {

        // 1. given
        Long seatId = 1L;
        SeatEntity seatEntity = SeatEntity.builder()
                .seatId(seatId)
                .status(SeatStatus.AVAILABLE)
                .price(new BigDecimal("10000"))
                .build();

        given(seatReader.readEntity(seatId)).willReturn(seatEntity);

        // SeatHolder 내부에서 mapper/validator를 쓰니 최소 스텁
        Seat seat = mock(Seat.class);
        given(seatMapper.toDomain(any(SeatEntity.class))).willReturn(seat);

        // seat.hold()가 null이 아니도록
        Seat seatAfterHold = mock(Seat.class);
        given(seat.hold()).willReturn(seatAfterHold);

        doNothing().when(seatValidator).validateAvailable(any(Seat.class));

        // updateWithFlush 반환값(Seat)을 null이 아니라 HOLD Seat로 반환
        Seat returned = mock(Seat.class);
        given(returned.getStatus()).willReturn(SeatStatus.HOLD);

        given(seatWriter.updateWithFlush(any(Seat.class), any(SeatEntity.class)))
                .willReturn(returned);

        // 2. when
        Seat result = seatHolder.hold(seatId);

        // 3. then
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(SeatStatus.HOLD); // 상태 변경 확인

        verify(seatReader).readEntity(seatId);
        verify(seatMapper).toDomain(seatEntity);
        verify(seatValidator).validateAvailable(any(Seat.class));
        verify(seatWriter).updateWithFlush(any(Seat.class), eq(seatEntity));
    }

    @Test
    @DisplayName("낙관적 락 충돌 : 저장 시점에 OptimisticLock 예외가 발생하면 그대로 상위로 전파한다.")
    void hold_OptimisticLock_Failure() {

        // 1. given
        Long seatId = 1L;
        SeatEntity seatEntity = SeatEntity.builder()
                .seatId(seatId)
                .status(SeatStatus.AVAILABLE)
                .build();

        given(seatReader.readEntity(seatId)).willReturn(seatEntity);

        Seat seat = mock(Seat.class);
        given(seatMapper.toDomain(any(SeatEntity.class))).willReturn(seat);

        // seat.hold()가 null이면 writer까지 못 감
        Seat seatAfterHold = mock(Seat.class);
        given(seat.hold()).willReturn(seatAfterHold);

        doNothing().when(seatValidator).validateAvailable(any(Seat.class));

        // 예외는 seatRepository가 아니라 seatWriter.updateWithFlush에 걸어야 함
        given(seatWriter.updateWithFlush(any(Seat.class), any(SeatEntity.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(SeatEntity.class, seatId));

        // 2.  when & then
        assertThatThrownBy(() -> seatHolder.hold(seatId))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(seatWriter).updateWithFlush(any(Seat.class), eq(seatEntity));
    }

    @Test
    @DisplayName("좌석 부재 : 존재하지 않는 좌석 ID로 점유 시도 시 IllegalArgumentException이 발생한다.")
    void hold_NotFound() {

        // 1. given
        Long seatId = 999L;
        given(seatReader.readEntity(seatId))
                .willThrow(new IllegalArgumentException("존재하지 않는 좌석입니다."));

        // 2. when & then
        assertThatThrownBy(() -> seatHolder.hold(seatId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 좌석입니다.");

        verify(seatWriter, never()).updateWithFlush(any(), any()); // 저장은 호출되지 않아야 함
        verify(seatWriter, never()).update(any(), any());
        verify(seatValidator, never()).validateAvailable(any());
        verify(seatMapper, never()).toDomain(any());
    }
}
