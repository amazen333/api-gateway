#!/bin/bash

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}IoT Platform API Gateway Deployment${NC}"
echo -e "${BLUE}========================================${NC}"

# Load environment
ENV=${1:-production}
echo -e "${YELLOW}Environment: ${ENV}${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { 
    echo -e "${RED}Docker is required but not installed.${NC}"; exit 1; 
}
command -v docker-compose >/dev/null 2>&1 || { 
    echo -e "${RED}Docker Compose is required but not installed.${NC}"; exit 1; 
}

# Load environment variables
if [ -f ".env.${ENV}" ]; then
    echo -e "${YELLOW}Loading environment from .env.${ENV}${NC}"
    export $(grep -v '^#' .env.${ENV} | xargs)
else
    echo -e "${YELLOW}No .env.${ENV} found, using defaults${NC}"
fi

# Build
echo -e "\n${BLUE}Building Docker image...${NC}"
docker build -t iot-platform/api-gateway:latest \
    --build-arg BUILD_PROFILE=${ENV} \
    --build-arg JAR_FILE=target/*.jar .

# Test
echo -e "\n${BLUE}Running tests...${NC}"
if [ "$ENV" != "production" ]; then
    docker run --rm \
        -e SPRING_PROFILES_ACTIVE=test \
        iot-platform/api-gateway:latest \
        ./mvnw test -B
fi

# Deploy
echo -e "\n${BLUE}Deploying with Docker Compose...${NC}"
docker-compose -f docker-compose.${ENV}.yml down
docker-compose -f docker-compose.${ENV}.yml up -d --build --scale api-gateway=3

# Wait for services
echo -e "\n${BLUE}Waiting for services to be healthy...${NC}"
sleep 30

# Health check
echo -e "\n${BLUE}Performing health check...${NC}"
HEALTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health)
if [ "$HEALTH_STATUS" -eq 200 ]; then
    echo -e "${GREEN}✓ Gateway is healthy${NC}"
else
    echo -e "${RED}✗ Gateway health check failed (Status: ${HEALTH_STATUS})${NC}"
    exit 1
fi

# Performance test
if [ "$ENV" == "production" ]; then
    echo -e "\n${BLUE}Running performance test...${NC}"
    docker run --rm --network=host \
        alpine/curl:latest \
        -s -o /dev/null -w "Response time: %{time_total}s\n" \
        http://localhost:8080/health
fi

echo -e "\n${GREEN}Deployment completed successfully!${NC}"
echo -e "${BLUE}========================================${NC}"
echo -e "Gateway URL: http://localhost:${GATEWAY_PORT:-8080}"
echo -e "Health: http://localhost:${GATEWAY_PORT:-8080}/health"
echo -e "Metrics: http://localhost:${GATEWAY_PORT:-8080}/internal/metrics"
echo -e "Prometheus: http://localhost:9090"
echo -e "Grafana: http://localhost:3000"
echo -e "${BLUE}========================================${NC}"