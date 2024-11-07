FROM maven:3.9.5-eclipse-temurin-21 AS base_build_image

# Produce a small Java runtime that contains only what we need.
# ------------------------------------------------------------------------------
# Module             Class from module                       Used by
# ------------------------------------------------------------------------------
# java.datatransfer  java.awt.datatransfer.Transferrable     jakarta.activation
# java.logging       java.util.logging.Logger                hk2
# java.management    java.lang.management.ManagementFactory  maven-surefire-plugin, bw.status.service.HealthChecker
# java.naming        javax.naming.NamingException            logback
# java.net.http      java.net.http.HttpClient                bw.status.testlib.HttpTester
# java.xml           org.xml.sax.InputSource                 logback
# jdk.crypto.ec      sun.security.ec.SunEC                   jakarta.mail (for STARTTLS)
# jdk.jdwp.agent     (native code)                           java -agentlib:jdwp
# jdk.unsupported    sun.misc.Unsafe                         jboss-threads
# jdk.zipfs          jdk.nio.zipfs.ZipFileSystemProvider     bw.status.util.ZipFiles
# ------------------------------------------------------------------------------
FROM base_build_image AS build_jre
RUN jlink --add-modules java.datatransfer,java.logging,java.management,java.naming,java.net.http,java.xml,jdk.crypto.ec,jdk.jdwp.agent,jdk.unsupported,jdk.zipfs \
          --output /opt/jre

FROM base_build_image AS build_app
WORKDIR /bwstatus
COPY --from=build_jre /opt/jre /opt/jre
COPY .git .git
COPY pom.xml pom.xml
COPY src src
ARG SKIP_TESTS=false
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn package --batch-mode \
                -Djvm=/opt/jre/bin/java \
                -DskipTests="${SKIP_TESTS}"
# To debug surefire VM crashes, append this to the previous line:
# || (cat target/surefire-reports/* && exit 1)

FROM debian:bookworm-slim AS run_app
WORKDIR /bwstatus
COPY --from=build_jre /opt/jre /opt/jre
ENV JAVA_HOME "/opt/jre"
ENV PATH "${JAVA_HOME}/bin:${PATH}"
COPY --from=build_app /bwstatus/target/lib lib
COPY --from=build_app /bwstatus/target/bw-status.jar bw-status.jar

ENTRYPOINT [ "java", "-jar", "bw-status.jar" ]
