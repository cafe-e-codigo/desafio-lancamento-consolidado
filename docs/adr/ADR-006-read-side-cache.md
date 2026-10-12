# ADR-006: read-side com Postgres como fonte de verdade, Redis read-through e single-flight

Status: Aceito

Relaciona os ADR-004 (idempotência e append-only), ADR-009 (observabilidade) e ADR-010 (retenção e pool). A moldura é mono-comerciante, com dois serviços Java, Postgres, Redis ElastiCache e 50 requisições por segundo no consolidado, com no máximo 5% de perda.

## Contexto

O desenho diz que o GET lê diretamente do ElastiCache e que a escrita atualiza de forma atômica a chave de saldo no Redis e a base histórica, ou seja, dois armazenamentos com a palavra atômico e sem 2PC, o que é dual-write. A base relacional aparece só como read replica e cold store, e não há caminho de miss. Ficou fixado que staleness não é miss, e que o read-through só age no miss, e que as promessas de 100% de cache hit e de SLA de 99,99% eram afirmações.

As correções canônicas abriram três superfícies novas neste eixo.

- Efeito de manada: o `cache.t4g.micro` é nó único, então em failover, restart ou `FLUSHALL` todas as chaves somem, e o primeiro acesso de cada dia, que é uma chave nova por construção, dá N misses simultâneos e joga o pico inteiro no Postgres.
- Contenção na linha quente: no mono-comerciante, todos os writes de uma data batem na mesma linha de `saldo_diario`, então o `ON CONFLICT DO UPDATE` serializa, com row-lock e speculative-insert token, e gera uma tupla morta por evento.
- Durabilidade não é cache: usar o mesmo Redis como cache e como idempotência cria dependência circular, porque o cache cair derruba a idempotência. Isso foi resolvido no ADR-004, com a idempotência no Postgres, e este ADR resolve o read-side.

Os requisitos aplicáveis são o RF04, que pede saldo em tempo real ou com latência imperceptível e que no desenho é só uma afirmação, e o critério de 50 requisições por segundo com no máximo 5% de perda.

## Opções consideradas

**Opção A, o GET lê direto do Redis e o Redis mais a base relacional atualizam de forma atômica.**
- A favor: latência baixa no hit.
- Contra: não há caminho de miss, então um failover dá erro; o dual-write é uma afirmação; o Redis vira fonte de verdade; e 100% de hit é impossível. Rejeitada.

**Opção B, read-through ingênuo de Redis para Postgres, com só o que o ADR-004 deixa.**
- A favor: fecha o miss sem caminho e o Redis vira derivado e descartável.
- Contra: não fecha o efeito de manada, porque no cold key ou no failover N GETs vão em bando ao Postgres, e a 50 requisições por segundo o pico de leitura estoura o pool do Aurora Serverless v2, com ramp de ACU, e dá 5xx no consolidado, que é o oposto do critério. Rejeitada sozinha.

**Opção C, read-through com single-flight e coalescing, TTL com jitter, invalidação pós-commit e append-only com rollup (escolhida).**
- A favor: uma única unidade de escrita, o Postgres; só uma requisição reconstrói a chave por vez; TTL desalinhado; a leitura nunca serve um valor mais velho que o bound; e a escrita não serializa a linha de saldo.
- Contra: exige lock curto por chave, e não lock distribuído, mais um job de rollup, e TTL e invalidação como contrato; a leitura passa a ter o lag do rollup no caminho.

**Opção D, Redis Cluster ou cache distribuído dedicado, com write-behind.**
- A favor: escala horizontal.
- Contra: é um nó novo com custo, e o write-behind reintroduz o dual-write que a decisão já tinha matado, o que é excesso para mono-comerciante. Rejeitada para o escopo.

## Decisão

A fonte de verdade é o Postgres, e o Redis é uma projeção derivada, descartável e reconstruível. São seis peças.

1. Uma unidade de escrita. O consumidor faz um INSERT append-only idempotente no ledger:

```sql
INSERT INTO consolidado.processed_event (event_id, comerciante_id, data, valor_signed)
VALUES ($1, $2, $3, $4)
ON CONFLICT (event_id, data) DO NOTHING;   -- a chave primária inclui a partição; o ack no broker só após o COMMIT
```

O `saldo_diario` não é atualizado no caminho da requisição, o que evita a contenção na linha quente.

2. Rollup em micro-batch, do append-only para a projeção. Um job interno agrega a janela por `{comerciante_id, data}` e faz um UPDATE por grupo, e não por evento:

```sql
INSERT INTO consolidado.saldo_diario AS s (comerciante_id, data, valor, atualizado_em)
SELECT comerciante_id, data, sum(valor_signed), now()
  FROM consolidado.processed_event
 WHERE processado_em > (SELECT ultimo_processado_em FROM consolidado.rollup_controle)
 GROUP BY comerciante_id, data
ON CONFLICT (comerciante_id, data) DO UPDATE
  SET valor = s.valor + EXCLUDED.valor, atualizado_em = now();
```

O `rollup_controle` é atualizado na mesma transação do rollup, com a marca d'água em `max(processado_em)` do lote.

3. Read-through com single-flight. No GET:

```sql
SELECT valor FROM consolidado.saldo_diario
 WHERE comerciante_id = $1 AND data = $2;   -- no miss, lê do Postgres, grava no Redis com TTL e responde
```

O single-flight é por chave: a primeira requisição que dá miss toma um lock curto com `SET saldo:{m}:{d}:lock <token> NX PX 2000`, e as demais esperam, com um busy-wait curto e backoff com jitter, e releem o valor recém-populado. Não é RedLock, porque o lock é por chave de saldo e não uma exclusão distribuída de recurso.

4. TTL com jitter e invalidação pós-commit. O TTL base, por exemplo 300 s, leva jitter, por exemplo de 10% a 20%, para desalinhar as expirações e evitar que elas aconteçam em bloco. O consumidor, depois do COMMIT do insert no ledger, apaga a chave `saldo:{m}:{d}`, e o próximo GET a repovoa. É uma invalidação eventual e barata, porque a verdade está no Postgres.

5. Chave de calendário explícita. O campo `data` é data de negócio, com o fuso definido no ADR-012, e não o `now()` do servidor, o que evita erro de corte de dia.

6. Reconciliação de runtime. Um job periódico independente da aplicação:

```sql
SELECT s.comerciante_id, s.data, s.valor, COALESCE(SUM(p.valor_signed),0) AS recomputado
  FROM consolidado.saldo_diario s
  LEFT JOIN consolidado.processed_event p
         ON p.comerciante_id = s.comerciante_id AND p.data = s.data
 GROUP BY s.comerciante_id, s.data, s.valor
HAVING s.valor <> COALESCE(SUM(p.valor_signed),0);   -- deve retornar 0 linhas
```

Se o delta for diferente de zero, dispara o alarme `Verety/Consolidado ReconciliacaoDelta` (ADR-009).

O que a medição local prova é o comportamento: o `ON CONFLICT`, o `numeric`, o MVCC e o single-flight. O que ela não prova é a eviction, o failover e a latência do ElastiCache, nem a capacidade do Aurora Serverless v2. O resultado aqui é semântica de execução, nunca capacidade de produção.

## Consequências

Positivas:
- O read-side fica coerente, com uma unidade de escrita no Postgres e o Redis como cache reconstruível, o que mata o dual-write e o miss sem caminho.
- O efeito de manada fica contido por construção, com o single-flight, e o pico de cold key não chega ao Postgres.
- A contenção na linha quente fecha com append-only e rollup, com um UPDATE por `{comerciante, data}` por janela, e não por evento.
- O invariante monetário passa a ser verificável em runtime, comparando `saldo_diario` com `SUM(processed_event.valor_signed)`, e não só no teste.

Negativas e mitigação:
- O lag de leitura é o intervalo de rollup no caminho do GET, e entra no bound de lag do ADR-003, com lag p95 de no máximo 2 s de forma condicional, verificado pelo teste de carga e não afirmado.
- O single-flight exige `SET NX PX` e backoff com jitter, que são disciplina; se o lock expirar sob carga, o miss é reenviado, e não dá erro.
- TTL e invalidação são política, não código mágico, e a defasagem residual até o delete pós-commit é aceita e declarada, porque não é miss.
- São necessários o job de rollup e o job de reconciliação como componentes observáveis; sem eles, os alarmes não existem.

## Fontes

- PostgreSQL, na documentação oficial: `INSERT ... ON CONFLICT` e speculative insertion, MVCC, rotina de vacuum e `fillfactor`.
- Redis, na documentação oficial: key eviction e `maxmemory-policy`, client-side caching e `SET NX PX`.
- Martin Kleppmann, How to do distributed locking (2016).
- Google SRE Book: Handling Overload e a ideia de manada, e Service Level Objectives.
- microservices.io, de Chris Richardson: o problema do dual-write e o consumidor idempotente.
- AWS: Aurora Serverless v2, com ACU, e ElastiCache for Redis, com a distinção entre nó único e replication group.
- Michael Nygard, Documenting Architecture Decisions (2011).
