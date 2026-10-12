# ADR-005: contrato de escrita, com Idempotency-Key e event_id determinístico (UUIDv5)

Status: Aceito

Relaciona os ADR-003 (relay at-least-once), ADR-004 (deduplicação durável), o `openapi.yaml` e o ADR-008 (contrato de consumo).

## Contexto

O desenho diz que a idempotência usa um `transaction_id` gerado na origem, mas o exemplo de contrato, o `curl` que aparece no próprio documento, não envia campo algum de idempotência: o cliente manda só `valor` e `tipo` e recebe 202. Ao mesmo tempo, o consumidor deduplica por `transaction_id`.

A consequência é mecânica: se o servidor gerar o `event_id` aleatório a cada request, um retry do cliente, por timeout de rede, por 502 do gateway ou por um clique duplo, cria um novo `event_id`, e a deduplicação do consumidor não reconhece a duplicata, o que gera um duplo crédito e contraria a promessa de perda zero sem duplicidade, além de gastar o orçamento de 5% de perda no consolidado com duplicatas de borda. Além disso, o invariante de contagem fecha com `COUNT(DISTINCT event_id)`, e se o `event_id` não for determinístico da chave do cliente, esse termo não fecha na borda.

O contrato também não diz o que acontece no conflito, quando a mesma key chega com corpo diferente, nem por quanto tempo a key vale. Sem TTL, a tabela cresce para sempre; com TTL curto, um retry tardio duplica.

## Opções consideradas

**Opção A, `event_id` aleatório gerado no servidor.**
- A favor: cliente simples, nada a enviar.
- Contra: o retry do cliente duplica, porque a deduplicação do consumidor cobre a reentrega do broker e não a do cliente. Rejeitada.

**Opção B, o cliente envia o `event_id` pronto, como um UUID dele.**
- A favor: determinístico por construção.
- Contra: transfere ao cliente a responsabilidade de gerar e não reusar UUIDs; clientes ruins reusam `event_id` com corpos diferentes e corrompem a chave; e acopla o contrato a um detalhe do servidor. Rejeitada.

**Opção C, `Idempotency-Key` do cliente vira `event_id = UUIDv5(NAMESPACE_VERETY, key)` (escolhida).**
- A favor: a mesma `Idempotency-Key` produz sempre o mesmo `event_id`, determinístico conforme a RFC 4122 seção 4.3, então o retry do cliente cai no mesmo marcador de deduplicação e é no-op; o cliente só precisa garantir uma key por operação lógica, e o servidor controla a derivação.
- Contra: exige documentar a semântica da key, com escopo, TTL e conflito, e é exatamente o que este ADR fixa.

## Decisão

A `Idempotency-Key` é header obrigatório no `POST /api/v1/lancamentos`, e o `event_id` é derivado de forma determinística:

```text
event_id = UUIDv5(namespace = NS_VERETY_LANCAMENTO, name = Idempotency-Key)
```

### Semântica da key

| Situação | Resposta | Efeito |
|---|---|---|
| `Idempotency-Key` ausente no POST | 400 Bad Request, `missing_idempotency_key` | Nada é escrito. |
| Key nova, payload válido | 201 Created, com `Location` | `lancamento` mais `outbox` pendente no mesmo COMMIT. |
| Mesma key, mesmo payload, hash igual | 200 OK, com o mesmo recurso | Replay idempotente, sem novo efeito, por `ON CONFLICT (event_id, data) DO NOTHING`. |
| Mesma key, payload diferente, hash diferente | 409 Conflict, `idempotency_key_reuse` | Rejeita e nunca sobrescreve. |
| Payload malformado, em tipo, valor ou data | 422 Unprocessable Entity, `validation_error` | Nada é escrito. |
| Broker ou consolidado fora | 201 Created, com o outbox acumulando pendente | O RNF#1 manda que o POST não dependa do broker (ADR-003). |

- O vínculo entre corpo e key é feito com `payload_hash = SHA-256(corpo canônico normalizado)`, guardado junto de `idempotency_key` e `event_id`. É o que distingue o replay, quando o hash é igual, do reuso indevido, quando o hash é diferente e a resposta é 409. A normalização do corpo, com ordem de campos e escala de decimais, fica fixada no `openapi.yaml`.
- A key não tem TTL curto. A validade dela é maior ou igual à retenção do ledger: a idempotência vive na tabela durável `lancamentos.lancamento`, com `UNIQUE (comerciante_id, data, transaction_id)` e o `transaction_id` da origem, mais `consolidado.processed_event`, com chave `(event_id, data)`, e nunca em cache com expiração, que era o caminho do `SETNX` com TTL de 48 h e que levava a dupla contagem no reprocesso tardio (ADR-004). A política de expurgo (ADR-006) só pode podar `transaction_id` de janelas já fechadas e reconciliadas, e é proibida dentro do horizonte de retry e redrive.
- O escopo da key é por `comerciante_id` e operação lógica. A recomendação é um UUID ou ULID gerado uma vez pelo cliente por intenção de lançamento, e o reuso intencional para replay é permitido só com corpo idêntico.
- O `event_id` é estável de ponta a ponta: o relay publica o evento com esse `event_id` inalterado (ADR-003), o consumidor deduplica por ele (ADR-004) e o redrive da DLQ o preserva, conforme o `runbook-dlq.md`. É esse elo que faz o `COUNT(DISTINCT event_id)` fechar da borda ao saldo.

### Erros padronizados (RFC 9457, `application/problem+json`)

Os códigos são 400 para malformado, 409 para conflito de key, 422 para validação, 429 para rate-limit e 503 para indisponível, no comportamento do gateway. O corpo é um `Problem` com `type`, `title`, `status`, `detail` e `instance`, detalhado no `openapi.yaml`.

## Consequências

Positivas:
- O retry do cliente é seguro: a mesma key dá o mesmo `event_id` e é no-op, o que elimina o duplo crédito na borda, e não só no broker.
- O invariante de reconciliação fecha de ponta a ponta, porque `Idempotency-Key` vira um `event_id` determinístico.
- O contrato fica formal, testável e versionado no `openapi.yaml`, e dá ao avaliador uma superfície de `curl` e Postman.
- O cliente expressa intenção, não um detalhe interno como o `event_id`.

Negativas:
- O cliente fica obrigado a gerar a key e a não reusá-la com corpos diferentes; o 409 é a rede de proteção.
- Guardar o `payload_hash` por key e por lançamento é um custo de armazenamento.
- O `UUIDv5` é determinístico e não é segredo, e o namespace e o tipo de operação não entram na chave; eles devem entrar se surgirem múltiplos endpoints idempotentes, com registradores distintos.

A verificação é esta: dois POST com a mesma `Idempotency-Key` e o mesmo corpo produzem um lançamento e um evento de saldo, com 200 no segundo; a mesma key com corpo diferente dá 409 e zero efeito; e a key ausente dá 400 e zero efeito.
