package com.koesc.ci_cd_test_app.storage.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "events", schema = "ticketing_concert")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "poster_url", length = 255)
    private String posterUrl;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist // 저장(insert) 되기 직전에 실행
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate // 수정(update) 되기 직전에 실행
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 낙관전 락(Optimistic Lock)
     *
     * 장애 포인트 : 동시성 제어를 위한 @Version 어노테이션 필요
     * 위험 : 관리자 두 명이 동시에 같은 공연 정보를 수정할 때 '두 번째 수정자'가 첫 번째 수정 내용을
     *       덮어쓰는(Lost Update) 현상이 발생함.
     *
     * 해결 : EventEntity에 @Version private Long version; 필드를 추가하여 정합성을 보장
     */
    @Version
    private Long version;
}
