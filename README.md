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

************************************************
#### `api-gateway/`
- **Purpose**: Single entry point, routing, and cross-cutting concerns
- **Technology**: Spring Cloud Gateway
- **Features**: Rate limiting, authentication, load balancing
 



