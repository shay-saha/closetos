#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
: "${APPLICATION_DATABASE_PASSWORD:?Run make setup to prepare application credentials}"
export DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:5432/closetos}"
export DATABASE_USERNAME="${DATABASE_USERNAME:-closetos}"
cd apps/api
./mvnw spring-boot:run -Dspring-boot.run.main-class=com.closetos.platform.infrastructure.DatabaseMigration
export DATABASE_USERNAME=closetos_app
export DATABASE_PASSWORD="${APPLICATION_DATABASE_PASSWORD:?Run make setup to prepare application credentials}"
export SPRING_FLYWAY_ENABLED=false
unset APPLICATION_DATABASE_PASSWORD
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
