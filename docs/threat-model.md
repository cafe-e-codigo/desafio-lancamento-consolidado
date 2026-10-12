# Threat Model: Verety

Escopo: ambiente descartável em `docker-compose` mais o projeto dos dois serviços Java (mono-comerciante), sem AWS real ligada.

---

## 1. Threat model (mínimo viável)

### 1.1 Ativos
- Os dados financeiros de um comerciante: lançamentos (débitos e créditos) e saldo diário consolidado.
- As credenciais do broker (RabbitMQ) e o token do IdP (Cognito), sempre por nome de variável, nunca por valor.
- Os ledgers duráveis: `processed_event`, `saldo_diario` e `outbox` (payload de evento).

### 1.2 Fronteira e premissas do ambiente
- Ambiente descartável (`docker-compose`), efêmero (`down -v` apaga volumes) e mono-comerciante, com dados sintéticos, sem PII real.
- Nenhuma porta exposta além de `localhost` e sem AWS real ligada (o Alvo é desenho).
- Quem for avaliar clona e roda o repositório público, então eu o trato como hostil por padrão.

### 1.3 Adversários e ameaças (STRIDE resumido)

| Ameaça | Cenário no projeto | Controle |
|---|---|---|
| Spoofing | token forjado ou `alg=none` | validação de JWT em `iss`, `aud`, `exp` e `alg`, com JWKS (ADR-008) |
| Tampering | mensagem alterada em trânsito | TLS server-side no Amazon MQ e `delivery_mode=2` para durabilidade |
| Repudiation | "não fui eu que lancei" | auditoria por chamador, CloudTrail e correlation id (ADR-008 e ADR-009) |
| Information disclosure (IDOR/BOLA) | `GET /consolidado/{merchant_id}` de outro id | ownership: a claim `merchant_id` tem de casar com path e body, senão 403 ou 404 (ADR-008) |
| Denial of service | flood por IP ou um pico de 50 req/s | WAF rate-limit na borda e quota por chamador (ADR-008) |
| Elevation of privilege | `guest/guest` que vira padrão de cópia | `guest/guest` removido e authz do broker por serviço (ADR-008) |
| Vazamento de segredo | credencial publicada no repositório | scan de segredo igual a zero e Secrets Manager no Alvo |

### 1.4 Segredos: política (nome de variável, responsável e nunca valor)
- Local: o `guest/guest` do RabbitMQ é apenas loopback, marcado descartável e proibido no Alvo; as credenciais dos serviços locais vêm de variáveis de ambiente, com o `.env` não commitado e referenciado só pelo nome. Nunca ler ou colar valor.
- Alvo (desenho): broker via AWS Secrets Manager com rotação; workload via IRSA e ECS Task Roles, com menor privilégio; KMS cobre os dados em repouso (ledgers do ADR-009 e ADR-013).
- Responsável pelo segredo: a senha do broker é da Mensageria; a credencial de IdP e do chamador é do Backend; a política de classificação e retenção é da Segurança.

### 1.5 Isolamento e multi-tenant
- Multi-tenant está fora da moldura. Declaro forward-compat: o `merchant_id` já é a chave de escopo, como claim no token, então a evolução de single para multi-tenant é política de authz, e não reescrita de topologia. Enquanto mono-comerciante, o ownership continua valendo como defesa em profundidade.

### 1.6 Veredito do ambiente descartável
Fica aprovado como descartável desde que: o scan de segredo seja zero; os dados sejam sintéticos; não haja distribuição externa além de `localhost`; o `guest/guest` fique escopado, marcado como descartável e barrado no Alvo; e o `.env` fique fora do versionamento. Se qualquer um falhar, fica reprovado.

---

## Verificação dos controles (segurança)

Estes são os controles verificáveis de segurança que eu chamo de G1. O responsável por esta verificação é a Segurança.

1. scan de segredo igual a zero, com o `guest/guest` removido do Alvo;
2. authz por recurso negada corretamente, com 403 ou 404, e a claim casando com path e body;
3. validação de JWT em `iss`, `aud`, `exp` e `alg`;
4. `Idempotency-Key` ponta a ponta, que é a precondição do SQL único;
5. authz do broker, com users, vhosts e permissões por serviço, TLS server-side e `guest` fora do Alvo.

---

## Referências

- OWASP, para o modelo de ameaças e a prevenção de IDOR e BOLA.
- STRIDE, como taxonomia de ameaças.
- ADR-008, segurança para consumo e integração.
- ADR-009, observabilidade.
