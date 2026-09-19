.PHONY: setup dependencies api web test-api test-web test-e2e format-api

setup:
	python3 scripts/prepare-local-env.py
	pnpm install --frozen-lockfile

dependencies:
	docker compose up -d --wait
	python3 scripts/wait-for-http.py http://localhost:8081/realms/closetos/.well-known/openid-configuration

api:
	./scripts/run-api.sh

web:
	pnpm dev

test-web:
	pnpm check
	pnpm test
	pnpm build

test-e2e:
	pnpm test:e2e

test-api:
	cd apps/api && ./mvnw verify

format-api:
	cd apps/api && ./mvnw spotless:apply
