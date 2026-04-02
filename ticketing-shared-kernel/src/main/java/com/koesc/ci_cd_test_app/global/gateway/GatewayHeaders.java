package com.koesc.ci_cd_test_app.global.gateway;

/**
 * SCG ↔ downstream 서비스 간 공유 헤더 상수.
 *
 * ADR-0007: RFC 6648 준수(X- 접두어 제거) + Auth-Passport 단일 컨텍스트 헤더 도입.
 *
 * 이 클래스는 ticketing-shared-kernel에 위치하여
 * scg-app(WebFlux)과 downstream 서비스(MVC) 모두에서 참조할 수 있다.
 */
public final class GatewayHeaders {

    private GatewayHeaders() {}

    // ── 신규 헤더 (ADR-0007) ─────────────────────────────────

    /** Base64url(JSON) 인코딩된 인증 컨텍스트. SCG JwtAuthenticationFilter가 주입. */
    public static final String AUTH_PASSPORT = "Auth-Passport";

    /** JWT sub에서 추출한 인증 사용자 ID. */
    public static final String AUTH_USER_ID = "Auth-User-Id";

    /** 요청 상관관계 ID. SCG RequestCorrelationFilter가 생성/전파. */
    public static final String CORRELATION_ID = "Correlation-Id";

    /** 대기열 통과 토큰. 클라이언트가 예약 생성 시 전달. */
    public static final String QUEUE_TOKEN = "Queue-Token";

    /** 서비스 간 내부 호출 식별자. */
    public static final String INTERNAL_CALLER = "Internal-Caller";

    /** 서비스 간 내부 통신 전용 토큰. */
    public static final String INTERNAL_TOKEN = "Internal-Token";

}
