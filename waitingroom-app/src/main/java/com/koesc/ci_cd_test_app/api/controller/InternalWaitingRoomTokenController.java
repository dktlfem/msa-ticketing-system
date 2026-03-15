package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.business.WaitingRoomInternalService;
import com.koesc.ci_cd_test_app.domain.WaitingToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
/**
 * 1. 응답 형태(성공)
 * - POST /internal/v1/waiting-room/tokens/validate -> ValidateTokenResponse
 * - POST /internal/v1/waiting-room/tokens/{tokenId}/consume -> ConsumeTokenResponse
 *
 * 2. 실패 시(스펙 그대로)
 * - 410 Gone: 만료
 * - 409 Conflict: 이미 사용됨
 * - 422 Unprocessable Entity: 유효하지 않음
 */
@Tag(name = "Internal WaitingRoom Token API", description = "서비스 간 대기열 활성 토큰 검증/소모 내부 API")
@RestController
@RequestMapping("/internal/v1/waiting-room/tokens")
@RequiredArgsConstructor
public class InternalWaitingRoomTokenController {

    private final WaitingRoomInternalService waitingRoomInternalService;

    @Operation(summary = "활성 토큰 검증", description = "booking-app이 예약 생성 전에 토큰의 유효성을 검증합니다.")
    @PostMapping("/validate")
    public ResponseEntity<ValidateTokenResponse> validate(@Valid @RequestBody ValidateTokenRequest request) {
        try {
            WaitingToken token = waitingRoomInternalService.validateOrThrow(
                    request.tokenId(),
                    request.userId(),
                    request.eventId()
            );

            return ResponseEntity.ok(new ValidateTokenResponse(
                    true,
                    token.getTokenId(),
                    token.getStatus().name(),
                    token.getExpiredAt()
            ));
        } catch (IllegalStateException e) {
            throw mapTokenException(e.getMessage(), request.tokenId());
        }
    }

    @Operation(summary = "활성 토큰 소모 처리", description = "booking-app 예약 생성 성공 후 토큰을 USED 처리합니다.")
    @PostMapping("/{tokenId}/consume")
    public ResponseEntity<ConsumeTokenResponse> consume(
            @PathVariable String tokenId,
            @Valid @RequestBody ConsumeTokenRequest request
    ) {
        try {
            WaitingToken token = waitingRoomInternalService.consumeOrThrow(tokenId);

            return ResponseEntity.ok(new ConsumeTokenResponse(
                    token.getTokenId(),
                    token.getStatus().name()
            ));
        } catch (IllegalStateException e) {
            throw mapTokenException(e.getMessage(), tokenId);
        }
    }

    private ResponseStatusException mapTokenException(String code, String tokenId) {
        if ("WAITING_TOKEN_EXPIRED".equals(code)) {
            return new ResponseStatusException(
                    HttpStatus.GONE,
                    "WAITING_TOKEN_EXPIRED: tokenId=" + tokenId
            );
        }

        if ("WAITING_TOKEN_ALREADY_USED".equals(code)) {
            return new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "WAITING_TOKEN_ALREADY_USED: tokenId=" + tokenId
            );
        }

        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "WAITING_TOKEN_INVALID: tokenId=" + tokenId
        );
    }

    public record ValidateTokenRequest(
            @NotBlank String tokenId,
            @NotNull Long userId,
            @NotNull Long eventId
    ) {
    }

    public record ValidateTokenResponse(
            Boolean valid,
            String tokenId,
            String status,
            LocalDateTime expiredAt
    ) {
    }

    /**
     * 감사 로그/Audit용
     * - 현재는 reserved field
     */
    public record ConsumeTokenRequest(
            @NotBlank String usedBy
    ) {
    }

    public record ConsumeTokenResponse(
            String tokenId,
            String status
    ) {
    }
}