# ADR-012: calendário, fuso e data de negócio do diário

Status: Aceito

Relaciona os ADR-002 (fronteiras), ADR-004 (saldo), ADR-007 (ordem e watermark) e ADR-011 (paridade da transição).

## Contexto

O desafio pede o saldo diário consolidado, e o desenho usa a chave `saldo:{merchant_id}:{YYYY-MM-DD}` e expõe `GET /consolidado/{merchant_id}` sem parâmetro de data. Há três lacunas.

1. Nenhum fuso declarado. O dia depende do fuso, e um lançamento às 23:30 em America/Sao_Paulo é outro dia em UTC, então o saldo do dia muda conforme o instante de processamento, o que fica indefinido nos insumos.
2. Data de negócio contra data de processamento. O evento assíncrono pode ser consumido depois da virada do dia, com outbox acumulada sob broker fora e reprocesso de DLQ. Se o dia for o de processamento, um lançamento do dia D pode cair no dia D+1, o que quebra o fechamento diário e a paridade da transição (ADR-011).
3. O GET sem `?data=` não permite consultar o fechamento de um dia específico, então o relatório de saldo diário do desafio não é recuperável para um dia passado pelo contrato atual.

## Opções consideradas

**Opção A, o dia como data de processamento, no fuso do servidor.**
- A favor: nada a declarar.
- Contra: o resultado fica não determinístico, porque depende de onde e quando roda; o lançamento tardio cai no dia errado; e a paridade da transição (ADR-011) e o fechamento diário quebram. Rejeitada.

**Opção B, data de negócio explícita em event-time, com fuso único e `GET ?data=` (escolhida).**
- A favor: o dia é um dado do evento, definido na origem e gravado no payload, imutável e independente de quando é processado; reprocesso e atraso não mudam o dia; a consulta por dia fica possível; e a paridade fica comparável.
- Contra: exige propagar a data no envelope do evento e usá-la como chave do `saldo_diario` e do `processed_event`, com a coluna `data` no DDL e não `now()`, e exige uma política de fechamento, com watermark, para saber quando o dia está pronto.

**Opção C, o dia sempre em UTC.**
- A favor: convenção única, sem ambiguidade técnica.
- Contra: está errado para o negócio, porque o comerciante pensa no fuso local, em America/Sao_Paulo, e a meia-noite de UTC é 21:00 local, então o dia do caixa não bate com o dia do comerciante. Rejeitada.

## Decisão

Adoto a Opção B. As regras canônicas são estas.

1. A data de negócio é explícita e obrigatória. Todo lançamento carrega a `data_negocio`, um DATE em America/Sao_Paulo, derivada na origem a partir do timestamp do lançamento, e não de `now()` no consumidor. Ela é a chave de agregação. Há um nome por camada, por coerência: no banco e no ER a coluna chama-se `data`, com `PK (event_id, data)` e `saldo_diario (comerciante_id, data)`, e `data_negocio` é o termo de negócio no request e no envelope. É o mesmo valor, e a camada DDL usa `data`, no `schema.sql`.
2. Fuso único e declarado: America/Sao_Paulo, com `ZoneId` no serviço, `timestamptz` no store e `DATE` para o dia. Os horários internos ficam em UTC, em ISO-8601 `timestamptz`, e a virada de dia é calculada no fuso do negócio.
3. Event-time e watermark (ADR-007): o fechamento do dia D só é declarado pronto quando `watermark = min(event_time pendente) > fim(D)`, isto é, quando não há mais evento atrasado esperado dentro da janela. Antes disso, o dia está em curso. É isso que torna o fechamento diário determinístico.
4. Contrato de leitura: `GET /api/v1/consolidado/{comerciante_id}?data=YYYY-MM-DD`, com default para hoje no fuso do negócio, e a resposta inclui `data`, `valor`, `status`, com valor `em_curso` ou `fechado`, e `atualizado_em`.
5. Política de evento atrasado: um evento cuja data de negócio já passou do watermark não é descartado; ele aplica backfill no dia correspondente, com append-only e rollup reprocessando (ADR-004), e dispara alerta (ADR-009).
6. Transição (ADR-011): a paridade compara o saldo novo e o saldo legado na mesma data de negócio e no mesmo fuso, senão uma divergência por fuso é confundida com divergência de dinheiro.

A janela de watermark do fechamento, por exemplo aceitar eventos atrasados até D+1 às 04:00, é decisão de negócio do dono, com gatilho no fecho da especificação de calendário. Enquanto não estiver ratificada, o `status` usa o default declarado e fica pendente.

## Consequências

Positivas:
- O fechamento diário fica determinístico, porque o dia é um dado do evento e é imune a atraso e reprocesso, com outbox, DLQ e backfill.
- O contrato fica recuperável, com consulta a qualquer dia passado por `?data=`, e o `status` diz se o dia está pronto.
- A paridade e a reconciliação ficam comparáveis, com a mesma data e o mesmo fuso, o que fecha o vínculo com os ADR-004, ADR-007 e ADR-011.
- Some a ambiguidade do `now()` implícito, e o resultado é o mesmo em local e no alvo, na semântica.

Negativas e mitigação:
- A data de negócio na origem exige que o chamador e a UI a calculem corretamente; a mitigação é um default no serviço, a partir do timestamp recebido, mais validação, não aceitando dia no futuro além do horizonte.
- O watermark e o fechamento acrescentam estado, com `em curso` e `fechado`, e um horizonte de eventos atrasados a definir, o que é mais complexidade; a mitigação é um default declarado e um alarme de evento atrasado (ADR-009).
- A base de fusos horários precisa estar atualizada no container, por causa das regras históricas de horário de verão, o que é um item do docker-compose e da imagem, com a base `tzdata`.

## Fontes

- O PDF do desafio, arquivo desafio-arquiteto-solucao-jun25.pdf, nos requisitos de negócio, sobre o saldo diário consolidado.
- Martin Kleppmann, Designing Data-Intensive Applications (2017), sobre event time e processing time, e watermarks.
- Tyler Akidau et al., The Dataflow Model (VLDB 2015), sobre event-time, watermarks e late data com backfill.
- A base de fusos da IANA e o java.time, com `ZonedDateTime`, `ZoneId` e `ZoneRules`, sobre fuso e horário de verão.
- RFC 3339 e ISO-8601, sobre a representação de instantes, com `timestamptz` interno em UTC.
- Michael Nygard, Documenting Architecture Decisions (2011).
