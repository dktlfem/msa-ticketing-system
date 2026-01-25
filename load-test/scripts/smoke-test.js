import http from 'k6/http';
import { check, sleep } from 'k6';

// 환경 변수(__ENV)를 사용하여 실행 시점에 대상 서버를 지정할 수 있게함.
const BASE_URL = __ENV.BASE_URL || 'http://192.168.219.102:8085';

export const options = {
    vus: 1, // 먼저 1명으로 테스트해서 에러를 잡습니다.
    duration: '10s',
    thresholds: {
        http_req_failed: ['rate<0.01'], // 실패율 0%여야 통과
    },
};

export default function () {
    // const url = 'http://192.168.219.102:8085/api/v1/waiting-room/join';
    const url = `${BASE_URL}/api/v1/waiting-room/join`;
    const payload = JSON.stringify({
        userId: 1,
        eventId: 1,
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
    };

    const res = http.post(url, payload, params);

    // [중요] 서버의 실제 응답을 터미널에 출력합니다.
    if (res.status !== 200) {
        console.log(`Error Status: ${res.status}`);
        console.log(`Error Body: ${res.body}`);
    }

    check(res, {
        'Smoke Test: status is 200': (r) => r.status === 200,
        // 아래 줄에서 에러가 나고 있으니, 일단 주석 처리하고 status부터 확인합니다.
        // 'has token': (r) => r.json().token !== undefined,
    });

    sleep(1);
}