FROM node:24-bookworm-slim AS web-build
WORKDIR /workspace/web
RUN npm install --global pnpm@11.25.0
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm test && pnpm build

FROM maven:3.9.11-eclipse-temurin-21 AS java-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -f backend/pom.xml dependency:go-offline
COPY backend/ backend/
COPY --from=web-build /workspace/web/dist/ web/dist/
# Real PostgreSQL integration tests run in CI and via scripts/mvn.ps1 test.
# A Docker image build does not have the CI database service attached.
RUN mvn -B -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN apt-get update && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 batchflow \
    && useradd --uid 10001 --gid 10001 --no-create-home batchflow \
    && mkdir -p /app/storage && chown -R batchflow:batchflow /app
COPY --from=java-build --chown=batchflow:batchflow /workspace/backend/target/batchflow-0.1.0.jar /app/batchflow.jar
USER batchflow
EXPOSE 18090 18091
ENTRYPOINT ["java", "-jar", "/app/batchflow.jar"]
