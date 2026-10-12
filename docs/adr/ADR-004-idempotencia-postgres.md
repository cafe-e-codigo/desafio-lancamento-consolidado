# ADR-004: idempotência durável com uma instrução SQL (ON CONFLICT) e Postgres como fonte de verdade

Status: Aceito

Relaciona os ADR-003 (relay at-least-once), ADR-005 (contrato e event_id) e ADR-006 (dados e retenção).

## Contexto

O desenho faz a idempotência com `SETNX idempotency:{transaction_id}` e TTL de 48 h e, separadamente, `INCRBYFLOAT saldo:{comerciante_id}:{data}`. As consequências que eu quero registrar são estas.

- Um crash entre os dois comandos grava a chave de deduplicação e não incrementa o saldo. Na reentrega, o `SETNX` retorna 0 e a mensagem é ignorada com ack, o que é perda permanente e silenciosa, contra a promessa de perda zero.
- O TTL de 48 h, combinado com o reprocesso de DLQ sem prazo, deixa a chave expirar e permite a dupla contagem.
- `INCRBYFLOAT` usa ponto flutuante para dinheiro, o que perde centavos.
- O próprio fluxo afirma atualizar de forma atômica a chave no Redis e a base histórica, que são dois armazenamentos, sem 2PC. É dual-write.

A moldura é mono-comerciante, o que concentra todos os writes de uma data na mesma linha de saldo, o que gera contenção na conta quente (row-lock no `saldo_diario`).

## Opções consideradas

**Opção A, `SETNX` mais `INCRBYFLOAT` no Redis.**
- A favor: rápido.
- Contra: não é atômico entre a deduplicação e o efeito; TTL e eviction quebram a deduplicação; float perde centavos; e o Redis vira fonte de verdade. Rejeitada.

**Opção B, um script Lua `EVAL` no Redis, com NX e incremento juntos.**
- A favor: atômico por chave.
- Contra: mantém o Redis como armazenamento de deduplicação e de verdade, e uma política como `allkeys-lru` pode derrubar a chave e causar dupla contagem; não resolve a contenção nem o dinheiro em float; e usar o mesmo Redis do read-side e da deduplicação cria dependência circular, porque o cache cair derruba a idempotência. Rejeitada como armazenamento de verdade.

**Opção C, uma instrução SQL no Postgres com `ON CONFLICT` (escolhida).**
- A favor: o marcador durável e o efeito ficam na mesma transação, então um crash entre eles é impossível; `numeric(18,2)` é exato; a idempotência vive onde o dado vive; e a constraint resolve sem lock distribuído.
- Contra: concentra tudo no Postgres, o que exige tratar a conta quente.

**Opção D, RedLock ou 2PC distribuído.**
- A favor: exclusão explícita.
- Contra: desnecessário, porque uma constraint `UNIQUE` resolve, e não é recomendado pela literatura (Kleppmann, How to do distributed locking, 2016). Rejeitada.

## Decisão

A fonte de verdade é o Postgres. O Redis é cache derivado, com read-through e single-flight. A idempotência cabe em um comando SQL:

```sql
-- ledger append-only: o marcador de deduplicação E o incremento são a MESMA linha (event_id, data)
INSERT INTO consolidado.processed_event (event_id, comerciante_id, data, valor_signed)
VALUES ($1, $2, $3, $4)
ON CONFLICT (event_id, data) DO NOTHING;   -- reentrega = no-op (0 linhas); 1 vez por evento
COMMIT;                                     -- o ack no broker só acontece após o COMMIT
```

O ledger é único, definido em `schema.sql`. O DDL canônico usa uma só tabela, `consolidado.processed_event(event_id, comerciante_id, data, valor_signed, processado_em)`, que funde o marcador de deduplicação e o incremento append-only. Não existe tabela separada de incremento. A chave primária e o `ON CONFLICT` são `(event_id, data)`, porque a chave de partição (`data`) precisa entrar na chave única, como exige um `UNIQUE` em tabela particionada no Postgres, e `event_id` sozinho não é indexável como `UNIQUE`.

- Dinheiro é `numeric(18,2)` em todas as colunas, e nunca float nem `INCRBYFLOAT` (RF02).
- Para a contenção na conta quente, em vez de `UPDATE saldo_diario` a cada evento, com row-lock e tuplas mortas, a escrita vai para o ledger `processed_event` append-only, onde inserts concorrentes não serializam na linha de saldo. O `saldo_diario` é atualizado por rollup em micro-batch:

```sql
INSERT INTO consolidado.saldo_diario AS s (comerciante_id, data, valor, atualizado_em)
SELECT comerciante_id, data, sum(valor_signed), now()
  FROM consolidado.processed_event
 WHERE processado_em > (SELECT ultimo_processado_em FROM consolidado.rollup_controle)
 GROUP BY comerciante_id, data
ON CONFLICT (comerciante_id, data) DO UPDATE
  SET valor = s.valor + EXCLUDED.valor, atualizado_em = now();
```

Assim há um UPDATE por `{comerciante_id, data}` por janela, e não por evento, o que elimina a pressão de `1/hold_time` e o bloat. O `saldo_diario` nunca é escrito por evento, só pelo rollup.

- A leitura de `GET /api/v1/consolidado/{comerciante_id}?data=` lê do Redis, e no miss faz read-through para o `saldo_diario`, com single-flight para evitar o efeito de manada (ADR-006).
- Para a compatibilidade futura com multi-inquilino, embora a moldura seja mono-comerciante, `comerciante_id` é coluna de primeira classe em todas as tabelas e chaves, com `processed_event` indexando `comerciante_id` e `saldo_diario` tendo `(comerciante_id, data)` como chave. Habilitar multi-tenant vira migração de dados, não de esquema.
- A reconciliação compara `saldo_diario.valor` com `SUM(processed_event.valor_signed)` por SQL independente, no invariante de contagem.

## Consequências

Positivas:
- Sem perda e sem dupla contagem: marcador e efeito são atômicos, e a reentrega é no-op.
- O dinheiro fica exato, com `numeric`, sem float.
- Não há dual-write entre Redis e a base: há uma única unidade de escrita, o Postgres.
- A conta quente escala com append-only e rollup, sem serializar a linha de saldo.
- A deduplicação é durável no Postgres, na chave `(event_id, data)`, e funciona mesmo com o Redis fora.

Negativas:
- O GET passa a ter o intervalo de rollup no caminho, o que entra no bound de lag do ADR-003.
- O ledger append-only cresce indefinidamente, o que pede retenção e archival por partição, com o rollup como job observável.
- Reprocessar exige rodar o rollup, e a leitura pode ficar levemente desatualizada entre janelas, o que a single-flight e a invalidação mitigam.

A verificação é esta: um crash entre o marcador e o efeito deixa o saldo correto uma única vez; e a contenção de uma conta no pico de 50 requisições por segundo mantém a perda em no máximo 5% no consolidado. A decisão só passa a valer com essas saídas.
