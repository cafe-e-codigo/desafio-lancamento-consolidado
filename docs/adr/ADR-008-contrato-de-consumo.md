# ADR-008: contrato de consumo (segurança de integração)

Status: Aceito

O escopo são dois serviços Java, lançamentos e consolidado, mono-comerciante e sem nó novo.

## Contexto

O desafio, nos requisitos diferenciais, pede critérios de segurança para o consumo e a integração de serviços. O desenho entrega WAF e ALB no perímetro, OAuth2 e OIDC com JWT na autenticação, TLS 1.3 no transporte, KMS no repouso e Secrets Manager com IRSA na identidade de workload. Isso é infraestrutura de consumo, e não critério de consumo. Trecho a trecho, a quota é por IP e não por chamador, a autorização refinada é um adjetivo sem escopo, sem aud e sem alg, o mTLS aparece sem política de credencial, e faltam literalmente os itens do diferencial: escopo por comerciante, ownership por recurso, autorização por endpoint, credencial serviço a serviço e versão e quota. O contrato expõe `GET /consolidado/{merchant_id}` com o id do cliente no path, então qualquer token válido lê o caixa de outro id, que é o BOLA do OWASP API1. Autenticar não é autorizar.

## Opções consideradas

**Opção A, um nó dedicado de API Gateway ou Authorization Service.**
- A favor: política de autorização centralizada, sem deriva, e ponto único de auditoria.
- Contra: muda a topologia e a fronteira da moldura, que é de dois serviços e sem novo componente; acrescenta custo de um serviço dedicado e latência de um salto; e é excesso para mono-comerciante.

**Opção B, convenção mais configuração nos dois serviços existentes, com biblioteca compartilhada e política como dado (escolhida).**
- A favor: zero nó novo, fecha o critério de consumo e tem custo quase zero, por reuso de código e configuração; fica alinhado à moldura e ao viés de não crescer topologia sem que ela pague o custo.
- Contra: existe risco de deriva de autorização entre os dois serviços, o que exige disciplina de política como dado e a biblioteca compartilhada.

**Opção C, manter o estado atual.**
- A favor: nada a fazer.
- Contra: o critério de consumo não existe e o BOLA continua exposto, então o atendimento ao diferencial é só uma afirmação.

## Decisão

Adoto a Opção B, convenção mais configuração, sem novo nó. Os itens por camada são estes.

1. Autenticação e escopo: um middleware JWT em cada serviço, com biblioteca compartilhada e não serviço, que valida `iss`, `aud`, `exp` e `alg`, com allowlist e `alg=none` proibido, mais o `scope` do chamador, e JWKS com rotação.
2. Ownership por recurso, contra o BOLA: a regra fica na borda do domínio de cada serviço, com o `merchant_id` do token igual ao `merchant_id` do path ou do body, e divergência dá 403 ou 404, para não vazar a existência do recurso.
3. Credencial serviço a serviço e do broker: a autorização do broker Amazon MQ usa `users`, `vhosts` e permissões por serviço, com TLS server-side obrigatório e sem `guest/guest`. Não prometo mTLS onde o AMQP não provê, e o App Mesh ou ALB não cobre o salto AMQP.
4. Autorização por endpoint: uma matriz de método, rota e scope, versionada no `openapi.yaml`.
5. Versão e depreciação: OpenAPI 3.1 com prefixo `/api/v1` e política de sunset.
6. Quota por chamador: no edge já declarado, ALB ou WAF ou API Gateway, como configuração e não nó, e o Cognito fica como IdP gerenciado, externo e não construído.
7. Auditoria por chamador: log estruturado com caller id, rota, decisão allow ou deny e correlation id do ADR-009, mais CloudTrail, que o ADR-009 exige.

## Consequências

Positivas:
- O diferencial de segurança para consumo fica atendido por artefato observável, com a matriz de escopos, a regra de ownership, a autorização de broker e o OpenAPI versionado, sem custo de infraestrutura.
- O BOLA e o BFLA ficam fechados na borda do domínio, e a auditoria por chamador fica rastreável.

Negativas e mitigação:
- A deriva de autorização entre os dois serviços é mitigada com biblioteca compartilhada e política como dado, em arquivo versionado, e não em código fixo. Se um dia houver muitos serviços e chamadores externos multi-inquilino, o que hoje está fora do escopo, promove-se a componente dedicado e o ADR é reavaliado.
- A quota por chamador depende do edge; se o edge mudar, a configuração muda, e isso deve ser registrado na comparação entre local e alvo.

## Fontes

- OWASP API Security Top 10 (2023): API1 BOLA e API5 BFLA.
- OWASP Top 10 (2021): A01 e A05.
- RFC 9110, sobre semântica HTTP, com 403 e 404.
- Martin Fowler, Consumer-Driven Contracts.
- AWS Well-Architected, Security Pillar, com menor privilégio e autorização por requisição.
- Michael Nygard, Documenting Architecture Decisions (2011).
