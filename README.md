# API Gateway
*******************************************************
# Directory Structure:

api-gateway/
├── src/
├── pom.xml
├── application-local.yml
├── application-local-discovery.yml
├── application-local-redis.yml
├── docker-compose-local.yml
├── prometheus/
│   └── prometheus.yml
├── mock-config/
│   ├── mock-auth.json
│   └── mock-device.json
├── grafana/
│   ├── dashboards/
│   └── datasources/
├── Makefile
├── run-local.sh
└── README-local.md

## Quick Start

### Option 1: Using Make (Recommended)

```bash
#Build and start everything
make start

#	Or run locally without Docker
make dev

#	Check service status
make health
******************************************
******************************************
Option 2: Using Script
bash
# Make script executable
chmod +x run-local.sh

# Run the script
./run-local.sh
******************************************
******************************************
Option 3: Manual
bash
# 1. Start infrastructure
docker-compose -f docker-compose-local.yml up -d redis eureka-server

# 2. Build and run
mvn clean package -DskipTests
SPRING_PROFILES_ACTIVE=local-dev java -jar target/api-gateway-*.jar

*******************************************
*******************************************

Option 3: Manual
bash
# 1. Start infrastructure
docker-compose -f docker-compose-local.yml up -d redis eureka-server

# 2. Build and run
mvn clean package -DskipTests
SPRING_PROFILES_ACTIVE=local-dev java -jar target/api-gateway-*.jar
Service Ports
API Gateway: http://localhost:8080

Eureka Dashboard: http://localhost:8761

Redis: localhost:6379

Prometheus: http://localhost:9090

Grafana: http://localhost:3000 (admin/admin)

Development Profiles
local-dev (Default)
Uses local service URLs

Mock authentication

Debug logging enabled

local-discovery
Uses Eureka for service discovery

Real service discovery

local-redis
Embedded Redis for rate limiting

Local Redis configuration

Testing Endpoints
Health & Metrics
bash
# Health check
curl http://localhost:8080/admin/health

# Metrics
curl http://localhost:8080/admin/metrics

# Gateway routes
curl http://localhost:8080/admin/gateway/routes
Mock Services
bash
# Mock authentication
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'

# Mock devices
curl http://localhost:8080/api/v1/devices \
  -H "Authorization: Bearer mock-jwt-token"
Configuration Files
application-local.yml - Main local configuration

application-local-discovery.yml - With Eureka

application-local-redis.yml - Redis configuration

docker-compose-local.yml - Local services

Monitoring Stack
Start monitoring:

bash
make monitor
Access:

Prometheus: http://localhost:9090

Grafana: http://localhost:3000 (admin/admin)

Common Issues
Port already in use
bash
# Find and kill process
lsof -ti:8080 | xargs kill -9
Redis connection failed
bash
# Start Redis manually
docker run -d -p 6379:6379 redis:alpine
Build errors
bash
# Clean and rebuild
mvn clean
mvn package -DskipTests
Environment Variables
Set these for local development:

bash
export SPRING_PROFILES_ACTIVE=local-dev
export REDIS_HOST=localhost
export SERVER_PORT=8080
Debugging
Enable debug logging:

yaml
# In application-local.yml
logging.level.com.iotplatform: DEBUG
logging.level.org.springframework.cloud.gateway: DEBUG
View logs:

bash
# Docker logs
make logs

# Or directly
docker logs -f api-gateway-local
Next Steps
Test the gateway is running: curl http://localhost:8080/admin/health

Register services with Eureka

Test rate limiting and circuit breakers

Set up monitoring dashboards

text

## **How to Start Locally:**

### **Quick Start:**
```bash
# 1. Clone/Create the project structure
# 2. Make script executable
chmod +x run-local.sh

# 3. Run the startup script
./run-local.sh

# OR use Make commands
make infra-up   # Start Redis and Eureka
make dev        # Start API Gateway
********************************************
*********************************************
This setup provides a complete local development environment with:

API Gateway running on port 8080

Eureka service discovery (optional)

Redis for rate limiting

Mock services for testing

Monitoring stack (Prometheus + Grafana)

Easy commands via Makefile

Health checks and debugging tools


