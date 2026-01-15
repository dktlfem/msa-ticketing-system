import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1, // 먼저 1명으로 테스트해서 에러를 잡습니다.
    duration: '10s',
};

export default function () {
    const url = 'http://192.168.124.100:8085/api/v1/waiting-room/join';
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
        'status is 200': (r) => r.status === 200,
        // 아래 줄에서 에러가 나고 있으니, 일단 주석 처리하고 status부터 확인합니다.
        // 'has token': (r) => r.json().token !== undefined,
    });

    sleep(1);
}