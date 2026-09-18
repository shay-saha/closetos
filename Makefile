.PHONY: dependencies api test-api format-api

dependencies:
	docker compose up -d --wait

api:
	cd apps/api && ./mvnw spring-boot:run

test-api:
	cd apps/api && ./mvnw verify

format-api:
	cd apps/api && ./mvnw spotless:apply
