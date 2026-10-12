# Verety: imagem do serviço `consolidado` (read-side, CQRS). Multi-stage.
#
# CONTEXTO DE BUILD = RAIZ do repositório, não a pasta do módulo: o build Maven
# multi-módulo precisa do pom pai + do módulo `common`. O docker-compose aponta:
#     build: { context: ., dockerfile: consolidado/Dockerfile }

# ---------- estágio 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /w

# Poms primeiro (melhora cache de camadas); a raiz lista os 3 módulos, então todos os poms entram.
COPY pom.xml .
COPY common/pom.xml common/
COPY lancamentos/pom.xml lancamentos/
COPY consolidado/pom.xml consolidado/

# Código-fonte (common é dependência; -am constrói o que o módulo precisa).
COPY common/src common/src
COPY consolidado/src consolidado/src

RUN mvn -q -B -DskipTests -pl consolidado -am package

# ---------- estágio 2: runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /w/consolidado/target/consolidado-*-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
