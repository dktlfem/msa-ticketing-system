import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://15.134.88.109';

export const options = {
    stages: [
        { duration: '1m', target: 500 },  // 1분 동안 500명까지 급격히 상승
        { duration: '2m', target: 1000 }, // 2분 동안 1,000명 유지 (스트레스 구간)
        { duration: '1m', target: 0 },    // 회복 측정
    ],
    thresholds: {
        http_req_failed: ['rate<0.1'], // 스트레스 상황에서도 실패율 10% 미만 유지 목표
        http_req_duration: ['p(99)<5000'], // 최악의 경우에도 1초 이내 응답
    },
};

export default function () {
    const url = `${BASE_URL}/api/v1/waiting-room/join`;

    // 시딩된 1,000명의 유저 범위를 고려한 랜덤 ID 생성
    const payload = JSON.stringify({
        userId: Math.floor(Math.random() * 1000) + 1,
        eventId: Math.floor(Math.random() * 30) + 1,
    });

    const params = { headers: { 'Content-Type': 'application/json' } };
    const res = http.post(url, payload, params);

    check(res, {
        'Stress Test: status is 200': (r) => r.status === 200,
    });

    sleep(0.5); // 요청 간격을 좁혀 더 강한 부하 유도
}