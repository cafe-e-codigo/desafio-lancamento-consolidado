# Harness executável

Este diretório reúne os testes que provam, na prática, o comportamento dos dois serviços. Escrevi
cada peça para ser executada de ponta a ponta: a suíte JUnit via Testcontainers, a régua de carga do k6
e os controles de single-flight. Tudo roda contra o `docker-compose` local. O resultado é sempre
`PROXY LOCAL, semântica`: ajuda a validar semântica e comportamento, mas não prova capacidade nem HA
do Alvo (Aurora/ElastiCache/Amazon MQ).

## Suíte (Testcontainers dentro do container do Maven)

O host não serve para rodar `mvn test` direto: aqui o Maven é 3.6.3 no JDK 11 e o daemon Docker expõe
API mínima 1.40, então subo a imagem do Maven no JDK 21 e negocio a versão da API na mão.

```bash
# build + testes dos dois módulos; socket Docker montado para o Testcontainers.
# -Dapi.version=1.43 é obrigatório neste host: o docker-java embutido negocia 1.32
# e o daemon recusa -> "Could not find a valid Docker environment".
# (a env DOCKER_API_VERSION não resolve; tem que ser via -Dapi.version.)
docker run --rm \
  -v "$PWD":/w -v verety-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w /w maven:3.9-eclipse-temurin-21 \
  mvn -B test -Dapi.version=1.43
```

O atalho `make test` é exatamente esse comando.

## Régua de carga (k6)

A API exige Bearer JWT com os scopes `SCOPE_consolidado:read` / `SCOPE_lancamentos:write`
(`SecurityConfig.java:74-75`). O segredo vem de uma variável só: `VERETY_JWT_HMAC_SECRET`. O app lê
`hmac-secret: ${VERETY_JWT_HMAC_SECRET:}` (`*/src/main/resources/application-security.yml:15`) e o
compose já injeta a mesma var com o placeholder de harness (`docker-compose.yml:99,147`). O
`harness/mint-token.py` lê a mesma var, com o mesmo default, então não há override a fazer.

```bash
# stack canônica (o compose base já traz o placeholder)
docker compose up -d --no-build

# token alinhado ao segredo do serviço (usa VERETY_JWT_HMAC_SECRET; default = placeholder do base)
TOKEN=$(python3 harness/mint-token.py)
# ou, com segredo explícito:
#   VERETY_JWT_HMAC_SECRET=meu-segredo python3 harness/mint-token.py

# régua ≤5% na superfície do CONSOLIDADO (50 req/s)
docker run --rm --network host -v "$PWD":/w -w /w grafana/k6:latest \
  run -e BASE_URL=http://localhost:8081 -e SURFACE=consolidado -e TOKEN="$TOKEN" \
  -e DURATION=60s -e RATE=50 harness/k6/k6-consolidado.js

# ingestão ponta a ponta (50 req/s), para o invariante DISTINCT
docker run --rm --network host -v "$PWD":/w -w /w grafana/k6:latest \
  run -e BASE_URL=http://localhost:8080 -e SURFACE=ingest -e TOKEN="$TOKEN" \
  -e DURATION=60s -e RATE=50 harness/k6/k6-consolidado.js
```

Sanidade de auth: `curl` com token devolve 200; sem token devolve 403.

## Single-flight distribuído

```bash
# réplicas na mesma rede, sem porta de host para não conflitar com a 8081
harness/single-flight-test.sh 150
harness/single-flight-controls.sh warm 150
harness/single-flight-controls.sh down 150
```

## Resultados medidos

Suíte, com `mvn -B test -Dapi.version=1.43`: BUILD SUCCESS, 33 testes, 0 falhas.

| Módulo | Testes | Falhas | Erros | Skipped |
|---|---|---|---|---|
| `verety-lancamentos` | 11 | 0 | 0 | 0 |
| `verety-consolidado` | 22 | 0 | 0 | 0 |

Por classe: `LancamentoServiceIT` 5, `Uuid5Test` 3, `OutboxRelayTest` 1, `OutboxReservaIT` 2,
`LedgerRollupIT` 5, `ConcorrenciaRollupIT` 2, `SaldoQueryReadThroughIT` 3, `RabbitTopologyArgsTest` 4,
`LancamentoCriadoListenerTest` 3, `ErroEventoTaxonomiaTest` 5.

Com a stack viva:

| Prova | Resultado |
|---|---|
| Régua ≤5% no consolidado (50 req/s por 60 s) | 3000 req @ 50 req/s, perda 0.00%, p95 5.18 ms |
| Invariante DISTINCT δ=0 ponta a ponta (N=3000) | `processed_event` 3001=3001, `saldo` 3010.00, δ_distinct=0, δ_saldo-ledger=0.00 |
| Single-flight distribuído (3 réplicas, 150 GET cold) | 1 SELECT no PG (warm=0, redis-down=150) |

Tudo isso é resultado local, `PROXY LOCAL, semântica`: não prova capacidade nem HA do Alvo
(Aurora/ElastiCache/Amazon MQ).

## Pendências antes de produção

1. O segredo JWT é placeholder de harness. O compose traz
   `VERETY_JWT_HMAC_SECRET: "harness-placeholder-do-not-use-in-prod-..."` (`docker-compose.yml:99,147`)
   e o `JwtVerifier` recusa HS256 por construção (`JwtVerifier.java:127-129`), então sem segredo real
   a API fica inutilizável em produção. Ação: provisionar Cognito/JWKS (RS256) ou Secret gerenciado.
   A var canônica continua sendo `VERETY_JWT_HMAC_SECRET`, com nome e valor alinhados ao
   `harness/mint-token.py`.
2. O compose não escala. `docker compose --scale consolidado=2` falha com
   `Bind for 0.0.0.0:8081 failed: port is already allocated`. O fan-out exige LB; aqui medi com
   réplicas manuais na rede `verety_verety`, sem porta de host.

`harness/mint-token.py` cunha o JWT HS256 lendo `VERETY_JWT_HMAC_SECRET` (default = placeholder do
compose base). Ele não substitui o compose canônico.
