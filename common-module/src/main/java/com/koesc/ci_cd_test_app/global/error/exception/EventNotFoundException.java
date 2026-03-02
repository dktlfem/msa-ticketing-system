package com.koesc.ci_cd_test_app.global.error.exception;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;

/**
 * super(): 부모 생성사 호출
 *
 * 자식 객체(EventNotFoundException)을 만들 땐 부모 객체(BusinessException)가 먼저 초기화돼야 함.
 * 그래서 모든 생성자는 첫 줄에서 super(...) 또는 this(...)중 하나를 호출해야 함.
 * (안 쓰면 컴파일러가 자동으로 super()를 넣어주는데, 부모 클래스의 BusinessException() 기본생성자가 없으므로 못 넣음)
 */
public class EventNotFoundException extends BusinessException{

    public EventNotFoundException(Long eventId) {
        super(ErrorCode.EVENT_NOT_FOUND, "공연을 찾을 수 없습니다. eventId = " + eventId);
    }
}
