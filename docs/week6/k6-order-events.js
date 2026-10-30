// 주문 확정이 카프카로 이벤트를 내보내는 경로에 부하를 준다.
//
//   VUS=100 k6 run docs/week6/k6-order-events.js
//
// 포인트 결제를 쓴다. 카드는 PG 가 미확정을 돌려주므로 확정만으로는 발행이
// 일어나지 않는다. 발행 경로를 재려면 그 자리에서 성공하는 결제여야 한다.
//
// 발행 건수는 k6 가 모른다. 브로커 오프셋을 전후로 비교해서 센다.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE_URL || 'http://localhost:8099';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '30s';
const IDS_FILE = __ENV.IDS_FILE || 'product-ids.json';
const CHARGE = Number(__ENV.CHARGE || 100000000);
const METHOD = __ENV.METHOD || 'POINT';
const CARD_TYPE = __ENV.CARD_TYPE || 'SAMSUNG';
const CARD_NO = __ENV.CARD_NO || '1234-5678-9814-1451';

const productIds = new SharedArray('productIds', () => JSON.parse(open(IDS_FILE)));

const createLatency = new Trend('step_create', true);
const confirmLatency = new Trend('step_confirm', true);

const createRejected = new Counter('create_rejected');
const confirmOk = new Counter('confirm_ok');
const confirmRejected = new Counter('confirm_rejected');
const confirmBroken = new Counter('confirm_broken');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    order: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      exec: 'order',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

function headers(userId) {
  return { headers: { 'Content-Type': 'application/json', 'X-USER-ID': String(userId) } };
}

export function setup() {
  for (let userId = 1; userId <= VUS; userId++) {
    http.post(`${BASE}/api/v1/points/charge`, JSON.stringify({ amount: CHARGE }), headers(userId));
  }
}

export function order() {
  const userId = 1 + (__VU % VUS);
  const productId = productIds[Math.floor(Math.random() * productIds.length)];

  const created = http.post(
    `${BASE}/api/v1/orders`,
    JSON.stringify({ lines: [{ productId, quantity: 1 }] }),
    { ...headers(userId), tags: { step: 'create' } },
  );
  createLatency.add(created.timings.duration);

  if (created.status !== 200) {
    createRejected.add(1);
    return;
  }

  const orderId = JSON.parse(created.body).data.id;
  const confirmed = http.post(
    `${BASE}/api/v1/orders/${orderId}/confirm`,
    JSON.stringify(
      METHOD === 'CARD'
        ? { method: 'CARD', cardType: CARD_TYPE, cardNo: CARD_NO }
        : { method: 'POINT' },
    ),
    { ...headers(userId), tags: { step: 'confirm' } },
  );
  confirmLatency.add(confirmed.timings.duration);

  if (confirmed.status === 200) {
    confirmOk.add(1);
  } else if (confirmed.status >= 400 && confirmed.status < 500) {
    confirmRejected.add(1);
  } else {
    confirmBroken.add(1);
  }
}
