import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://192.168.219.102:8085';

export const options = {
    stages: [
        { duration: '30s', target: 50 },  // Ramp-up
        { duration: '1m', target: 100 },  // Steady state
        { duration: '30s', target: 200 }, // Peak state
        { duration: '30s', target: 0 },   // Ramp-down
    ],
    thresholds: {
        http_req_duration: ['p(95)<200'], // 지연 시간 성능 지표
    },
};

export default function () {
    const url = `${BASE_URL}/api/v1/waiting-room/join`;

    // 실제 프로젝트에 포함된 EventDataSeeder를 통해 생성된 데이터를 고려하여
    // 다양한 유저 ID로 부하를 분산시킨다.
    const payload = JSON.stringify({
        userId: Math.floor(Math.random() * 1000000) + 1,
        eventId: 1,
    });

    const params = { headers: { 'Content-Type': 'application/json' } };
    const res = http.post(url, payload, params);

    check(res, {
        'Load Test: status is 200': (r) => r.status === 200,
    });

    sleep(1);
}