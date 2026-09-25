// k6 load test: duplicate checkouts and payments, all fired at the same instant.
//
// One iteration = one purchase attempt by a brand-new made-up user:
//   1. add an item to the cart
//   2. send the SAME checkout DUPLICATES times at once (same Idempotency-Key)   -> like a double click / retry
//   3. send the SAME payment  DUPLICATES times at once (same Idempotency-Key)
//
// With idempotency ON  : exactly one order and one charge per iteration.
// With idempotency OFF : duplicate orders / charges show up (the "before" stage).
//
// Needs the app running with profile "loadtest" (fake Stripe + zapmart_test database).
// Settings (all optional):  BASE_URL, RATE (iterations per MINUTE), DURATION, DUPLICATES, STAGE, RUN_ID
//   example:  k6 run -e RATE=1500 -e DURATION=2m -e STAGE=postgres-redis loadtest/checkout-duplicates.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9090';
const RATE = parseInt(__ENV.RATE || '1500', 10);
const DURATION = __ENV.DURATION || '2m';
const DUPLICATES = parseInt(__ENV.DUPLICATES || '3', 10);
const STAGE = __ENV.STAGE || 'run';
const RUN_ID = __ENV.RUN_ID || String(Date.now());
const REPLAY = (__ENV.REPLAY || '1') === '1'; // one late retry of the finished payment (use -e REPLAY=0 to skip)

export const options = {
  scenarios: {
    duplicates: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  // Lax limits: they only exist so k6 reports timing per request type.
  thresholds: {
    'http_req_duration{name:checkout}': ['p(95)<120000'],
    'http_req_duration{name:payment}': ['p(95)<120000'],
    'http_req_duration{name:replay}': ['p(95)<120000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

// k6 requires custom metrics to be created here (not inside the test function).
const KNOWN_STATUSES = ['201', '400', '409', '422', '500'];
const counts = {};
for (const phase of ['checkout', 'payment', 'replay']) {
  for (const s of [...KNOWN_STATUSES, 'other']) {
    counts[`${phase}_${s}`] = new Counter(`${phase}_${s}`);
  }
  counts[`${phase}_replayed`] = new Counter(`${phase}_replayed`);
}
const ordersCreated = new Counter('orders_created');
const duplicateOrders = new Counter('duplicate_orders');
const paymentsCreated = new Counter('payments_created');
const duplicatePayments = new Counter('duplicate_payments');

function tally(phase, res) {
  const status = String(res.status);
  counts[`${phase}_${KNOWN_STATUSES.includes(status) ? status : 'other'}`].add(1);
  if (res.headers['Idempotent-Replayed'] === 'true') {
    counts[`${phase}_replayed`].add(1);
  }
}

// Distinct ids among the 201 responses. More than one id = the server created duplicates.
function distinctIds(responses) {
  const ids = new Set();
  for (const r of responses) {
    if (r.status === 201) {
      try {
        ids.add(r.json('id'));
      } catch (e) {
        // not JSON: ignore
      }
    }
  }
  return ids;
}

function sameRequestManyTimes(url, body, headers, name) {
  const requests = [];
  for (let i = 0; i < DUPLICATES; i++) {
    requests.push(['POST', url, body, { headers, tags: { name } }]);
  }
  return http.batch(requests); // batch = sent in parallel
}

export function setup() {
  const reset = http.post(`${BASE_URL}/fake-stripe/reset`);
  if (reset.status !== 200) {
    throw new Error(`Fake Stripe not reachable (status ${reset.status}). Start the app with profile "loadtest".`);
  }
  const product = http.post(
    `${BASE_URL}/api/products`,
    JSON.stringify({
      name: `LoadTest ${RUN_ID}`,
      description: 'k6 load test product',
      price: 2.5,
      stockQuantity: 100000000,
      category: 'LoadTest',
    }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': '1' } },
  );
  if (product.status !== 201) {
    throw new Error(`Could not create the test product (status ${product.status}): ${product.body}`);
  }
  return { productId: product.json('id') };
}

export default function (data) {
  const userId = 1000000 + __VU * 1000000 + __ITER; // a new user every iteration
  const base = { 'Content-Type': 'application/json', 'X-User-Id': String(userId) };

  // 1. cart
  const add = http.post(
    `${BASE_URL}/api/cart/items`,
    JSON.stringify({ productId: data.productId, quantity: 1 }),
    { headers: base, tags: { name: 'add_to_cart' } },
  );
  if (!check(add, { 'cart add is 200': (r) => r.status === 200 })) {
    return;
  }

  // 2. same checkout, many times, at once
  const checkoutResponses = sameRequestManyTimes(
    `${BASE_URL}/api/orders/checkout`,
    null,
    Object.assign({}, base, { 'Idempotency-Key': `co-${RUN_ID}-${userId}` }),
    'checkout',
  );
  checkoutResponses.forEach((r) => tally('checkout', r));
  const orderIds = distinctIds(checkoutResponses);
  ordersCreated.add(orderIds.size);
  if (orderIds.size > 1) {
    duplicateOrders.add(orderIds.size - 1);
  }
  if (orderIds.size === 0) {
    return;
  }
  const orderId = [...orderIds][0];

  // 3. same payment, many times, at once
  const paymentResponses = sameRequestManyTimes(
    `${BASE_URL}/api/orders/${orderId}/payments`,
    JSON.stringify({ paymentMethodId: 'pm_card_visa' }),
    Object.assign({}, base, { 'Idempotency-Key': `pay-${RUN_ID}-${userId}` }),
    'payment',
  );
  paymentResponses.forEach((r) => tally('payment', r));
  const paymentIds = distinctIds(paymentResponses);
  paymentsCreated.add(paymentIds.size);
  if (paymentIds.size > 1) {
    duplicatePayments.add(paymentIds.size - 1);
  }

  // 4. a late retry of the SAME payment after everything finished: answered from Redis (cache on)
  //    or from Postgres (cache off). This is where the Redis cache should make a difference.
  if (REPLAY && paymentIds.size > 0) {
    const replay = http.post(
      `${BASE_URL}/api/orders/${orderId}/payments`,
      JSON.stringify({ paymentMethodId: 'pm_card_visa' }),
      {
        headers: Object.assign({}, base, { 'Idempotency-Key': `pay-${RUN_ID}-${userId}` }),
        tags: { name: 'replay' },
      },
    );
    tally('replay', replay);
  }
}

export function handleSummary(data) {
  const value = (metric, field) => {
    const m = data.metrics[metric];
    return m && m.values[field] !== undefined ? m.values[field] : 0;
  };
  const count = (metric) => value(metric, 'count');
  const ms = (metric, field) => Math.round(value(metric, field));

  let stats = { chargeRequests: 'unknown', distinctIdempotencyKeys: 'unknown' };
  try {
    stats = http.get(`${BASE_URL}/fake-stripe/stats`).json();
  } catch (e) {
    // fake Stripe not reachable: leave "unknown"
  }

  const iterations = count('iterations');
  const lines = [
    '',
    `=== RESULT  stage: ${STAGE} ===`,
    `Rate            : ${RATE} iterations/min for ${DURATION}, ${DUPLICATES} duplicate requests each`,
    `Iterations done : ${iterations}   (dropped, machine could not keep up: ${count('dropped_iterations')})`,
    '',
    `Orders created           : ${count('orders_created')}`,
    `DUPLICATE ORDERS         : ${count('duplicate_orders')}   <-- must be 0 with idempotency on`,
    `Payments created         : ${count('payments_created')}`,
    `DUPLICATE PAYMENTS       : ${count('duplicate_payments')}   <-- must be 0 with idempotency on`,
    `Fake Stripe charge calls : ${stats.chargeRequests}   (ideal: equal to Payments created)`,
    '',
    `Checkout responses : 201=${count('checkout_201')}  409=${count('checkout_409')}  422=${count('checkout_422')}  400=${count('checkout_400')}  500=${count('checkout_500')}  other=${count('checkout_other')}  replayed=${count('checkout_replayed')}`,
    `Payment responses  : 201=${count('payment_201')}  409=${count('payment_409')}  422=${count('payment_422')}  400=${count('payment_400')}  500=${count('payment_500')}  other=${count('payment_other')}  replayed=${count('payment_replayed')}`,
    '',
    `Checkout time (ms) : avg=${ms('http_req_duration{name:checkout}', 'avg')}  med=${ms('http_req_duration{name:checkout}', 'med')}  p95=${ms('http_req_duration{name:checkout}', 'p(95)')}  max=${ms('http_req_duration{name:checkout}', 'max')}`,
    `Payment time (ms)  : avg=${ms('http_req_duration{name:payment}', 'avg')}  med=${ms('http_req_duration{name:payment}', 'med')}  p95=${ms('http_req_duration{name:payment}', 'p(95)')}  max=${ms('http_req_duration{name:payment}', 'max')}`,
    `Late replay       : 201=${count('replay_201')}  409=${count('replay_409')}  500=${count('replay_500')}  other=${count('replay_other')}  replayed=${count('replay_replayed')}`,
    `Late replay time (ms): avg=${ms('http_req_duration{name:replay}', 'avg')}  med=${ms('http_req_duration{name:replay}', 'med')}  p95=${ms('http_req_duration{name:replay}', 'p(95)')}  max=${ms('http_req_duration{name:replay}', 'max')}`,
    '',
  ];

  const result = {
    stage: STAGE,
    ratePerMinute: RATE,
    duration: DURATION,
    duplicates: DUPLICATES,
    iterations,
    ordersCreated: count('orders_created'),
    duplicateOrders: count('duplicate_orders'),
    paymentsCreated: count('payments_created'),
    duplicatePayments: count('duplicate_payments'),
    fakeStripeChargeRequests: stats.chargeRequests,
  };

  return {
    stdout: lines.join('\n'),
    [`loadtest/results-${STAGE}.json`]: JSON.stringify(result, null, 2),
  };
}
