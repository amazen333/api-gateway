# ============================================
# Multi-stage build for optimal image size
# ============================================

# Build stage
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml and Maven wrapper first (dependency caching)
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn

# Download dependencies (offline mode)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code
COPY src src

# Build application with production profile
RUN ./mvnw clean package -DskipTests -Pproduction \
	-Dmaven.test.skip=true \
	-Dmaven.javadoc.skip=true \
	-Dmaven.source.skip=true

# ============================================
# Runtime stage
# ============================================
FROM eclipse-temurin:17-jre-alpine AS runtime

# Performance tuning options
ENV JAVA_OPTS="\
	-XX:MaxRAMPercentage=75.0 \
	-XX:+UseG1GC \
	-XX:MaxGCPauseMillis=100 \
	-XX:+UseStringDeduplication \
	-XX:+UseContainerSupport \
	-XX:InitialRAMPercentage=25.0 \
	-XX:MinRAMPercentage=50.0 \
	-XX:+AlwaysPreTouch \
	-XX:+OptimizeStringConcat \
	-XX:+UseCompressedOops \
	-XX:+UseCompressedClassPointers \
	-XX:+ExitOnOutOfMemoryError \
	-Djava.security.egd=file:/dev/./urandom \
	-Dio.netty.leakDetection.level=DISABLED \
	-Dio.netty.allocator.type=pooled \
	-Dio.netty.noPreferDirect=false \
	-Dreactor.netty.pool.leasingStrategy=lifo \
	-Dspring.main.lazy-initialization=true"

# Create non-root user
RUN addgroup -S gateway && adduser -S gateway -G gateway

WORKDIR /app

# Copy built artifact
COPY --from=builder /app/target/*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs && chown -R gateway:gateway /app

USER gateway

# Health check (configurable path)
ENV HEALTH_PATH=/actuator/health
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
	CMD wget -qO- http://localhost:8080${HEALTH_PATH} || exit 1

# Expose port
EXPOSE 8080

# Security hardening
# (read-only filesystem, drop capabilities)
VOLUME /tmp
RUN chmod 755 /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
