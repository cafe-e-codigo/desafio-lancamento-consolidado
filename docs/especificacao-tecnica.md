# Especificação Técnica: Verety v1

Meu critério: o desafio, no PDF, prevalece. O 5% de perda é literal do PDF e é contado como requisições não consolidadas no consolidado a 50 requisições por segundo. A moldura é mono-comerciante, em Java, sem frontend, e a infraestrutura como código fica só como documentação.

## 1. Visão

O Verety dá ao comerciante o controle do fluxo de caixa diário: registrar lançamentos de débito e crédito e consultar o saldo diário consolidado. O desafio exige dois serviços, o de controle de lançamentos e o do consolidado diário, com dois requisitos não funcionais que mandam na arquitetura:

- RNF#1: o serviço de lançamentos não pode ficar indisponível se o consolidado cair.
- RNF#2: em picos o consolidado recebe 50 requisições por segundo com no máximo 5% de perda de requisições.

A resposta arquitetural é CQRS com segregação física entre escrita e leitura, mais mensageria assíncrona (ADR-001 e ADR-002), com as quatro correções canônicas: outbox com relay, uma instrução SQL `ON CONFLICT` com Postgres como verdade, Redis com read-through e single-flight, e quorum com retry e taxonomia de erro. O meu desenho inicial sobrevive como estrutura e aqui é endurecido como especificação.

> Escopo da moldura: dois serviços Java, RabbitMQ, Postgres, Redis, ambiente local em docker-compose e topologia em .drawio. Fora: frontend, infraestrutura como código, multi-inquilino e AWS real em execução.

## 2. Domínios e Contextos (DDD)

São dois contextos delimitados, um por serviço do desafio, e a fronteira é o schema (ADR-002):

| Contexto | Responsabilidade | Schema | Agregado |
|---|---|---|---|
| Lançamentos (escrita) | Registrar débito e crédito; publicar evento | `lancamentos` | `Lancamento` e `outbox` |
| Consolidado (leitura) | Projetar o saldo diário; servir a consulta | `consolidado` | `SaldoDiario` e o ledger `processed_event`, `descarte_logado`, `rollup_controle` |

- A integração é por evento `LancamentoCriado`, e nunca por tabela compartilhada, com zero FK entre schemas (ADR-002).
- A linguagem ubíqua usa `lancamento`, `tipo` (CREDITO ou DEBITO), `valor` em `numeric(18,2)`, o dia de negócio no transporte e a coluna `data` no banco, o ledger `processed_event` com `valor_signed`, o `saldo_diario`, o `event_id` e o `outbox`.
- O ledger é único: no read-side o marcador de deduplicação e o incremento são a mesma linha, com `consolidado.processed_event(event_id, data, valor_signed)` e chave `(event_id, data)`. Não existe tabela de incremento separada, e o `schema.sql` é o executável.

## 3. Mapa de Capacidades de Negócio (PDF obrigatório #1)

Capacidade de negócio é o que o negócio faz, de forma estável, e não o componente que a executa. São três capacidades, cada uma com domínio, valor e o componente que a materializa:

| # | Capacidade de negócio | Domínio | Valor entregue ao comerciante | RF que a suporta | Componente |
|---|---|---|---|---|---|
| C1 | Registrar movimento de caixa | Lançamentos | Consolida débitos e créditos com segurança e sem perda, mesmo com o lado de leitura fora do ar (RNF#1) | RF01, RF03 | Serviço de Lançamentos com Outbox |
| C2 | Consolidar o dia | Consolidado | Fecha o saldo do dia de forma idempotente e determinística, pronto para decisão | RF03, RF04 | Serviço de Consolidado com rollup |
| C3 | Consultar o saldo diário | Consolidado | Lê o saldo do dia e de dias passados em tempo de resposta de cache, com contrato claro | RF04 | Serviço de Consolidado com Redis e `GET ?data=` |

Sub-capacidades, como refinamento:

- C1 vai de validar schema, valor e tipo a persistir em ACID, publicar no outbox e idempotir a entrada pelo `Idempotency-Key`.
- C2 vai de consumir em at-least-once a deduplicar no `processed_event`, agregar em append-only com rollup e fechar o dia por watermark (ADR-012).
- C3 vai de servir do cache com read-through e single-flight a consultar por `?data=` e sinalizar se o dia está pronto ou em curso pelo `status`.

> A cadeia de valor é C1 na entrada, C2 no fechamento e C3 na leitura e na decisão. É o mapa de domínio para capacidade para valor que o desafio pede, distinto do desenho de componentes.

## 4. Requisitos Funcionais (RF)

| ID | Requisito | Contrato | Correção aplicada |
|---|---|---|---|
| RF01 | Registrar lançamento de débito e crédito | `POST /api/v1/lancamentos` responde 201 com Location | 201 no COMMIT (ADR-003) e `Idempotency-Key` obrigatório (ADR-005) |
| RF02 | Dados monetários exatos | `numeric(18,2)` | nunca float nem `INCRBYFLOAT` (ADR-004) |
| RF03 | Consolidação assíncrona do saldo | evento `LancamentoCriado` para o consolidado | outbox e fila (ADR-003 e ADR-007) |
| RF04 | Consultar o saldo diário consolidado | `GET /api/v1/consolidado/{comerciante_id}?data=YYYY-MM-DD` | `?data=` e `status` de pronto ou em curso (ADR-012) |

- Entre RF03 e RF04 havia uma contradição no desenho inicial, que dizia RF04 em tempo real e imperceptível. Isso não é mensurável e contraria o modelo assíncrono. Aqui RF04 é eventual, com um bound verificado (ADR-003): o alvo de lag p95 é de no máximo 2 s, condicionado a que a taxa de dreno seja maior ou igual à de chegada, e isso é verificado pelo teste de carga, não afirmado.
- O `Idempotency-Key` é cabeçalho do chamador e vira um `event_id` determinístico (ADR-005), então um retry do cliente não duplica crédito.

## 5. Requisitos Não Funcionais (RNF)

| ID | RNF (do desafio ou meus) | Meta | Situação |
|---|---|---|---|
| RNF#1 | Lançamentos imune à queda do consolidado | POST responde 201 com o consolidado e o broker fora | satisfeito por construção com o outbox (ADR-003); prova de carga pendente |
| RNF#2 | 50 requisições por segundo com no máximo 5% de perda | literal do desafio, com perda no consolidado a 50 requisições por segundo | prova pendente, pelo teste de carga; o denominador é a consolidação |
| RNF#3 | Desacoplamento real entre escrita e leitura | dois serviços, integração por evento | satisfeito por construção (ADR-001 e ADR-002) |
| RF04 e SLO | Bound de lag de propagação | p95 de no máximo 2 s, condicional | pendente, verificado pelo teste de carga (ADR-003) |

> O 5% é literal e não reescrito. O desenho inicial releu a perda como throttling de borda, com 503. Meu critério é que a perda são as requisições não consolidadas, na consolidação e no ingresso, a 50 requisições por segundo. A meta de zero perda com throttling de borda fica rebaixada a meta interna, e não é critério de aceite. O 5% não é deste documento reescrever; ele é verificado com o invariante de contagem.

> Números que o desenho inicial afirmava e que eu rebaixo: o SLA de 99,99%, sem indicador e sem fonte, o cache hit de 100%, que é impossível por causa do primeiro acesso e da eviction, e o total de custo em torno de US$230, sem região, sem dia e sem licença. Ver os ADR-009 e ADR-013.

## 6. Arquitetura Alvo

O padrão é microsserviços com CQRS e mensageria assíncrona (ADR-001), com outbox na escrita (ADR-003) e Postgres como fonte de verdade (ADR-004). Nenhum nó além dos dois serviços e dos dados, que é o meu viés de arquitetura.

```
[Comerciante]
     │ HTTPS
     ▼
[WAF + ALB]  (borda; quota e throttle = meta interna)
     │
     ├──────────────▶ [Serviço de Lançamentos (Java)] ──▶ [Aurora PostgreSQL]
     │                    │  (tx: lancamento + outbox)        schema: lancamentos
     │                    │                                  (tabela outbox)
     │                    └── relay @Scheduled (SKIP LOCKED) ──▶ [Amazon MQ / RabbitMQ]
     │                                                             exchange → consolidado-queue (+retry +DLQ)
     └──────────────▶ [Serviço de Consolidado (Java)] ◀── consome evento
                          │  tx: processed_event ON CONFLICT (event_id, data)  [ledger]
                          │  rollup → saldo_diario (SÓ o rollup escreve o saldo)
                          └──▶ [Aurora PostgreSQL] schema: consolidado
                          └──▶ [ElastiCache Redis] cache derivado (read-through + single-flight)
```

- No caminho de escrita, o POST valida, faz `BEGIN`, insere `lancamento` e `outbox` e faz `COMMIT`, e só então responde 201. O broker fica fora do caminho da requisição.
- O relay é interno ao serviço de lançamentos, usa `FOR UPDATE SKIP LOCKED` e confirma por `delivery_tag` (ADR-003).
- No caminho de leitura, o consumidor consome o evento e faz uma instrução SQL `INSERT ... processed_event ON CONFLICT (event_id, data) DO NOTHING`, com o ledger durável onde a deduplicação e o incremento ficam na mesma linha, em `valor_signed`. O ack vem depois do COMMIT, e o rollup em micro-batch faz um UPDATE por `{comerciante_id, data}` por janela e grava o `saldo_diario`. O `saldo_diario` nunca é escrito por evento; o update por evento é o anti-padrão que o rollup elimina, e era ele a causa da contenção na linha quente.
- No GET, lê-se o Redis e, no miss, o read-through vai ao `saldo_diario` com single-flight e responde com o `status`.

As correções canônicas estão todas na especificação:

1. Outbox como tabela do Postgres mais relay com `SKIP LOCKED`, e 201 no COMMIT (ADR-003).
2. Uma instrução SQL com `ON CONFLICT` para a deduplicação durável, mais `numeric(18,2)`, com Postgres como verdade e Redis com read-through (ADR-004).
3. Mensageria com quorum, retry de TTL e DLX e taxonomia de erro, com `mandatory` e `basic.return`, `delivery_mode=2`, `overflow=reject-publish` e `dead-letter-strategy=at-least-once` (ADR-007).
4. Equivalência entre local e alvo como prova de semântica, nunca de capacidade ou alta disponibilidade.

## 7. Fronteiras (CQRS) e Regras

| Fronteira | Regra |
|---|---|
| Serviço com serviço | só evento pela fila, sem chamada síncrona no caminho de escrita (ADR-002) |
| Contexto com contexto nos dados | schemas `lancamentos` e `consolidado`, com zero FK entre schemas (ADR-002) |
| Read model | uma única unidade de escrita, o Postgres, e o Redis como derivado (ADR-004) |
| Idempotência | ledger `processed_event` com `PK (event_id, data)`, marcador e efeito na mesma transação (ADR-004) |
| Integridade entre os lados | eventual, verificada pelo invariante de contagem |
| Transição com o legado | por outbox e ACL, nunca dual-write direto (ADR-011) |

A direção no desenho fica corrigida: o serviço aponta para o banco, porque é o serviço que atualiza. Isso corrige a aresta invertida do desenho inicial, em que o banco parecia iniciar a atualização, e não há frontend.

## 8. Justificativa de Stack (Java) (PDF obrigatório #4)

| Decisão | Escolha | Alternativas consideradas | Por quê |
|---|---|---|---|
| Linguagem | Java LTS | C# e .NET, Node, Python | minha moldura, tipagem forte e ecossistema maduro para concorrência e transações, com Spring Boot, JDBC e o cliente AMQP, que casa com ACID e idempotência |
| Runtime | JVM em containers | Lambda | consumo contínuo de fila e relay agendado, e a JVM quente é melhor que o cold start de função para consumidor |
| Web | Spring Boot embarcado | Micronaut, Quarkus | maturidade, testes com JUnit e integração com AMQP e JDBC |
| Mensageria | RabbitMQ em AMQP | Kafka, SQS, SNS | minha escolha, com event-driven declarado; o AMQP dá rotas, DLQ e ack por mensagem, e 50 requisições por segundo não justificam Kafka |
| Banco | Amazon Aurora PostgreSQL | RDS Postgres, DynamoDB | um nome único por camada, com ACID e `ON CONFLICT` para a idempotência (ADR-004) |
| Cache | ElastiCache for Redis | sem cache | read-side acelerado, como derivado e não como verdade (ADR-004) |
| Infraestrutura como código | só documentação | CDK em código | a moldura é sem código de infraestrutura, e o desafio não exige isso |

> O desenho inicial citava C# e .NET no texto e .NET ou Java no diagrama, o que era ambíguo. Esta especificação fixa Java, que é a minha decisão, e usa um único nome por camada, que são Aurora PostgreSQL, Amazon MQ for RabbitMQ e ElastiCache for Redis.

## 9. Justificativa do Tipo de Arquitetura (PDF obrigatório #4)

A comparação completa, com opções e trade-offs, está no ADR-001. Aqui vai o resumo.

- Microsserviços com CQRS são escolhidos pelos requisitos, e não por moda. O RNF#1 exige segregação física entre escrita e leitura, e o RNF#2 exige escala assimétrica, porque só a leitura escala. O monolito modular não isola o RNF#1, e o SOA com ESB centraliza o acoplamento, o que é anti-padrão para dois serviços.
- O trade-off assumido é consistência eventual e complexidade distribuída, com outbox, idempotência e DLQ, em troca de desacoplamento físico e escala independente. Esse custo é pago pelas correções canônicas, sem mais nós.

## 10. Rastreabilidade do Desafio para Esta Especificação

| Obrigatório ou diferencial do desafio | Onde é atendido |
|---|---|
| Mapeamento de domínios e capacidades de negócio | seções 2 e 3 |
| Refinamento de requisitos funcionais e não funcionais | seções 4 e 5 |
| Desenho da arquitetura alvo, completo | seção 6 e a topologia em `topologia/c4-n1.drawio`, `c4-n2.drawio` e `c4-n3.drawio` |
| Justificativa de ferramentas, tecnologias e tipo de arquitetura | seções 8 e 9 e o ADR-001 |
| Testes | a pasta `harness/`, com carga e caos, e a verificação da seção 11 |
| Readme, com como funciona e como rodar local | `README.md` |
| Toda a documentação no repositório | a lista canônica de documentos |
| Transição, que é diferencial | ADR-011 |
| Custos de infraestrutura e licenças, que é diferencial | ADR-013 |
| Observabilidade, que é diferencial | ADR-009 |
| Segurança para consumo e integração, que é diferencial | ADR-008 |

## 11. Verificação

- Os dois serviços e os testes de unidade e de integração já rodam localmente, com o apoio de teste em `harness/`. O que ainda falta é a prova de carga e de caos a 50 requisições por segundo, e o resultado local vale como semântica, nunca como capacidade.
- Quem escreve não valida: os vetores de teste e a execução que os roda são papéis separados.
- O invariante central é o de contagem:

```
N(aceitos DISTINTOS por Idempotency-Key)
  == outbox(PENDING+FAILED) + COUNT(DISTINCT event_id) + DLQ + descartes_logados
E: saldo_diario.valor == SUM(processed_event.valor_signed)
```

- O que muda o veredito para confiável é um crash entre o marcador e o efeito, o broker fora com o POST respondendo 201 e um veneno indo para a DLQ, tudo passando com um oráculo independente.
- O RNF#2, com os 5%, só é declarado atendido depois do teste de carga a 50 requisições por segundo, com o meu denominador, e com o invariante acima.

## 12. Fontes

- O PDF do desafio, arquivo desafio-arquiteto-solucao-jun25.pdf, em todas as seções.
- Eric Evans, Domain-Driven Design (2003), e Vaughn Vernon, Implementing Domain-Driven Design (2013), sobre contextos, capacidades e fronteiras.
- Simon Brown, C4 model (c4model.com), nos níveis 1 a 3.
- Martin Fowler, CQRS, StranglerFigApplication e MonolithFirst.
- Chris Richardson, microservices.io, sobre outbox, consumidor idempotente e banco por serviço.
- Google SRE Book, sobre SLI e SLO, overload e alarme sem ação.
- AWS Well-Architected, nos pilares de Reliability, Security e Cost, e o 12-Factor App.
- Michael Nygard, Documenting Architecture Decisions (2011).
- Martin Kleppmann, Designing Data-Intensive Applications (2017), e Akidau e outros, sobre event-time e watermark.
