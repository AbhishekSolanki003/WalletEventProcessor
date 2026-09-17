# Wallet Event Processor

A Spring Boot backend application that processes payment gateway wallet events safely and idempotently.

The application is designed to handle duplicate webhook requests and concurrent debit requests without deducting the wallet balance more than once or allowing the balance to become negative.

---

## Problem Statement

Payment gateways may send the same webhook multiple times because of network failures, retries, or delayed responses.

For example, the same transaction may be received three times almost simultaneously.

The application must ensure that:

- A transaction is processed only once.
- Duplicate requests with the same `transactionId` do not deduct the wallet balance multiple times.
- Concurrent debit requests are handled safely.
- The wallet balance never becomes negative.
- Database-level locking is used to prevent race conditions.
- The application works correctly when multiple requests arrive concurrently.

---

## Tech Stack

- Java 17+
- Spring Boot 4.1.1
- Spring Data JPA
- Hibernate ORM
- H2 In-Memory Database
- Maven
- JUnit 5
- Lombok
- Jakarta Bean Validation

---

## Features

- REST API for processing wallet transactions
- Idempotent transaction processing
- Duplicate transaction detection
- Pessimistic database locking
- Safe concurrent wallet updates
- Insufficient balance validation
- Request validation using Jakarta Bean Validation
- Global exception handling
- H2 in-memory database
- Integration tests for concurrent requests

---

## API

### Process Transaction

**Endpoint**

```text
POST /api/v1/transactions/process
```

### Request Body

```json
{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "22222222-2222-2222-2222-222222222222",
  "amount": 100.00,
  "type": "DEBIT"
}
```

### Request Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `transactionId` | UUID | Yes | Unique identifier of the transaction and idempotency key |
| `userId` | UUID | Yes | Identifier of the wallet owner |
| `amount` | BigDecimal | Yes | Amount to debit; must be greater than zero |
| `type` | TransactionType | Yes | Transaction type; currently supports `DEBIT` |

---

## API Responses

### Successful Transaction

HTTP Status:

```text
200 OK
```

Response:

```text
Transaction processed successfully
```

---

### Duplicate Transaction

If the same `transactionId` has already been processed:

HTTP Status:

```text
409 CONFLICT
```

Response:

```text
Transaction already processed
```

The duplicate request does not deduct the wallet balance again.

---

### Insufficient Funds

If the wallet does not have enough balance:

HTTP Status:

```text
409 CONFLICT
```

Response:

```text
Insufficient funds
```

---

### Wallet Not Found

If no wallet exists for the supplied `userId`:

HTTP Status:

```text
404 NOT FOUND
```

Response:

```text
Wallet not found
```

---

### Invalid Request

Invalid or missing request fields result in:

HTTP Status:

```text
400 BAD REQUEST
```

Examples include:

- Missing transaction ID
- Missing user ID
- Missing amount
- Amount less than or equal to zero
- Missing transaction type

---

## Database

The application uses an H2 in-memory database.

Database URL:

```text
jdbc:h2:mem:walletdb
```

The database schema is recreated when the application starts.

### Wallet

The `Wallet` entity contains:

- `id`
- `userId`
- `balance`

The `userId` column is unique, so each user has one wallet.

### Transaction

The `Transaction` entity contains:

- `id`
- `transactionId`
- `userId`
- `amount`
- `type`

The `transactionId` column has a unique database constraint to support idempotency.

---

## Concurrency Handling

Concurrency is handled using pessimistic database locking.

The wallet repository uses:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

with the following query:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
Optional<Wallet> findWalletForUpdate(UUID userId);
```

Hibernate translates this into a database query using:

```sql
FOR UPDATE
```

This locks the wallet row while the transaction is being processed.

The transaction processing method is also annotated with:

```java
@Transactional
```

This ensures that wallet locking, transaction checking, balance validation, balance update, and transaction persistence occur within the same database transaction.

### Processing Flow

```text
Request
   |
   v
Find wallet and acquire PESSIMISTIC_WRITE lock
   |
   v
Check whether transactionId was already processed
   |
   v
Check wallet balance
   |
   v
Deduct amount
   |
   v
Save transaction
   |
   v
Commit transaction
```

Because the wallet row is locked, concurrent requests for the same wallet are processed one at a time.

---

## Idempotency

Idempotency ensures that processing the same transaction multiple times produces only one financial effect.

The application uses `transactionId` as the idempotency key.

The database also enforces uniqueness:

```java
@Column(nullable = false, unique = true)
private UUID transactionId;
```

Before processing the transaction, the application checks whether the transaction ID has already been processed.

Therefore, if the same webhook is received multiple times, only one request performs the debit.

Example:

```text
Initial Balance = ₹500

Request 1 -> Transaction processed -> ₹400
Request 2 -> Duplicate -> ₹400
Request 3 -> Duplicate -> ₹400
```

The balance is deducted only once.

---

## Why the Wallet Is Locked Before the Duplicate Check

The wallet lock is acquired before checking the transaction ID.

This is important for concurrent duplicate requests.

Without proper locking, two requests could both perform:

```text
Check transactionId -> Not found
```

before either request inserts the transaction.

Both requests could then continue and deduct the wallet balance.

With the wallet row locked:

```text
Request A -> Locks wallet -> Checks transaction -> Processes -> Commits
Request B -> Waits for wallet lock
Request C -> Waits for wallet lock
```

After Request A commits, the waiting requests continue and see that the transaction ID has already been processed.

This prevents the same transaction from being applied more than once.

---

## Transaction Safety

The wallet balance is checked before performing the debit:

```java
if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
    throw new InsufficientFundsException("Insufficient funds");
}
```

The balance is then updated using `BigDecimal`:

```java
wallet.setBalance(
    wallet.getBalance().subtract(request.getAmount())
);
```

`BigDecimal` is used for monetary values to avoid floating-point precision issues.

---

## Transaction Boundary

The service method is transactional:

```java
@Transactional
public void processTransaction(TransactionRequest request) {
    ...
}
```

The database operations involved in processing a transaction are therefore part of one transaction.

If an exception occurs during processing, the transaction can be rolled back instead of leaving a partial update.

---

## Database Constraints

The database provides additional protection through constraints.

### Unique Transaction ID

```text
transaction_id UUID NOT NULL UNIQUE
```

This prevents multiple transaction records with the same transaction ID.

### Unique User ID

```text
user_id UUID NOT NULL UNIQUE
```

in the wallet table ensures that a user has only one wallet.

### Non-null Fields

Important wallet and transaction fields are configured as non-null to prevent incomplete records.

---

## Project Structure

```text
WalletEventProcessor/
|
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── wallet/
│   │   │           |
│   │   │           ├── controller/
│   │   │           │   └── TransactionController.java
│   │   │           |
│   │   │           ├── dto/
│   │   │           │   └── TransactionRequest.java
│   │   │           |
│   │   │           ├── entity/
│   │   │           │   ├── Wallet.java
│   │   │           │   ├── Transaction.java
│   │   │           │   └── TransactionType.java
│   │   │           |
│   │   │           ├── exception/
│   │   │           │   ├── GlobalExceptionHandler.java
│   │   │           │   ├── InsufficientFundsException.java
│   │   │           │   └── WalletNotFoundException.java
│   │   │           |
│   │   │           ├── repository/
│   │   │           │   ├── WalletRepository.java
│   │   │           │   └── TransactionRepository.java
│   │   │           |
│   │   │           ├── service/
│   │   │           │   ├── TransactionService.java
│   │   │           │   └── TransactionServiceImpl.java
│   │   │           |
│   │   │           └── WallEventProcessorApplication.java
│   │   │
│   │   └── resources/
│   │       └── application.properties
│   │
│   └── test/
│       └── java/
│           └── com/
│               └── wallet/
│                   ├── TransactionIntegrationTest.java
│                   └── WallEventProcessorApplicationTests.java
│
├── .gitignore
├── HELP.md
├── mvnw
├── mvnw.cmd
├── pom.xml
├── README.md
└── DECISIONS.md
```

---

## Running the Application

### Prerequisites

Make sure the following are installed:

- Java 17 or higher
- Git
- Maven (optional because the Maven Wrapper is included)

### Run Using Maven Wrapper

On Windows:

```bash
mvnw.cmd spring-boot:run
```

The application starts on:

```text
http://localhost:8080
```

---

## Running Tests

Run all tests using:

```bash
mvnw.cmd clean test
```

The tests use JUnit 5 and Spring Boot integration testing.

---

## Integration Tests

The project contains integration tests for the required concurrency scenarios.

### Test 1: Single Valid Debit

A wallet with a balance of ₹500 receives a ₹100 debit request.

Expected result:

```text
Transaction succeeds
Final balance = ₹400
```

---

### Test 2: Three Identical Concurrent Transactions

Three requests containing the same `transactionId` are sent simultaneously.

Expected result:

```text
1 request succeeds
2 requests fail as duplicates
Final balance = ₹400
```

This verifies idempotency.

---

### Test 3: Ten Concurrent Debit Requests

Ten concurrent requests are sent to a wallet containing ₹500.

Each request attempts to debit ₹100.

Expected result:

```text
5 requests succeed
5 requests fail due to insufficient funds
Final balance = ₹0
```

This verifies that concurrent requests cannot overdraw the wallet.

---

## Test Result

The complete Maven test suite passes successfully.

```text
Tests run: 4
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

Required integration test results:

```text
Single debit:
Success
Final balance = ₹400.00

Three identical concurrent transactions:
Success = 1
Failures = 2
Final balance = ₹400.00

Ten concurrent debit requests:
Success = 5
Failures = 5
Final balance = ₹0.00
```

---

## Testing Approach

The concurrency tests use Java's `ExecutorService` and `CountDownLatch`.

`CountDownLatch` is used to make multiple worker threads start processing at approximately the same time.

This creates realistic concurrent access to the same wallet and helps expose race conditions.

For example:

```text
             Start
               |
       +-------+-------+
       |       |       |
    Thread 1 Thread 2 Thread 3
       |       |       |
       +-------+-------+
               |
        Concurrent requests
               |
        Database lock
               |
        Safe processing
```

The tests then verify both:

1. The number of successful and failed requests.
2. The final wallet balance.

---

## Validation

The API uses Jakarta Bean Validation.

The request DTO validates:

```java
@NotNull
private UUID transactionId;

@NotNull
private UUID userId;

@NotNull
@Positive
private BigDecimal amount;

@NotNull
private TransactionType type;
```

This prevents invalid requests from reaching the transaction processing logic.

---

## Exception Handling

A global exception handler is implemented using:

```java
@RestControllerAdvice
```

It handles:

- `InsufficientFundsException`
- `WalletNotFoundException`
- Duplicate transaction errors
- `MethodArgumentNotValidException`

This keeps error responses consistent across the API.

---

## H2 Database

The project uses an in-memory H2 database as required.

Configuration:

```properties
spring.datasource.url=jdbc:h2:mem:walletdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
spring.jpa.open-in-view=false
```

The application uses `READ_COMMITTED` transaction isolation provided by the database.

Hibernate SQL logging is enabled to make database operations and locking behavior visible during development and testing.

---

## Important Design Choices

### Pessimistic Locking

`PESSIMISTIC_WRITE` is used because wallet balance updates are sensitive to concurrent modifications.

The database locks the wallet row so another transaction cannot modify the same wallet until the current transaction completes.

### Unique Transaction ID

The database has a unique constraint on `transactionId` to provide database-level protection against duplicate transaction records.

### BigDecimal

`BigDecimal` is used for money instead of `double` or `float`.

### Service-Level Transaction

`@Transactional` is placed on the service method so the database lock and all processing operations share the same transaction boundary.

### Integration Testing

Concurrency is tested using real Spring Boot application components and the H2 database rather than only mocking the service layer.

---

## Documentation

Additional architectural and implementation decisions are documented in:

```text
DECISIONS.md
```

This document explains:

- How the concurrency race condition was handled.
- Why pessimistic locking was selected.
- Why the duplicate transaction check is performed after acquiring the wallet lock.
- How database constraints provide an additional idempotency guarantee.
- An AI-generated suggestion that was identified as incorrect or suboptimal and how it was corrected.

---

## Build Verification

The project can be verified using:

```bash
mvnw.cmd clean test
```

A successful build should end with:

```text
BUILD SUCCESS
```

and:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

---

## Author

**Abhishek Solanki**

Java Backend Intern Assignment
