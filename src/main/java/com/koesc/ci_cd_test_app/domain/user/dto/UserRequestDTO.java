package com.koesc.ci_cd_test_app.domain.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRequestDTO {

    private String username;
    private String password;
    private String nickname;
    private String email;
}
