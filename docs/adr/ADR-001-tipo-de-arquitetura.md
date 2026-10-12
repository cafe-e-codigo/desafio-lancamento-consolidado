# ADR-001: tipo de arquitetura (monolito modular, microsserviços ou SOA)

Status: Aceito

Relaciona os ADR-002 (fronteiras e CQRS), ADR-003 (outbox) e ADR-007 (mensageria).

## Contexto

O desafio lista três padrões possíveis (microsserviços, SOA e monolito modular) e pede que eu compare os trade-offs entre simplicidade e flexibilidade. O cenário tem dois serviços, o de lançamentos e o do consolidado diário, com requisitos que puxam para lados opostos. A escrita não pode ficar indisponível se a leitura cair, e isso é o RNF#1. O consolidado precisa absorver 50 requisições por segundo com no máximo 5% de perda, e isso é o RNF#2. A moldura é mono-comerciante, em Java, sem interface web.

## Opções consideradas

**Opção A, monolito modular (um único deploy, com os módulos de lançamentos e de consolidado bem separados).**
- A favor: menor custo operacional, transações locais e um só artefato, o que combina com equipe pequena.
- Contra: não isola o RNF#1. Um deploy ou uma queda do módulo de consolidação derruba o processo de escrita, porque os dois rodam na mesma JVM, e aí o desacoplamento deixa de ser físico. Para escalar só a leitura seria preciso replicar o módulo inteiro.

**Opção B, microsserviços com CQRS e mensageria assíncrona (escolhida).**
- A favor: separação física entre escrita e leitura, o que satisfaz o RNF#1 por construção, com o 201 saindo no commit e o broker fora do caminho da requisição. O read-side escala de forma independente para o RNF#2, e as fronteiras coincidem com os bounded contexts.
- Contra: a entrega at-least-once obriga idempotência durável e outbox com relay; são mais peças móveis e mais custo de operação e de observabilidade; e a consistência eventual exige tratar o lag de propagação, não ignorá-lo.

**Opção C, SOA com barramento central (ESB).**
- A favor: mediação e transformação centralizadas, com a governança num único ponto.
- Contra: o barramento vira ponto único de acoplamento e de falha, é excesso para dois serviços e contraria a segregação de capacidades pedida pelo desafio. Rejeitada.

## Decisão

Adoto a **Opção B**, e a justificativa vem dos requisitos, não de preferência pessoal.

1. O RNF#1 é um requisito de topologia, não de código. Que "lançamentos não pode cair se o consolidado cair" só se garante se escrita e leitura forem processos e deploys distintos, o que pede a segregação física da Opção B combinada com o outbox. O monolito modular não fecha esse requisito sem cair junto.

2. O RNF#2 pede escala assimétrica. Só o read-side recebe as 50 requisições por segundo, então escalar apenas a leitura é economia direta. A Opção C não muda isso e ainda acrescenta um salto central.

3. O CQRS é a forma de materializar a segregação de capacidades que o desafio pede: um write-model (lançamento e outbox) e um read-model (saldo consolidado), com fronteiras explícitas.

4. A simplicidade fica preservada: dois serviços, um broker, um Postgres e um Redis, sem ESB, sem gateway dedicado e sem serviço de autenticação próprio. O preço da flexibilidade é pago com disciplina de engenharia, não com mais nós.

A decisão por microsserviços é do tipo "dois serviços de negócio", não "N serviços". Se o domínio crescer (multi-inquilino, várias integrações), a escolha deve ser reavaliada.

## Consequências

Pontos positivos:
- O RNF#1 passa a valer por construção, e o RNF#2 escala só no read-side, com as fronteiras coincidindo com os domínios do negócio.
- Cada decisão de custo que vier depois (broker em alta disponibilidade, réplica de leitura) fica localizada e não exige redesenho.
- Não se cresce topologia sem necessidade: nenhum nó além dos dois serviços e dos dados.

Pontos negativos e mitigação:
- Consistência eventual entre o POST e o GET, com latência de propagação. A mitigação é medir o limite de lag, e não apenas afirmá-lo.
- Complexidade distribuída (outbox, idempotência, DLQ e taxonomia de erro), já endereçada nos ADRs de mensageria e de idempotência.
- Duplicação de middleware de autorização e de tracing nos dois serviços. A mitigação é tratar a política como dado, para que a convenção seja replicada de forma controlada.

## Fontes

- O PDF do desafio, arquivo desafio-arquiteto-solucao-jun25.pdf, nas seções "Padrões Arquiteturais", "Requisitos de negócio" e "Requisitos não funcionais".
- Sam Newman, Building Microservices (2ª edição, 2021): fronteiras de serviço e o custo da distribuição.
- Eric Evans, Domain-Driven Design (2003): bounded context e segregação de capacidades.
- Martin Fowler, MonolithFirst e Microservices Trade-Offs (martinfowler.com): quando não distribuir.
- Michael Nygard, Documenting Architecture Decisions (2011): o formato de opções, decisão e consequências.
- AWS Well-Architected: os pilares de Reliability (isolar falhas) e Cost Optimization (escalar por componente).
