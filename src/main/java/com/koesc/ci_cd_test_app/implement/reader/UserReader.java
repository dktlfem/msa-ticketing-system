package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reader : 조회 전용, DB에서 데이터를 찾아오는 것(findBy...)만 전문적으로 수행
 */

@Component
@RequiredArgsConstructor
public class UserReader {

    private final UserRepository userRepository;

    
}
