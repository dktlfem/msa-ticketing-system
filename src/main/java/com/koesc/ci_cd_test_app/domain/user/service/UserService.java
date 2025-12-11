package com.koesc.ci_cd_test_app.domain.user.service;

import com.koesc.ci_cd_test_app.domain.user.dto.UserRequestDTO;
import com.koesc.ci_cd_test_app.domain.user.entity.UserEntity;
import com.koesc.ci_cd_test_app.domain.user.entity.UserRoleType;
import com.koesc.ci_cd_test_app.domain.user.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements UserDetailsService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public UserService(PasswordEncoder passwordEncoder, UserRepository userRepository) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    // 자체 로그인 회원 가입 (존재 여부) - Read
    @Transactional(readOnly = true)
    public Boolean existUser(UserRequestDTO dto) {
        return userRepository.existsByUsername(dto.getUsername());
    }

    // 자체 로그인 회원 가입 - Create
    @Transactional
    public Long addUser(UserRequestDTO dto) {

        // 프론트를 통해서가 아닌 Postman 해킹 툴을 통해서 백엔드에 직접적으로 바로 요청할 수 있어서 한 번 더 검증
        if (userRepository.existsByUsername(dto.getUsername())) {
            throw new IllegalArgumentException("이미 유저가 존재합니다.");
        }

        UserEntity entity = UserEntity.builder()
                .username(dto.getUsername())
                .password(passwordEncoder.encode(dto.getPassword()))
                .isLock(false)
                .isSocial(false)
                .roleType(UserRoleType.USER) // 우선 일반 유저로 가입
                .nickname(dto.getUsername())
                .email(dto.getEmail())
                .build();

        return userRepository.save(entity).getId();
    }


    // 자체 로그인 - Read
    @Transactional(readOnly = true)
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        UserEntity entity = userRepository.findByUsernameAndIsLockAndIsSocial(username, false, false)
                .orElseThrow(() -> new UsernameNotFoundException(username));

        return User.builder()
                .username(entity.getUsername())
                .password(entity.getPassword())
                .roles(entity.getRoleType().name())
                .accountLocked(entity.getIsLock())
                .build();
    }


    // 자체 로그인 회원 정보 수정 - Update
    @Transactional
    public Long updateUser(UserRequestDTO dto) throws AccessDeniedException {

        // 본인만 수정 가능
        String sessionUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!sessionUsername.equals(dto.getUsername())) {
            throw new AccessDeniedException("본인 계정만 수정 가능");
        }

        // 조회
        UserEntity entity = userRepository.findByUsernameAndIsLockAndIsSocial(dto.getUsername(), false, false)
                .orElseThrow(() -> new UsernameNotFoundException(dto.getUsername()));

        // 회원 정보 수정
        entity.updateUser(dto);

        return userRepository.save(entity).getId();

    }


    // 자체/소셜 로그인 탈퇴


    // 소셜 로그인 (매 로그인 시 : 신규 = 가, 기존 = 업데이트)


    // 자체/소셜 유저 정보 조회
}
