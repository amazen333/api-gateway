.PHONY: help start stop restart logs build test clean

help:
	@echo "Available commands:"
	@echo "  make start      - Start all services (gateway + dependencies)"
	@echo "  make stop       - Stop all services"
	@echo "  make restart    - Restart all services"
	@echo "  make logs       - Show logs from all services"
	@echo "  make build      - Build the API Gateway"
	@echo "  make test       - Run tests"
	@echo "  make clean      - Clean build artifacts"
	@echo "  make infra-up   - Start only infrastructure (Redis, Eureka)"
	@echo "  make infra-down - Stop only infrastructure"
	@echo "  make dev        - Start in development mode"
	@echo "  make monitor    - Start monitoring stack"

# Docker commands
start:
	docker-compose -f /docker/docker-compose-local.yml up -d

stop:
	docker-compose -f /docker/docker-compose-local.yml down

restart: stop start

logs:
	docker-compose -f /docker/docker-compose-local.yml logs -f api-gateway

# Build commands
build:
	mvn clean package -DskipTests

test:
	mvn test

clean:
	mvn clean
	rm -rf target logs

# Infrastructure only
infra-up:
	docker-compose -f /docker/docker-compose-local.yml up -d redis eureka-server

infra-down:
	docker-compose -f /docker/docker-compose-local.yml stop redis eureka-server

# Development
dev:
	SPRING_PROFILES_ACTIVE=local-dev mvn spring-boot:run

# Monitoring
monitor:
	docker-compose -f /docker/docker-compose-local.yml up -d prometheus grafana

# Health checks
health:
	curl -f http://localhost:8080/admin/health || echo "Gateway is down"
	curl -f http://localhost:8761/ || echo "Eureka is down"
	redis-cli -h localhost -p 6379 ping || echo "Redis is down"

# Ports info
ports:
	@echo "Service Ports:"
	@echo "  API Gateway:     http://localhost:8080"
	@echo "  Eureka Server:   http://localhost:8761"
	@echo "  Redis:           localhost:6379"
	@echo "  Prometheus:      http://localhost:9090"
	@echo "  Grafana:         http://localhost:3000 (admin/admin)"
	@echo "  Mock Auth:       http://localhost:8081"
	@echo "  Mock Device:     http://localhost:8082"

# Quick test
test-api:
	@echo "Testing API Gateway endpoints..."
	@echo "1. Health check:"
	curl -s http://localhost:8080/admin/health | jq .status || echo "Failed"
	@echo "\n2. Actuator endpoints:"
	curl -s http://localhost:8080/admin/metrics | jq '.names[]' | head -5 || echo "Failed"
	@echo "\n3. Gateway routes:"
	curl -s http://localhost:8080/admin/gateway/routes | jq '.[].predicates' || echo "Failed"