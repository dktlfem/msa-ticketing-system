package com.koesc.ci_cd_test_app.global.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * [Observability] 모든 HTTP 요청에 Trace ID를 부여하는 필터
 * 1. 요청마다 고유한 UUID를 생성하여 MDC(Logging Context)에 삽입
 * 2. 응답 헤더(X-Trace-Id)에 해당 ID를 추가하여 클라이언트와 공유
 */

@Component
public class TracingFilter implements Filter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {

        // 1. Trace ID 생성 (이미 헤더에 있다면 해당 값 유지, 없으면 신규 생성)
        String traceId = UUID.randomUUID().toString().substring(0, 8); // 식별 용이하게 8자리 사용

        try {

            // 2. MDC에 저장하여 이후 발생하는 모든 로그에 Trace ID가 자동으로 찍히게 함
            MDC.put(MDC_TRACE_ID, traceId);

            // 3. 응답 헤더에 Trace ID 추가
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(TRACE_ID_HEADER, traceId);
            }

            chain.doFilter(request, response);
        } finally {

            // 4. ThreadLocal 기반이므로 요청 종료 시 반드시 클리어 (메모리 누수 방지)
            MDC.clear();
        }

    }
}
