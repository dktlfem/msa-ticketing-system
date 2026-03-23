package com.koesc.ci_cd_test_app.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * SCG 전역 에러 핸들러.
 *
 * Spring Boot의 DefaultErrorWebExceptionHandler(order=-1)를 @Order(-2)로 덮어씀.
 * RFC 7807 ProblemDetail을 application/problem+json으로 반환.
 * requestId는 MDC 또는 X-Request-Id 헤더에서 획득.
 *
 * 필터 체인 외부에서 발생하는 예외(라우팅 실패, 연결 거부 등)를 커버함.
 * 필터 체인 내부 예외는 각 필터에서 직접 처리.
 */
@Component
@Order(-2)
public class GlobalErrorHandler extends AbstractErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    public GlobalErrorHandler(ErrorAttributes errorAttributes,
                               WebProperties webProperties,
                               ApplicationContext applicationContext,
                               ServerCodecConfigurer codecConfigurer) {
        super(errorAttributes, webProperties.getResources(), applicationContext);
        setMessageWriters(codecConfigurer.getWriters());
        setMessageReaders(codecConfigurer.getReaders());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Throwable error = getError(request);
        HttpStatus status = determineStatus(error);

        String requestId = resolveRequestId(request);
        log.error("[GATEWAY_ERROR] requestId={} path={} status={} error={}",
                requestId, request.path(), status.value(), error.getMessage());

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(sanitizeMessage(error.getMessage()));
        problem.setInstance(URI.create(request.path()));
        problem.setProperty("requestId", requestId);

        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(BodyInserters.fromValue(problem));
    }

    private HttpStatus determineStatus(Throwable error) {
        if (error instanceof org.springframework.web.server.ResponseStatusException rse) {
            return HttpStatus.resolve(rse.getStatusCode().value()) != null
                    ? HttpStatus.resolve(rse.getStatusCode().value())
                    : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String sanitizeMessage(String message) {
        if (message == null) return "An unexpected error occurred";
        // 스택 트레이스나 내부 경로 노출 방지
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }

    private String resolveRequestId(ServerRequest request) {
        String fromMdc = MDC.get("requestId");
        if (fromMdc != null) return fromMdc;
        return request.headers().firstHeader("X-Request-Id") != null
                ? request.headers().firstHeader("X-Request-Id")
                : "unknown";
    }
}
