# Verety: Controle de Lançamentos e Saldo Diário Consolidado

Dois serviços para o comerciante registrar lançamentos de débito/crédito e consultar o saldo diário consolidado, desenhados para resistir à queda do lado de leitura e sustentar picos de 50 req/s.

## Sumário

1. [O que é (e por quê)](#1-o-que-é-e-por-quê)
2. [Como funciona](#2-como-funciona)
3. [Estrutura do repositório](#3-estrutura-do-repositório)
4. [Pré-requisitos](#4-pré-requisitos)
5. [Instalar e subir](#5-instalar-e-subir)
6. [Usar a API](#6-usar-a-api)
7. [Testar](#7-testar)
8. [Configuração](#8-configuração)
9. [Parar e limpar](#9-parar-e-limpar)
10. [Solução de problemas](#10-solução-de-problemas)
11. [Documentação](#11-documentação)
12. [Licença](#12-licença)

## 1. O que é (e por quê)

O Verety dá ao comerciante o controle do fluxo de caixa diário em duas capacidades: registrar lançamentos de débito e crédito e consultar o saldo diário consolidado. Duas restrições não funcionais mandam na arquitetura:

- **RNF#1:** o serviço de lançamentos não pode ficar indisponível se o consolidado cair.
- **RNF#2:** sob pico, o consolidado precisa absorver 50 req/s com no máximo 5% de perda de requisições.

Esses dois requisitos são a razão pela qual escolhi CQRS com separação física entre escrita e leitura (dois serviços, dois processos) em vez de um monólito modular: só processos separados garantem que a queda de um lado não derrube o outro (RNF#1), e só assim a leitura escala de forma independente (RNF#2). Sobre essa base, quatro decisões sustentam o comportamento:

- Outbox transacional + relay. O `POST` de lançamento grava o lançamento e o evento na mesma transação do Postgres e responde 201 no commit. O broker fica fora do caminho da requisição: se o RabbitMQ ou o consolidado estiverem fora, nada se perde: os eventos ficam pendentes no banco e um relay interno publica depois. É o que torna o RNF#1 verdadeiro.
- Postgres é a fonte de verdade. A idempotência vive onde o dado vive: o ledger `processed_event` tem chave `(event_id, data)`, então deduplicação e incremento acontecem na mesma linha, sem lock distribuído e sem dupla contagem. O Redis não é verdade.
- Redis é cache derivado. Ele acelera a leitura do saldo (read-through com single-flight), mas um `miss` sempre volta ao Postgres. A verdade nunca depende do cache.
- Consolidação assíncrona. O saldo do dia é projetado por um rollup em micro-lotes no serviço de consolidado: o saldo não é escrito evento a evento (isso concentraria todo o movimento do dia na mesma linha e mataria o desempenho). Como contrapartida, a leitura do consolidado é eventualmente consistente: logo após um lançamento, o saldo pode levar alguns instantes para refletir.

> Escopo: dois serviços Java, RabbitMQ, Postgres e Redis, rodando localmente via `docker-compose`; topologia em `.drawio`. Fora de escopo: frontend, IaC em código, multi-tenant e provisionamento real de nuvem.

## 2. Como funciona

São dois serviços, integrados somente por evento, nunca por chamada síncrona nem por tabela compartilhada:

| Serviço | Papel | O que faz |
|---|---|---|
| lancamentos (escrita) | Recebe comandos | `POST /api/v1/lancamentos` grava o lançamento e o evento na mesma transação e responde 201 no commit. Um relay interno varre a tabela de outbox e publica os eventos no RabbitMQ. |
| consolidado (leitura) | Projeta e serve | Consome o evento, grava no ledger idempotente e roda o rollup para o saldo diário. `GET /api/v1/consolidado/{comerciante_id}?data=YYYY-MM-DD` responde pelo cache read-through. |

### Fluxo de um lançamento, ponta a ponta

1. O comerciante envia o lançamento (`POST`), com o cabeçalho `Idempotency-Key`.
2. O serviço de lançamentos valida, grava lançamento + evento na mesma transação e responde 201.
3. O relay lê os eventos pendentes (`FOR UPDATE SKIP LOCKED`) e publica no RabbitMQ.
4. O serviço de consolidado consome o evento e grava no ledger `processed_event`: `INSERT … ON CONFLICT (event_id, data) DO NOTHING`, com o valor já assinado (crédito positivo, débito negativo).
5. O rollup agrega o ledger no saldo diário (`saldo_diario`), uma atualização por `{comerciante, dia}` por janela.
6. A consulta (`GET`) lê do Redis; em caso de `miss`, busca no Postgres e repõe o cache (single-flight).

A consequência prática: a escrita é síncrona e imediata para o comerciante; a leitura do consolidado é assíncrona, daí os "alguns instantes" que citei na seção anterior.

## 3. Estrutura do repositório

```
verety/
├── common/             # biblioteca compartilhada (Money/BigDecimal, DTOs, erros no padrão RFC 9457)
├── lancamentos/        # serviço de escrita  (REST, JPA, outbox, relay, Flyway)
├── consolidado/        # serviço de leitura  (consumidor AMQP, ledger, rollup, cache, Flyway)
├── docs/               # documentação do projeto
│   ├── especificacao-tecnica.md   # domínios/capacidades, RF/RNF, arquitetura, justificativas
│   ├── adr/                       # decisões de arquitetura (ADR-001 … ADR-013)
│   ├── openapi.yaml               # contrato HTTP
│   ├── schema.sql                 # DDL canônico (executável)
│   └── topologia/                 # diagramas C4, ER, mensageria, sequência e deploy (.drawio) 
├── harness/            # apoio a testes locais (gerador de token, carga k6, invariante)
├── docker-compose.yml  # stack local completa (Postgres, RabbitMQ, Redis, 2 serviços)
├── Makefile            # atalhos de uso (test, up, demo, …)
├── pom.xml             # parent Maven (multi-módulo)
└── README.md           # este documento
```

- `common/`: o único código compartilhado entre os serviços: representação de dinheiro (`BigDecimal`), DTOs do evento e o formato padrão de erro.
- `lancamentos/` e `consolidado/`: os dois serviços, cada um com seu próprio schema e sua própria trilha de migração (Flyway).
- `docs/`: toda a documentação do projeto (especificação, ADRs, contrato, DDL e diagramas), dentro do repositório, para consulta de quem avalia.
- `harness/`: artefatos de apoio a teste local; não fazem parte do runtime dos serviços.

## 4. Pré-requisitos

Para rodar o projeto localmente, você só precisa de:

| Ferramenta | Versão sugerida | Para quê |
|---|---|---|
| Docker Engine | 24+ (com plugin `docker compose` v2) | compilar, subir a stack e executar os testes |
| Python 3 | 3.8+ | gerar o token JWT de teste (`harness/mint-token.py`) |
| curl | qualquer | os exemplos de chamada à API deste README |

Não é preciso instalar Java nem Maven na máquina: a compilação e os testes rodam dentro de containers Maven. Também não é preciso instalar banco, broker ou cache: Postgres, RabbitMQ e Redis sobem pelo `docker-compose.yml`.

Confirme o ambiente:

```bash
docker --version
docker compose version
python3 --version
```

> O Docker precisa estar em execução. Recomendo reservar alguns GB de RAM livre para a stack completa (Postgres, RabbitMQ, Redis e os dois serviços).

## 5. Instalar e subir

### 5.1 Clonar

```bash
git clone git@github.com:cafe-e-codigo/desafio-lancamento-consolidado.git verety
cd verety
```

### 5.2 Subir a stack

```bash
docker compose up --build -d
```

O `--build` compila os dois serviços dentro do Docker (imagens multi-stage): não há etapa de build no host. Na primeira execução, o download das dependências Maven pode levar alguns minutos; as execuções seguintes aproveitam o cache de camadas.

### 5.3 Conferir

```bash
docker compose ps
```

Saída esperada: 5 contêineres, Postgres, RabbitMQ e Redis em `healthy` e os dois serviços em execução. Os serviços só iniciam depois que a infraestrutura está saudável.

Endpoints após subir:

| Serviço | URL local | Health |
|---|---|---|
| lancamentos (escrita) | http://localhost:8080 | `/actuator/health` |
| consolidado (leitura) | http://localhost:8081 | `/actuator/health` |

Acompanhar os logs:

```bash
docker compose logs -f lancamentos consolidado
```

## 6. Usar a API

A API é fail-closed: toda rota de negócio exige um JWT `Bearer`. A ausência do cabeçalho `Authorization` responde 403; um token com assinatura inválida responde 401. As únicas rotas públicas são `/actuator/health/`, `/actuator/info` e `/actuator/metrics/`.

Escopos exigidos:

| Rota | Escopo |
|---|---|
| `POST /api/v1/lancamentos` | `lancamentos:write` |
| `GET /api/v1/consolidado/` | `consolidado:read` |

### 6.1 Gerar um token de teste

Para desenvolvimento local, o `docker-compose.yml` provisiona um segredo HMAC de exemplo. Cunhe um token com os dois escopos:

```bash
TOKEN=$(python3 harness/mint-token.py)
```

Por padrão, o token usa o comerciante `2222…2222`, os escopos `consolidado:read lancamentos:write` e validade de 1 hora. Para outro comerciante: `python3 harness/mint-token.py <uuid-do-comerciante>`.

> O token só é aceito se for assinado com o mesmo segredo que os serviços carregam (`VERETY_JWT_HMAC_SECRET`), assinatura divergente responde 401. O valor do compose é apenas para execução local; em produção ele é substituído por Cognito/JWKS (RS256) ou um segredo gerenciado.

### 6.2 Registrar um lançamento (escrita)

```bash
MERCHANT=22222222-2222-2222-2222-222222222222
curl -i -X POST http://localhost:8080/api/v1/lancamentos \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 9f1c2b7a-0d3e-4c5f-9a10-8b6d2e4f7c11' \
  -d "{\"comerciante_id\":\"$MERCHANT\",\"tipo\":\"CREDITO\",\"valor\":\"150.00\",\"moeda\":\"BRL\",\"data_negocio\":\"2026-10-08\"}"
```

Resposta 201 Created (com cabeçalho `Location`):

```json
{
  "lancamento_id": "1",
  "event_id": "0f8f2c1e-…",
  "comerciante_id": "22222222-2222-2222-2222-222222222222",
  "tipo": "CREDITO",
  "valor": "150.00",
  "moeda": "BRL",
  "data_negocio": "2026-10-08",
  "status": "PENDING",
  "criado_em": "2026-10-08T12:00:00Z"
}
```

### Idempotência (`Idempotency-Key` é obrigatória)

| Situação | Resposta |
|---|---|
| Mesma key + mesmo corpo (replay) | 200, devolve o lançamento original, sem novo efeito |
| Mesma key + corpo diferente | 409 |
| Key ausente, ou fora de 8..128 caracteres de `[A-Za-z0-9._:-]` | 400 |

### 6.3 Consultar o saldo consolidado (leitura)

```bash
sleep 5   # a consolidação é assíncrona, aguarde antes de consultar
curl -s "http://localhost:8081/api/v1/consolidado/$MERCHANT?data=2026-10-08" \
  -H "Authorization: Bearer $TOKEN"
```

Resposta 200:

```json
{
  "comerciante_id": "22222222-2222-2222-2222-222222222222",
  "data": "2026-10-08",
  "saldo": "150.00",
  "moeda": "BRL",
  "atualizado_em": "2026-10-08T12:00:05Z",
  "status": "aberto"
}
```

> A consolidação é assíncrona. Logo após um `POST`, o saldo pode levar alguns instantes para refletir o lançamento. Se consultar imediatamente, pode receber 404. Aguarde alguns segundos e repita.

- `?data=` é opcional; o default é hoje (fuso `America/Sao_Paulo`).
- `status` é `"aberto"` para o dia corrente e `"fechado"` para dias passados.
- `comerciante_id` deve ser UUID; valor não-UUID na escrita → 422 e na leitura → 404.

### 6.4 Códigos de erro

Erros seguem RFC 9457 (`application/problem+json`):

| Status | Quando |
|---|---|
| 400 | `Idempotency-Key` ausente/inválida, JSON malformado |
| 401 | token com assinatura inválida |
| 403 | sem `Authorization: Bearer`, ou escopo insuficiente |
| 404 | sem saldo para a data consultada |
| 409 | mesma `Idempotency-Key` com corpo diferente |
| 422 | payload válido em formato, inválido no domínio (ex.: `comerciante_id` não-UUID na escrita) |

## 7. Testar

### 7.1 Suíte automatizada

```bash
make test
```

Equivalente sem `make` (não exige JDK no host):

```bash
docker run --rm -v "$PWD":/w -v verety-m2:/root/.m2 -w /w \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  maven:3.9-eclipse-temurin-21 mvn -B test -Dapi.version=1.43
```

Resultado: 33 testes, 0 falhas. Os testes de integração usam Testcontainers e sobem Postgres, RabbitMQ e Redis reais em contêineres, não é preciso ter a stack no ar para rodá-los.

O que a suíte cobre:

| Área | O que é verificado |
|---|---|
| Idempotência de escrita | replay da mesma `Idempotency-Key` → 200 sem novo efeito; corpo divergente → 409; validação de cabeçalho e domínio |
| Identificadores | `event_id = UUIDv5(ns, Idempotency-Key)`, determinístico no relay, no consumidor e no redrive |
| Outbox + relay | lançamento e evento na mesma transação; reserva concorrente (`FOR UPDATE SKIP LOCKED`); publicação e marcação de estado |
| Ledger + rollup | dedup `ON CONFLICT (event_id, data)`; uma linha por grupo; saldo assinado em `numeric(18,2)` |
| Concorrência do rollup | rollup concorrente sem dupla contagem |
| Cache de leitura | read-through + single-flight; Redis vazio/evictado volta ao Postgres |
| Mensageria | topologia (quorum, DLX, TTL de retry); ack só após o efeito; transiente × veneno |

Para rodar um teste específico:

```bash
make it T=LancamentoServiceIT
```

### 7.2 Verificação de carga: o critério `≤5%`

Com a stack no ar:

```bash
make k6
```

O comando mede a régua do desafio: 50 req/s por 30 s na superfície do consolidado, ele falha se as requisições com erro (`http_req_failed`) ou a perda no consolidado (`perda_consolidado`) atingirem 5% (o `≤5%` literal do enunciado).

Para estressar a ingestão (caminho de escrita) em vez da leitura:

```bash
SURFACE=ingest make k6
```

> A medição é local: prova o comportamento do sistema nesta máquina (`PROXY LOCAL, semântica`), não a capacidade de produção. Sob carga concorrente do host, a latência varia e o script pode sinalizar o SLO interno de `p(95) < 2 s`; o critério de aceite, porém, é a perda `≤5%`.

### 7.3 Demonstração de vários lançamentos

```bash
make demo N=10
```

O script `harness/demo-varios-lancamentos.sh` cria um comerciante novo (UUID aleatório), envia N lançamentos alternando crédito/débito, calcula a soma esperada, aguarda o rollup assíncrono e imprime o consolidado lido, que deve ser exatamente igual à soma esperada. É a forma mais rápida de ver o fluxo completo funcionando ponta a ponta.

## 8. Configuração

Toda configuração vem de variável de ambiente: os arquivos `application.yml` não carregam literais sensíveis e trazem defaults válidos para execução local.

| Grupo | Variáveis | Default local |
|---|---|---|
| Datasource | `SPRING_DATASOURCE_URL` · `_USERNAME` · `_PASSWORD` · `_POOL_MAX` | `jdbc:postgresql://postgres:5432/verety` · `verety` · `verety` · `10` |
| Migrações (Flyway) | `SPRING_FLYWAY_ENABLED` · `_SCHEMAS` · `_DEFAULT_SCHEMA` · `_TABLE` | ver nota abaixo |
| RabbitMQ | `SPRING_RABBITMQ_HOST` · `_PORT` · `_USERNAME` · `_PASSWORD` | `rabbitmq` · `5672` · `verety` · `verety` |
| Redis (leitura) | `SPRING_DATA_REDIS_HOST` · `_PORT` · `_TIMEOUT` | `redis` · `6379` · `2s` |
| Porta | `SERVER_PORT` | `8080` (escrita) · `8081` (leitura) |
| Segurança | `VERETY_JWT_ISSUER` · `_AUDIENCE` · `_ALGORITHMS` · `_SCOPE_CLAIM` · `_MERCHANT_CLAIM` · `_HMAC_SECRET` · `_PUBLIC_KEY_PEM` · `VERETY_SECURITY_TEST_BYPASS` | vazio · vazio · `HS256,RS256` · `scope` · `merchant_id` · vazio · vazio · `false` |
| Observabilidade | `VERETY_OUTBOX_LAG_WARN_SECONDS` · `VERETY_OUTBOX_TERMINAL_DOWN` · `VERETY_CONSOLIDADO_LAG_WARN_SECONDS` | `300` · `true` · `300` |

Migrações (Flyway). Cada serviço gerencia o seu próprio schema, `lancamentos` e `consolidado`, com a sua própria tabela de histórico, no mesmo banco físico. O default do serviço de leitura é `false` (para não competir com a escrita); o `docker-compose.yml` liga os dois de forma explícita (`SPRING_FLYWAY_ENABLED=true`), apontando cada um para o seu schema. Assim um não enxerga o schema do outro e não há colisão.

Perfis `security` e `observability`. Os dois serviços sobem com `SPRING_PROFILES_ACTIVE=security,observability`. Os parâmetros de segurança só valem sob o perfil (fora dele, os valores têm default seguro). `VERETY_SECURITY_TEST_BYPASS` existe apenas para testes automatizados e deve permanecer `false` fora deles.

## 9. Parar e limpar

```bash
docker compose down        # para a stack e MANTÉM os dados
docker compose down -v     # para a stack e APAGA os dados DESTE projeto
```

Escopo: o Compose nomeia os recursos com o projeto (`name: verety`). O `down -v` remove apenas os volumes deste projeto (`verety_pgdata`), não toca em volumes, contêineres, imagens ou redes de outros projetos na sua máquina, e não apaga nenhuma pasta do seu sistema de arquivos. Ele também não roda `prune` nem remove imagens.

- `down`: desliga os contêineres guardando os dados no volume `pgdata`; ao subir de novo, os lançamentos anteriores continuam lá.
- `down -v`: remove também os volumes: recomeça do zero. No próximo `up`, as migrações do Flyway são reaplicadas nos dois schemas.
- Para reconstruir as imagens (por exemplo, após mudar o código): `docker compose up -d --build`.

O `Makefile` traz o atalho:

```bash
make down
```

## 10. Solução de problemas

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| `curl: (7) Failed to connect` (código `000`) | A stack ainda está subindo, ou caiu | `make ps`; espere os 5 contêineres ficarem saudáveis; se preciso, `docker compose up -d` |
| A primeira subida demora muito | Download das dependências Maven no build | Aguarde; as execuções seguintes usam o cache de camadas |
| `GET` do consolidado retorna 404 | A consolidação é assíncrona, o saldo ainda não foi projetado | Espere alguns segundos e repita; confirme que a `data` consultada é a mesma do `data_negocio` lançado |
| 400 citando `Idempotency-Key` | Cabeçalho ausente ou fora do formato | Use de 8 a 128 caracteres de `[A-Za-z0-9._:-]` |
| 403 em toda rota de negócio | Sem `Authorization: Bearer`, ou escopo faltando | Gere o token com `python3 harness/mint-token.py` (já traz os dois escopos) |
| 401 | Token assinado com segredo diferente do serviço | Gere o token com o mesmo `VERETY_JWT_HMAC_SECRET` que o serviço carrega |
| 409 | Mesma `Idempotency-Key` reutilizada com corpo diferente | Use uma key nova para um lançamento novo |
| `address already in use` | Portas 8080/8081 já ocupadas | Libere a porta ou pare o serviço que a usa |
| Contêiner de infraestrutura em `unhealthy` | Postgres, RabbitMQ ou Redis com problema | `docker compose logs <serviço>` |
| `make test` falha com *"Could not find a valid Docker environment"* | Versão da API do Docker no host | Use `make test` (já passa `-Dapi.version`); confirme que o Docker está em execução |
| Quero recomeçar do zero | Dados de execuções anteriores | `docker compose down -v`, escopo deste projeto (ver seção 9) |
| Contêiner dos serviços sai com `Exited (1)` e o log cita *checksum mismatch* no Flyway | Você subiu a stack por cima de um banco de uma versão anterior das migrations | `docker compose down -v` e suba de novo (ver seção 9) |
| `mvn test` direto no host falha com *Permissão negada* em `target/` | Os `target/` foram criados pelos builds em contêiner (dono `root`) e o host não tem o JDK 21 | Use `make test` (Maven 3.9 e JDK 21 em contêiner) |

## 11. Documentação

Toda a documentação do projeto está em `docs/`:

| Documento | Conteúdo |
|---|---|
| `docs/especificacao-tecnica.md` | domínios e capacidades de negócio, requisitos funcionais e não funcionais, arquitetura alvo e justificativas de tecnologia e de tipo de arquitetura |
| `docs/adr/` | decisões de arquitetura (ADR-001 … ADR-013), com alternativas consideradas e trade-offs |
| `docs/openapi.yaml` | contrato HTTP da API |
| `docs/schema.sql` | DDL canônico do banco (executável) |
| `docs/topologia/` | diagramas C4 (contexto, contêineres e componentes), ER, mensageria, sequência e deploy, em `.drawio` |

## 12. Licença

MIT. O texto completo está em [LICENSE](LICENSE).
