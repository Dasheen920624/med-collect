ARG TEMURIN_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${TEMURIN_IMAGE}

ARG JAR_SHA256
LABEL org.opencontainers.image.title="med-collect" \
      cn.zoesoft.med-collect.jar-sha256="${JAR_SHA256}"

COPY med-collect.jar /app/med-collect.jar
RUN test -n "$JAR_SHA256" \
    && printf '%s  /app/med-collect.jar\n' "$JAR_SHA256" | sha256sum -c - \
    && groupadd --gid 10001 medcollect \
    && useradd --uid 10001 --gid 10001 --home-dir /var/lib/med-collect --no-create-home --shell /usr/sbin/nologin medcollect \
    && install -d -m 0750 -o 10001 -g 10001 /var/lib/med-collect

WORKDIR /var/lib/med-collect
USER 10001:10001
EXPOSE 18085
# Seed Tomcat session IDs without blocking on the deployment host's entropy pool.
ENTRYPOINT ["java", "-Xms128m", "-Xmx768m", "-Djava.awt.headless=true", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/med-collect.jar", "--spring.profiles.active=oracle"]
