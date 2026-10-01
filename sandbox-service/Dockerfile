# Same base as adk-mcp-sandbox's sandbox-bridge/Dockerfile — only the jar and the
# ENTRYPOINT class differ. Node is here for the same reason: the demo MCP server
# (@modelcontextprotocol/server-everything) is npx-based.
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gnupg ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/sandbox-service.jar /app/sandbox-service.jar

# Cloud Run injects PORT — SandboxServiceMain reads it (defaults to 8080 if unset, e.g.
# for a local docker run without -e PORT=...).
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/sandbox-service.jar"]
