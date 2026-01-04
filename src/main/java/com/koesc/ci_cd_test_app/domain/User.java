package com.koesc.ci_cd_test_app.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain <-> Entity 구분하면 좋은점 ? <- 공부 필요
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE) // 빌더용 전체 생성자 (외부 접근 막음)
public class User {

    private final Long id;
    private final String email;
    private final String name;
    private final String password;
    private final BigDecimal point;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /**
     * 포인트 충전
     *
     * point라는 데이터를 User가 가지고 있다면,
     * 그 데이터를 사용하는 규칙(0원 이상 충전, 잔액 부족 검사 등)도 User가 직접 관리하는 것이 맞다.
     */
    public User chargePoint(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("0원 이상 충전해야 합니다.");
        }

        // 불변성 유지 : toBuilder()를 사용하여 기존 객체를 수정하는 대신 새로운 객체를 반환 (Side Effect)
        // 해석 : 나(this)를 복사해서 빌더를 열어, 근데 point랑 updatedAt만 바꿀 거야
        return this.toBuilder()
                .point(this.point.add(amount)) // 포인트 변경
                .updatedAt(LocalDateTime.now()) // 수정일 변경
                .build(); // 나머지는 기존 값 유지됨
    }

    /**
     * 포인트 사용(결제)
     */
    public User usePoint(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("사용할 포인트는 0보다 커야 합니다.");
        }

        if (this.point.compareTo(amount) < 0) {
            throw new IllegalArgumentException("잔액이 부족합니다.");
        }

        // 3. 기존 객체 수정 (toBuilder 사용)
        return this.toBuilder()
                .point(this.point.subtract(amount)) // 포인트 차감
                .updatedAt(LocalDateTime.now()) // 수정일 변경
                .build();
    }

    /**
     * 회원 가입 시 초기 User 생성용 (ID 없음)
     */
    public static User create(String email, String name, String password) {
        // 4. 새 객체 생성 (일반 builder 사용)
        // 해석 : 아예 새로운 User를 만들 거야
        return User.builder()
                .email(email)
                .name(name)
                .password(password)
                .point(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now()) // 가입일
                .updatedAt(LocalDateTime.now()) // 수정일
                .build();
    }
}
