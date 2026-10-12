// k6: carga do CONSOLIDADO DIÁRIO a 50 req/s (adaptação para o repo de implementação).
// -----------------------------------------------------------------------------
// Verety: régua ≤5% = LITERAL do PDF
//   -> perda de requisições NA SUPERFÍCIE DO CONSOLIDADO a 50 req/s.
// Threshold: http_req_failed < 0.05  (denominador = requisições do consolidado).
//
// Equivalência:
//   Rodar contra o docker-compose local prova SEMÂNTICA e COMPORTAMENTO do
//   consolidado, NUNCA capacidade/HA do Alvo (Aurora/ElastiCache/Amazon MQ).
//   Todo resultado local é rotulado: PROXY LOCAL, semântica.
//
// Uso:
//   docker run --rm -v "$PWD":/w -w /w grafana/k6 run harness/k6/k6-consolidado.js
//   BASE_URL=http://localhost:8081 k6 run harness/k6/k6-consolidado.js
// -----------------------------------------------------------------------------

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'; // Serviço de Consolidado
const MERCHANT = __ENV.MERCHANT || '22222222-2222-2222-2222-222222222222';
const DATA = __ENV.DATA || '2026-10-08'; // data de negócio: ADR-012
const SURFACE = __ENV.SURFACE || 'consolidado'; // 'consolidado' (default) | 'ingest'
const RATE = Number(__ENV.RATE || 50); // 50 req/s: literal do PDF
const TOKEN = __ENV.TOKEN || ''; // Bearer JWT (auth real; sem segredo a API é 403/fail-closed)

// Auth REAL (não bypass): a API exige SCOPE_consolidado:read / SCOPE_lancamentos:write.
function authHeaders(extra) {
  const h = extra || {};
  if (TOKEN) h['Authorization'] = `Bearer ${TOKEN}`;
  return h;
}

const perdaConsolidado = new Rate('perda_consolidado');

export const options = {
  scenarios: {
    consolidado_50rps: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  // Critério de aceite: ≤5% de perda NA SUPERFÍCIE DO CONSOLIDADO (não reescrever).
  thresholds: {
    http_req_failed: ['rate<0.05'],
    perda_consolidado: ['rate<0.05'],
    'http_req_duration{expected_response:true}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

export default function () {
  let res;
  if (SURFACE === 'ingest') {
    // Contrato real (LancamentoRequest): snake_case + dinheiro como STRING "0.00".
    const body = JSON.stringify({
      comerciante_id: MERCHANT,
      tipo: 'CREDITO',
      valor: '1.00',
      moeda: 'BRL',
      data_negocio: DATA,
    });
    const headers = authHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
    });
    res = http.post(`${BASE_URL}/api/v1/lancamentos`, body, { headers });
    check(res, { 'ingest 201/200/409/422': (r) => [200, 201, 409, 422].includes(r.status) });
  } else {
    res = http.get(
      `${BASE_URL}/api/v1/consolidado/${MERCHANT}?data=${DATA}`,
      { headers: authHeaders(), tags: { name: 'GET /consolidado' } },
    );
    check(res, { 'consolidado 200/404': (r) => [200, 404].includes(r.status) });
  }

  // "Perda" = a requisição NÃO foi servida na superfície em teste (5xx/timeout/rede).
  // 404 é entrega legítima (saldo inexistente), não perda.
  const ok = SURFACE === 'ingest'
    ? [200, 201, 409, 422].includes(res.status)
    : [200, 404].includes(res.status);
  perdaConsolidado.add(ok ? 0 : 1);
}
