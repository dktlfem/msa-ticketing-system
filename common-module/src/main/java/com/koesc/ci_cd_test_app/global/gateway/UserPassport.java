package com.koesc.ci_cd_test_app.global.gateway;

import java.util.List;

/**
 * SCG가 JWT 검증 후 Auth-Passport 헤더에 담아 전파하는 인증 컨텍스트.
 *
 * ADR-0007 확정 필드 스펙.
 *
 * @param userId   JWT sub — downstream 서비스의 주요 인증 주체
 * @param roles    JWT roles — 향후 권한 검사용
 * @param jti      JWT jti — 토큰 ID (replay 감지용, nullable)
 * @param issuedAt JWT iat — 토큰 발급 시각 (epoch seconds)
 * @param clientIp X-Forwarded-For 첫 번째 값
 */
public record UserPassport(
        String userId,
        List<String> roles,
        String jti,
        long issuedAt,
        String clientIp
) {}
