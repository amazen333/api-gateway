#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}Starting IoT Platform API Gateway${NC}"
echo -e "${BLUE}========================================${NC}"

# Check if Java 17+ is installed
if ! command -v java &> /dev/null; then
    echo -e "${RED}Java is not installed. Please install Java 17 or higher.${NC}"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo -e "${RED}Java 17 or higher is required. Found version: $JAVA_VERSION${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Java version: $(java -version 2>&1 | head -1)${NC}"

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Maven is not installed. Please install Maven.${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Maven version: $(mvn --version | head -1)${NC}"

# Check if Docker is running (for infrastructure)
if command -v docker &> /dev/null; then
    if docker info &> /dev/null; then
        echo -e "${GREEN}✓ Docker is running${NC}"
        
        # Start infrastructure if not running
        if ! docker ps | grep -q "redis"; then
            echo -e "${YELLOW}Starting Redis...${NC}"
            docker run -d --name redis-local -p 6379:6379 redis:7-alpine
        fi
        
        if ! docker ps | grep -q "eureka-server"; then
            echo -e "${YELLOW}Starting Eureka Server...${NC}"
            docker run -d --name eureka-local -p 8761:8761 springcloud/eureka
        fi
    else
        echo -e "${YELLOW}⚠ Docker is installed but not running. Using embedded mode.${NC}"
    fi
else
    echo -e "${YELLOW}⚠ Docker not installed. Using embedded mode where possible.${NC}"
fi

# Build the project
echo -e "\n${BLUE}Building API Gateway...${NC}"
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Build successful${NC}"

# Run the application
echo -e "\n${BLUE}Starting API Gateway...${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo -e "\n${GREEN}Service URLs:${NC}"
echo -e "  API Gateway:     http://localhost:8080"
echo -e "  Eureka Dashboard: http://localhost:8761"
echo -e "  Health Check:    http://localhost:8080/admin/health"
echo -e "  Metrics:         http://localhost:8080/admin/metrics"
echo -e "  Gateway Routes:  http://localhost:8080/admin/gateway/routes"
echo -e "\n${BLUE}========================================${NC}"

# Run with local development profile
SPRING_PROFILES_ACTIVE=local-dev java -jar target/api-gateway-*.jar