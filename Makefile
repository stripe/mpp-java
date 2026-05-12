.PHONY: node node-stop test test-integration

node:
	docker compose up -d --wait

node-stop:
	docker compose down

test:
	./gradlew test

test-integration: node
	TEMPO_RPC_URL=http://localhost:8545 ./gradlew integrationTest
	$(MAKE) node-stop
