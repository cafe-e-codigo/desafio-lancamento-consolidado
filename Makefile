# Verety: atalhos de desenvolvimento. Sem JDK/Maven no host: tudo roda via container.
# Uso:  make test | build | up | down | k6 | demo | it T=Uuid5Test
MAVEN_CACHE = verety-m2
MVN  = docker run --rm -v "$(CURDIR)":/w -v $(MAVEN_CACHE):/root/.m2 -w /w maven:3.9-eclipse-temurin-21
MIT  = docker run --rm -v "$(CURDIR)":/w -v $(MAVEN_CACHE):/root/.m2 -w /w \
         -v /var/run/docker.sock:/var/run/docker.sock \
         -e TESTCONTAINERS_RYUK_DISABLED=true maven:3.9-eclipse-temurin-21

SURFACE  ?= consolidado
K6_BASE  ?= $(if $(filter consolidado,$(SURFACE)),http://localhost:8081,http://localhost:8080)

.PHONY: help test build it up down logs ps k6 demo clean

help:   ## lista os alvos
	@grep -E '^[a-z0-9]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/\t/' | column -t -s"$$(printf '\t')"

test:   ## suíte completa (33 testes JUnit/Testcontainers)
	$(MIT) mvn -B test -Dapi.version=1.43

build:  ## compila e gera os 3 jars (pula testes)
	$(MVN) mvn -q -DskipTests clean package

it:     ## um teste só:  make it T=LancamentoServiceIT
	$(MIT) mvn -B test -Dapi.version=1.43 -Dtest=$(T) -Dsurefire.failIfNoSpecifiedTests=false

up:     ## sobe a stack (Postgres+RabbitMQ+Redis+2 apps)
	docker compose up -d --build
	@docker compose ps

down:   ## derruba a stack (mantém os dados)
	docker compose down

logs:   ## segue o log dos dois apps
	docker compose logs -f lancamentos consolidado

ps:     ## estado dos containers
	docker compose ps

k6:     ## carga 50 req/s (SURFACE=ingest|consolidado; stack no ar)
	TOKEN=$$(python3 harness/mint-token.py) ; \
	docker run --rm --network host -v "$(CURDIR)":/w -w /w grafana/k6 \
	  run -e BASE_URL=$(K6_BASE) -e SURFACE=$(SURFACE) -e RATE=50 -e DURATION=30s -e TOKEN="$$TOKEN" harness/k6/k6-consolidado.js

demo:   ## N lançamentos:  make demo N=10
	./harness/demo-varios-lancamentos.sh $(or $(N),5)

clean:  ## limpa os target/
	$(MVN) mvn -q clean
