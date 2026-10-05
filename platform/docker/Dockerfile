# Один Dockerfile для всех приложений стенда: jar собирает Maven до `docker build` (см. README), внутри образа сборки нет.
# Используется тремя образами: ingestion-service, mcp-server и demo-launcher (адаптеры и заглушки: аргументы в compose).
FROM eclipse-temurin:21-jre

ARG JAR
RUN useradd --system --uid 10001 --no-create-home archrag
WORKDIR /app
COPY ${JAR} /app/app.jar
COPY --chmod=755 platform/docker/entrypoint.sh /app/entrypoint.sh
USER 10001

# curl есть в базовом образе: healthcheck в compose не требует ничего ставить.
# entrypoint.sh экспортирует Docker secrets (/run/secrets/*) в переменные окружения и делает exec java.
ENTRYPOINT ["/app/entrypoint.sh"]
