package com.koesc.ci_cd_test_app.handler;

import com.koesc.ci_cd_test_app.domain.jwt.service.JwtService;
import com.koesc.ci_cd_test_app.util.JWTUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 자체 로그인 성공 이후 프론트로 JWT 를 발급해주는 Handler
 * 로그인 성공이라는 "특정 이벤트에만" 반응하는 핸들러이다.
 */
@Component
@Qualifier("LoginSuccessHandler")
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private JwtService jwtService;

    public LoginSuccessHandler(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    // 로그인이 성공된 이후에 이 메서드가 실행되므로 인자로는 request, response, authentication 3개가 넘어온다.
    // request : 사용자가 보낸 요청의 모든 정보 (헤더, 파라미터, 세션 정보 등) 을 담는다.
    // response : 서버가 사용자에게 보낼 응답을 생성하는데 사용된다. 리다이렉션, 쿠키 추가 등이 여기서 이루어진다.
    // authentication : 로그인에 성공한 사용자의 주체 정보, 권한 등을 포함하는 객체이다.
    //                  시큐리티 컨테이너가 로그인 성공 후 생성하여 전달한다.
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {

        // 로그인 된 사용자의 username, role 추출
        String username =  authentication.getName();
        String role = authentication.getAuthorities().iterator().next().getAuthority();

        // JWT(Access/Refresh) 발급
        String accessToken = JWTUtil.createJWT(username, role, true);
        String refreshToken = JWTUtil.createJWT(username, role, false);

        // 발급한 Refresh DB 테이블 저장 (Refresh whitelist)
        jwtService.addRefresh(username, refreshToken);

        // 응답
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String json = String.format("{\"accessToken\":\"%s\", \"refreshToken\":\"%s\"}", accessToken, refreshToken);
        response.getWriter().write(json);
        response.getWriter().flush();
    }
}
