package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.WaitingRoomRequestDTO;
import com.koesc.ci_cd_test_app.business.WaitingRoomService;
import com.koesc.ci_cd_test_app.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

/**
 * WaitingRoomController 슬라이스 테스트
 */
@WebFluxTest(controllers = WaitingRoomController.class)
@Import(SecurityConfig.class)
public class WaitingRoomControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private WaitingRoomService waitingRoomService;

    @Test
    @DisplayName("대기열 진입 API: 200 OK + rank 반환 + isAllowed = false")
    void joinWaitingRoom_ReturnOk() throws Exception {

        // 1. given
        WaitingRoomRequestDTO request = new WaitingRoomRequestDTO(1L, 100L);
        given(waitingRoomService.joinQueue(1L, 100L))
                .willReturn(Mono.just(50L));

        // 2. when & then
        webTestClient
                .mutateWith(mockUser()) // WebFlux 보안 통과용
                .post()
                .uri("/api/v1/waiting-room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.rank").isEqualTo(50)
                .jsonPath("$.isAllowed").isEqualTo(false);

    }
}
