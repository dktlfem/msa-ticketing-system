// SCG 시나리오 0: 전체 서비스 워밍업 (메트릭 등록 + CB lazy-init 유도)
//
// 목적:
//   모든 서비스에 최소 1회 이상 트래픽을 보내서
//   Micrometer 메트릭 등록, Resilience4j CB lazy initialization,
//   HikariCP 커넥션풀 초기화를 유도한다.
//   Grafana 대시보드 작성 전 메트릭 존재 여부를 확인하기 위한 사전 작업.
//
// 실행:
//   k6 run --env SCG_BASE_URL=http://192.168.124.100:8090 scenario0-warmup.js
//
// 소요 시간: 약 30초 (10 VUs × 30s)

import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL = __ENV.SCG_BASE_URL || 'http://192.168.124.100:8090';
const JWT_SECRET   = __ENV.JWT_SECRET   || 'change-me-in-production-must-be-at-least-32-bytes!!';

// ── 옵션 ─────────────────────────────────────────────────────
export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
        },
    },
    thresholds: {
        // 워밍업이므로 느슨한 임계치
        http_req_duration: ['p(95)<3000'],
    },
};

// ── JWT 생성 ─────────────────────────────────────────────────
function generateJwt(userId) {
    const header = encoding.b64encode(
        JSON.stringify({ alg: 'HS256', typ: 'JWT' }),
        'rawurl'
    );
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({
            sub: String(userId),
            roles: ['USER'],
            iat: now,
            exp: now + 3600,
        }),
        'rawurl'
    );
    const sigInput = `${header}.${payload}`;
    const sig = encoding.b64encode(
        crypto.hmac('sha256', JWT_SECRET, sigInput, 'binary'),
        'rawurl'
    );
    return `${sigInput}.${sig}`;
}

// ── 워밍업 대상 엔드포인트 ───────────────────────────────────
// ADR: 각 서비스를 SCG 경유로 1회 이상 호출하여
// CircuitBreaker/Bulkhead/RateLimiter 메트릭을 Micrometer에 등록시킨다.
const ENDPOINTS = [
    // concert-app (GET)
    { method: 'GET',  path: '/api/v1/events',       name: 'concert-events' },
    // booking-app (GET)
    { method: 'GET',  path: '/api/v1/reservations',  name: 'booking-reservations' },
    // payment-app (GET)
    { method: 'GET',  path: '/api/v1/payments/1',    name: 'payment-detail' },
    // waitingroom-app (GET)
    { method: 'GET',  path: '/api/v1/queue/status',  name: 'waitingroom-status' },
    // user-app (GET)
    { method: 'GET',  path: '/api/v1/users/me',      name: 'user-me' },
];

// ── 메인 VU 함수 ────────────────────────────────────────────
export default function () {
    const vuId = __VU;
    const token = generateJwt(vuId);
    const params = {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
        tags: {},
    };

    for (const ep of ENDPOINTS) {
        params.tags.name = ep.name;
        const url = `${SCG_BASE_URL}${ep.path}`;

        let res;
        if (ep.method === 'GET') {
            res = http.get(url, params);
        } else {
            res = http.post(url, null, params);
        }

        // 워밍업이므로 200/401/404/429/503 모두 허용
        // 핵심은 SCG가 요청을 받아 CB/Bulkhead가 등록되는 것
        check(res, {
            [`${ep.name}: status < 600`]: (r) => r.status < 600,
        });

        sleep(0.2);
    }

    sleep(0.5);
}

// ── 테스트 종료 후 안내 ──────────────────────────────────────
export function handleSummary(data) {
    const lines = [
        '\n=== Warmup Complete ===',
        '다음 Prometheus 쿼리로 메트릭 등록을 확인하세요:',
        '',
        '1) Golden Signals:',
        '   count by (application) (http_server_requests_seconds_count)',
        '',
        '2) CircuitBreaker:',
        '   count by (application, name) (resilience4j_circuitbreaker_state)',
        '',
        '3) Bulkhead:',
        '   count by (application, name) (resilience4j_bulkhead_max_allowed_concurrent_calls)',
        '',
        '4) HikariCP:',
        '   hikaricp_connections_active',
        '',
    ];
    console.log(lines.join('\n'));
    return {};
}
