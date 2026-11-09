# GELI Warehouse API

A Spring Boot REST API for a shop's inventory. Items group sellable variants; each variant has its own SKU, optional size/color, effective price, and stock. Sales reduce stock and preserve the price charged at the time of sale.

## Requirements

- Java 21 and Maven 3.9+.
- PostgreSQL 17, either an existing server or the supplied Docker Compose service.
- Docker with Compose v2 for the easiest local database setup. The default test suite also uses Docker through Testcontainers.

The application uses Spring Boot 3.5, PostgreSQL, JPA, and Flyway. It runs on a local JVM; Compose starts the database only.

## Run locally

1. Copy `.env.example` to `.env` if you want to customize the local database settings. The checked-in defaults work without a `.env` file.
2. Start PostgreSQL and wait for its health check:

   ```sh
   docker compose up -d --wait
   ```

3. Run the application:

   ```sh
   mvn spring-boot:run
   ```

The API is available at `http://localhost:8080/api`. Flyway creates the schema on startup. A normal startup leaves the inventory empty.

To build and run the packaged application:

```sh
mvn verify
java -jar target/warehouse-api-0.0.1-SNAPSHOT.jar
```

`mvn verify` runs the database integration tests and requires Docker unless you supply the existing-database test configuration below. Stop the local database with `docker compose down`; its named volume preserves the data.

### Existing PostgreSQL or custom credentials

Create a database and an account that can create tables and apply migrations, then set these application environment variables:

| Variable | Default |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/geli_warehouse` |
| `DATABASE_USERNAME` | `geli` |
| `DATABASE_PASSWORD` | `geli_local` |

For example, in PowerShell:

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/geli_warehouse'
$env:DATABASE_USERNAME = 'geli'
$env:DATABASE_PASSWORD = 'geli_local'
mvn spring-boot:run
```

Or in a POSIX shell:

```sh
export DATABASE_URL='jdbc:postgresql://localhost:5432/geli_warehouse'
export DATABASE_USERNAME='geli'
export DATABASE_PASSWORD='geli_local'
mvn spring-boot:run
```

Docker Compose reads `.env` for its database container. Spring Boot does not load that file automatically. If you change the password, export `DATABASE_PASSWORD` for the application as well; if you change `POSTGRES_PORT`, update the port in `DATABASE_URL`. Compose credentials initialize an empty volume; editing `.env` later does not change an existing database account's password. The example credentials are intended for local development.

## API examples

These examples use `curl` in a POSIX shell such as Bash or Git Bash, and a fresh database. Replace IDs with those returned by your requests.

Create an item with two sellable variants:

```sh
curl -i -X POST http://localhost:8080/api/items -H 'Content-Type: application/json' -d '{"name":"Everyday T-Shirt","description":"Cotton crew neck","basePrice":125000.00,"variants":[{"sku":"TEE-BLK-M","size":"M","color":"Black","priceOverride":null,"initialStock":10},{"sku":"TEE-BLK-L","size":"L","color":"Black","priceOverride":135000.00,"initialStock":5}]}'
```

An item without size or color options still has one sellable variant:

```sh
curl -i -X POST http://localhost:8080/api/items -H 'Content-Type: application/json' -d '{"name":"Notebook","description":"A5 lined notebook","basePrice":25000.00,"variants":[{"sku":"NOTE-A5","size":null,"color":null,"priceOverride":null,"initialStock":20}]}'
```

List and inspect items:

```sh
curl 'http://localhost:8080/api/items?page=0&size=20&name=shirt'
curl http://localhost:8080/api/items/1
curl 'http://localhost:8080/api/items/1/variants?page=0&size=20'
```

Update item details and add a variant:

```sh
curl -X PUT http://localhost:8080/api/items/1 -H 'Content-Type: application/json' -d '{"name":"Everyday T-Shirt","description":"Cotton crew neck","basePrice":130000.00}'
curl -i -X POST http://localhost:8080/api/items/1/variants -H 'Content-Type: application/json' -d '{"sku":"TEE-WHT-M","size":"M","color":"White","priceOverride":null,"initialStock":8}'
```

Restock and sell a variant:

```sh
curl -X POST http://localhost:8080/api/variants/1/restocks -H 'Content-Type: application/json' -d '{"quantity":5}'
curl -i -X POST http://localhost:8080/api/sales -H 'Content-Type: application/json' -d '{"variantId":1,"quantity":2}'
curl http://localhost:8080/api/sales/1
curl 'http://localhost:8080/api/sales?page=0&size=20'
```

In PowerShell, the same JSON bodies can be sent with `Invoke-RestMethod`, for example:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/variants/1/restocks' -ContentType 'application/json' -Body '{"quantity":5}'
```

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/items` | Create an item with 1–100 variants |
| `GET` | `/api/items` | List items; optional `name`, `page`, and `size` |
| `GET` | `/api/items/{itemId}` | Get an item and its variants |
| `PUT` | `/api/items/{itemId}` | Replace item name, description, and base price |
| `DELETE` | `/api/items/{itemId}` | Delete an item and its unsold variants |
| `POST` | `/api/items/{itemId}/variants` | Add a sellable variant |
| `GET` | `/api/items/{itemId}/variants` | List an item's variants; `page` and `size` |
| `GET` | `/api/variants/{variantId}` | Get one variant |
| `PUT` | `/api/variants/{variantId}` | Replace SKU, size, color, and price override |
| `DELETE` | `/api/variants/{variantId}` | Delete an unsold variant, keeping at least one |
| `POST` | `/api/variants/{variantId}/restocks` | Increase stock by a positive quantity |
| `POST` | `/api/sales` | Sell a positive quantity of one variant |
| `GET` | `/api/sales/{saleId}` | Get a recorded sale |
| `GET` | `/api/sales` | List sales; `page` and `size` |

Creation returns `201 Created` with a `Location` header. Reads and updates return `200 OK`; deletion returns `204 No Content`. Paged responses use `content`, `page`, `size`, `totalElements`, and `totalPages`. Page indexes start at zero; the default page size is 20 and the maximum is 100. Sales are listed newest first.

### Errors

Errors use RFC 9457 Problem Details with the `application/problem+json` media type. For example, an invalid sale quantity returns `400 Bad Request`:

```json
{
  "type": "urn:geli:problem:validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/sales",
  "code": "VALIDATION_ERROR",
  "errors": [
    { "field": "quantity", "message": "Quantity must be greater than zero." }
  ]
}
```

The exact detail and validation messages depend on the failure. The `errors` array is empty for failures without field errors. Invalid input returns `400`; a missing resource returns `404`. Insufficient stock, duplicate variants/SKUs, or deletion that would violate inventory/history rules returns `409`. Temporary database contention or unavailability returns `503`. Error responses do not expose SQL or stack traces.

## Design decisions and assumptions

- **One deployable application.** Feature packages separate item, variant, and sale behavior. Controllers handle HTTP and DTOs; services own transactions and business rules; repositories handle persistence. Entities are not the API contract.
- **Variants are the stock source of truth.** Every item is created with at least one explicit variant. An optionless item uses a variant with absent size and color. There is no separate parent stock counter to keep synchronized.
- **Price inheritance.** An item has a base price. A variant's nullable price override takes precedence; otherwise it inherits the current item base price. Monetary values use decimal arithmetic and two fractional digits. The API assumes one currency and does not perform currency conversion.
- **Variant identity.** SKUs are stored in uppercase and must be unique. Trimmed size/color combinations are unique within an item, ignoring case; blank or missing values represent an absent option. Each item supports at most 100 variants.
- **Stock changes are explicit.** Every new variant requires a nonnegative `initialStock`, including zero for an empty variant. Normal item/variant updates cannot replace stock; restocks increase it and sales decrease it. Quantities are integers, prices and stock cannot be negative, and sale/restock quantities must be positive.
- **Sales are transactional.** The stock check, deduction, and sale insert run in one transaction while holding database row locks. A sale records the charged price and identifying details so later catalog changes do not rewrite history. Concurrent sales cannot reduce stock below zero.
- **Simple locking policy.** Mutations lock the item before the relevant variant. This also serializes mutations to different variants of the same item, trading some concurrency for straightforward consistency within this project's scope.
- **Consistent reads.** Read operations use repeatable-read transactions so item details and paginated results are built from one database snapshot. Write operations use PostgreSQL's default read-committed isolation with explicit row locks.
- **Deletion preserves history.** A sold variant cannot be deleted. The final variant of an item cannot be deleted independently. Deleting an item removes its unsold variants together, but fails if any variant has sale history.
- **Persistence is explicit.** PostgreSQL constraints support application validation; Flyway owns schema changes. The Compose database uses a named volume so application/container restarts retain inventory and sales.
- **Deliberate scope.** A sale contains one variant. Authentication, customers, payment, multi-line orders, returns, stock reservations, and tax are outside this assessment. There is no idempotency key: retrying a successful sale request creates another sale.

## Tests and CI

Run the full suite:

```sh
mvn verify
```

By default, Testcontainers starts PostgreSQL 17 using Docker. The tests exercise application request handling and PostgreSQL persistence, including sale boundaries, rollback, and concurrent stock changes. GitHub Actions runs `mvn -B verify` on Java 21 with Docker available on the Ubuntu runner.

If Docker is unavailable, provide an isolated PostgreSQL 17 test database and credentials:

```sh
mvn verify -Dtest.database.url=jdbc:postgresql://localhost:5432/geli_warehouse_test -Dtest.database.username=geli -Dtest.database.password=geli_local
```

The test account must be able to create schemas. Tests use a randomly named schema, reset only its tables between tests, and drop that schema after each test class. Application tables are not reset. Use a dedicated test database/account so test resources remain separate from development inventory.
