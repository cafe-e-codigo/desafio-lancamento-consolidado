# Documentação técnica do Verety

Esta pasta reúne a documentação técnica da solução: a especificação, as decisões, o contrato, o schema, os diagramas e os textos de apoio.

## Documentos

- [especificacao-tecnica.md](especificacao-tecnica.md): a especificação da arquitetura alvo (visão, domínios, capacidades, RF e RNF, arquitetura e justificativas).
- [adr/](adr/) (13 ADRs, ADR-001 a ADR-013): as decisões de arquitetura, uma por arquivo, no formato contexto, opções, decisão e consequências.
- [openapi.yaml](openapi.yaml): o contrato HTTP (OpenAPI 3.1) dos lançamentos e do consolidado.
- [schema.sql](schema.sql): o DDL do Postgres, com os dois schemas e os índices.
- [topologia/](topologia/) (7 `.drawio`): os diagramas C4 níveis 1 a 3, o modelo ER, a sequência do write path, a mensageria e o deploy.
- [por-que.md](por-que.md): o texto que explica o porquê de cada decisão.
- [threat-model.md](threat-model.md): o modelo de ameaças e os controles de segurança.
- [observabilidade-dados.md](observabilidade-dados.md): o catálogo de métricas de dados, o invariante de reconciliação e os alarmes.
- [runbook-dlq.md](runbook-dlq.md): o procedimento de triagem e redrive da DLQ.

## Ordem de leitura sugerida

especificacao-tecnica.md → adr/ → openapi.yaml e schema.sql → topologia/ → textos de apoio.
