package com.koesc.ci_cd_test_app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * CircuitBreaker가 open 상태일 때 fallbackUri: forward:/fallback/service-unavailable 로 전달됨.
 * CircuitBreaker Open 시 503 fallback 응답
 *
 * RFC 7807 ProblemDetail (application/problem+json) 형식으로 응답.
 *
 * requestId는 두 경로로 접근:
 *   1. MDC.get("requestId")    — Hooks.enableAutomaticContextPropagation() + Slf4jMdcThreadLocalAccessor 경로
 *   2. X-Request-Id 요청 헤더  — RequestCorrelationFilter가 mutate한 요청 헤더 경로 (fallback)
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback/service-unavailable")
    public Mono<ResponseEntity<ProblemDetail>> serviceUnavailable(ServerWebExchange exchange) {
        String requestId = resolveRequestId(exchange);
        String path = exchange.getRequest().getPath().value();

        log.warn("[CB_FALLBACK] requestId={} path={}", requestId, path);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Service Unavailable");
        problem.setDetail("The upstream service is temporarily unavailable. Please try again later.");
        problem.setInstance(URI.create(path));
        problem.setProperty("requestId", requestId);

        return Mono.just(
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problem)
        );
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        // MDC 전파가 정상 작동하면 MDC에서 획득 (스레드 전환 후에도 복원됨)
        String fromMdc = MDC.get("requestId");
        if (fromMdc != null) return fromMdc;
        // MDC 전파 미작동 시 X-Request-Id 헤더에서 직접 획득
        String fromHeader = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return fromHeader != null ? fromHeader : "unknown";
    }
}
