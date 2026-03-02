package com.koesc.ci_cd_test_app.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * ApiCommonTest의 X-Trace-Id 헤더 검증을 맞추기 위해 TraceIdFilter 생성
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId"; // %mdc{traceId}

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        try {
            // 1. 로그에 찍힐 수 있도록 MDC에 저장
            MDC.put(MDC_KEY, traceId);

            // 2. 응답 헤더에도 넣어줌
            response.setHeader(TRACE_HEADER, traceId);

            filterChain.doFilter(request, response);
        } finally {
            // 3. 요청이 끝나면 반드시 비워줘야 함. (ThreadLocal 메모리 누수 방지)
            MDC.remove(MDC_KEY);
        }
    }
}
