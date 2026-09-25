.PHONY: setup dependencies api web media-worker test-api test-web test-e2e test-worker test-infra format-api

setup:
	python3 scripts/prepare-local-env.py
	pnpm install --frozen-lockfile
	uv sync --project workers/media-processor --locked
	./scripts/prepare-local-models.sh

dependencies:
	docker compose up -d --wait
	python3 scripts/wait-for-http.py http://localhost:8081/realms/closetos/.well-known/openid-configuration
	uv run --project workers/media-processor python scripts/prepare-local-storage.py

media-worker:
	./scripts/run-media-worker.sh

test-worker:
	./scripts/run-worker-tests.sh

test-infra:
	./scripts/run-infrastructure-tests.sh

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
