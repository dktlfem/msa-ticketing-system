package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.UserRequestDTO;
import com.koesc.ci_cd_test_app.api.response.UserResponseDTO;
import com.koesc.ci_cd_test_app.business.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User API", description = "사용자 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "회원 가입", description = "이메일과 이름을 통해 새로운 회원을 등록합니다.")
    @PostMapping("/signup")
    public UserResponseDTO signup(@RequestBody @Valid UserRequestDTO request) {
        return userService.signUp(request);
    }

    @Operation(summary = "내 정보 조회", description = "ID를 통해 사용자 정보를 조회합니다.")
    @GetMapping("/{userId}")
    public UserResponseDTO getInfo(@PathVariable Long userId) {
        return userService.getInfo(userId);
    }
}
