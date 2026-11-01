# P1 门户运行镜像。
# 构建产物由 CI/本机 `mvn -o clean package` 生成；容器内不下载依赖，保证离线可复现。
# 基础镜像按 ADR-001 锁 digest（2026-11-01 拉取核对）。
FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a

ARG JAR_FILE=target/kb-p1-portal.jar
WORKDIR /app

RUN groupadd --system --gid 10001 kbp1 \
    && useradd --system --uid 10001 --gid kbp1 --home-dir /app --shell /usr/sbin/nologin kbp1 \
    && chown -R kbp1:kbp1 /app

COPY --chown=kbp1:kbp1 ${JAR_FILE} /app/app.jar

USER kbp1
EXPOSE 18181
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
