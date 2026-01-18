package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.mapper.UserMapper;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reader : 조회 전용, DB에서 데이터를 찾아오는 것(findBy...)만 전문적으로 수행
 */

@Component
@RequiredArgsConstructor
public class UserReader {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public User read(Long id) {
        return userRepository.findById(id)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));
    }

    public User readByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));
    }
}
