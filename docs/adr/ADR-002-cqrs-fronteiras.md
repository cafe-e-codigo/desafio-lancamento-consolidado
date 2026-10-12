# ADR-002: fronteiras de CQRS e a segregação entre escrita e leitura

Status: Aceito

Relaciona os ADR-001 (tipo de arquitetura), ADR-003 (outbox), ADR-004 (Postgres como verdade), ADR-011 (transição) e o C4 de nível 3.

## Contexto

O desenho declara CQRS e separa dois serviços, mas a fronteira vaza em três pontos que eu quero registrar, porque cada um tem consequência mecânica.

1. Duas unidades de escrita na projeção. O consolidado atualizaria de forma atômica a chave de saldo no Redis e também a base histórica. Redis e a base relacional, sem transação distribuída, é dual-write: se uma das duas falha, os lados divergem em silêncio e ninguém percebe.

2. O saldo diário no mesmo banco dos lançamentos. No diagrama existe um único nó de banco relacional, consumido tanto pelo serviço de lançamentos quanto pelo do consolidado. Se os dois contextos compartilham o mesmo banco, o split de CQRS é lógico e não físico.

3. Direção invertida. A aresta que liga o banco ao consolidado, `...-44`, tem origem no banco e destino no serviço, ou seja, o desenho sugere que o banco inicia a atualização.

O desafio pede segregação de capacidades e definição de contextos, não separação de deploy por si só.

## Opções consideradas

**Opção A, a projeção grava Redis e a base relacional como se fosse atômico.**
- A favor: GET muito rápido e o histórico gravado junto.
- Contra: dual-write sem transação distribuída leva a divergência silenciosa, o Redis vira a fonte de verdade de fato e o modelo ACID que o próprio desenho invoca fica contrariado. Rejeitada.

**Opção B, um banco físico com dois schemas, zero FK cruzada, uma única unidade de escrita em Postgres e o Redis derivado (escolhida).**
- A favor: uma só unidade de escrita fecha o dual-write, e separar `lancamentos` e `consolidado` em schemas distintos dá disciplina de fronteira, sem nenhuma FK entre schemas, sem pagar o custo de dois bancos. O Redis passa a ser cache derivado com read-through (ADR-004).
- Contra: resta um acoplamento físico no cluster (um Aurora), então existe o risco de a fronteira vazar por SQL direto entre schemas. A mitigação é uma regra de build e de ACL por schema, mais a fronteira explícita no C4 de nível 3.

**Opção C, dois bancos físicos separados, um para escrita e um para leitura.**
- A favor: isolamento máximo de fronteira, com escala e alta disponibilidade independentes por lado.
- Contra: num contexto mono-comerciante a 50 requisições por segundo isso é excesso: mais um cluster, mais custo e mais consistência entre bancos para reconciliar, quando o ganho de fronteira já se obtém com schemas. Rejeitada neste escopo e registrada como evolução, para o caso de o read-model passar a precisar de uma engine diferente, por exemplo colunar para rollup em escala.

## Decisão

Adoto a Opção B. A fronteira canônica fica assim:

1. O lado de escrita, em lançamentos, é o schema `lancamentos`, com as tabelas `lancamento` e `outbox` (ADR-003). É o único produtor de eventos.
2. O lado de leitura, no consolidado, é o schema `consolidado`, com um ledger único `processed_event`, cuja chave primária é `(event_id, data)`, onde a deduplicação e o incremento ficam na mesma linha, em `valor_signed`, mais o `saldo_diario` de rollup (ADR-004). É o único consumidor e o único que materializa o saldo. Não existe um `saldo_incremento` separado: o executável canônico é o `schema.sql`.
3. Uma unidade de escrita por agregado. Os lançamentos não escrevem em `consolidado.*` e o consolidado não escreve em `lancamentos.*`. A comunicação é só por evento, nunca por tabela compartilhada.
4. O Redis é read model derivado do consolidado e nunca fonte de verdade (ADR-004). O GET lê do Redis e, no miss, faz read-through para o `saldo_diario` com single-flight.
5. Zero FK entre schemas. Nenhuma constraint referencia o outro schema, e a integridade entre os dois lados é eventual, verificada pelo invariante de contagem: o número de aceitos distintos por `Idempotency-Key` é igual ao outbox pendente mais falho, mais a contagem de `event_id` distintos, mais a DLQ e os descartes.
6. A direção no C4 fica corrigida: o serviço aponta para o banco, porque é o serviço que atualiza, nunca o contrário.

A declaração de fronteira é esta: o C4 de nível 3 materializa a decisão, e o nível 2 não tem SPA, tem DLQ, outbox e WAF, com a direção correta.

## Consequências

Positivas:
- Uma unidade de escrita elimina o dual-write e deixa a fronteira verificável, por ACL de schema e zero FK cruzada.
- Os contextos delimitados ficam alinhados aos dois serviços do desafio, com integração exclusivamente por evento, ou seja, acoplamento fraco.
- Há reuso: o mesmo padrão de outbox serve à transição para o legado (ADR-011).

Negativas e mitigação:
- Um banco físico compartilha o raio de explosão, e uma indisponibilidade do cluster afeta os dois lados. A mitigação é que o RNF#1 é satisfeito no caminho de escrita, com o 201 no commit e o outbox, e não pela separação de banco.
- A fronteira lógica sobre um banco físico pode degradar com o tempo, se aparecer SQL direto cruzando schema. A mitigação é uma regra de revisão que proíbe query entre schemas, papéis e ACL por schema, e teste no harness.
- O rollup do read model é um job interno observável e entra no bound de lag do ADR-003.

## Fontes

- Eric Evans, Domain-Driven Design (2003): contextos delimitados, context map e integração por eventos.
- Martin Fowler, CQRS (martinfowler.com): separação entre comando e consulta, e o conceito de bounded context.
- Vaughn Vernon, Implementing Domain-Driven Design (2013): separação de schemas e anti-corruption layer.
- Chris Richardson, microservices.io: Database per Service contra Shared Database como anti-padrão.
- Tomasz Nurkiewicz e o catálogo da microservices.io: os padrões Database per Service e Shared Database.
- Michael Nygard, Documenting Architecture Decisions (2011): o formato de registro.
