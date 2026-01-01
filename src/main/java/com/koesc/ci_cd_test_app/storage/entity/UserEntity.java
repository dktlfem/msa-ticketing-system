package com.koesc.ci_cd_test_app.storage.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity // 테이블이랑 1:1 매핑되는 객체
@Table(name = "members")
@Getter // 조회용 Getter 자동 생성
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA는 기본 생성자가 필수 (외부에서 무분별한 생성 방지)
@AllArgsConstructor(access = AccessLevel.PRIVATE) // Builder 사용 시 필수
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // auto_increment (1, 2, 3...)
    @Column(name = "member_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 100) // not null + unique
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private String password;

    @Builder.Default // 빌더 사용 시 초기값(0원)이 무시되지 않도록 설정
    @Column(nullable = false)
    private BigDecimal point = BigDecimal.ZERO; // 정확한 십진법 숫자를 나타낼 수 있다.

    // 생성일/수정일 (보통은 BaseEntity로 빼지만, 이해를 위해 여기에 작성)
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist // 저장(insert) 되기 직전에 실행
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        // 포인트가 null이면 0으로 안전하게 초기화
        if (this.point == null) {
            this.point = BigDecimal.ZERO;
        }
    }

    @PreUpdate // 수정(update) 되기 직전에 실행
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
