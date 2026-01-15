package com.koesc.ci_cd_test_app.implement.client;

/**
 * AI 모델 서빙 서버와의 통신을 담당하는 클라이언트 인터페이스
 * ML Backend 환경에서는 모델의 추론 (Inference) 시간이 가변적이므로 이를 추상화하여 관리함.
 */
public interface AIModelClient {

    // 유저의 정보를 바탕으로 어뷰징 여부를 판단 (무거운 연산 시뮬레이션용)
    String predictAbuse(Long userId);
}
