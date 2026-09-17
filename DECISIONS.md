# Decision Log

## 1. How did you handle the concurrency race condition?

The main race condition occurs when multiple requests attempt to debit the same wallet concurrently.

If two requests read the wallet balance at the same time before either request updates it, both requests could potentially pass the balance check and cause an incorrect wallet balance.

To prevent this, I used **pessimistic database-level locking** with `PESSIMISTIC_WRITE`.

The wallet repository uses:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
Optional<Wallet> findWalletForUpdate(UUID userId);
```

Hibernate translates this into a database-level `FOR UPDATE` lock.

The transaction processing method is also annotated with:

```java
@Transactional
```

This ensures that acquiring the wallet lock, checking the transaction, checking the balance, deducting the amount, and saving the transaction all occur within the same database transaction.

The processing order is:

```text
Request
   ↓
Acquire wallet PESSIMISTIC_WRITE lock
   ↓
Check transactionId
   ↓
Check balance
   ↓
Deduct amount
   ↓
Save transaction
   ↓
Commit
```

This serializes concurrent operations on the same wallet.

For duplicate concurrent requests, the first request obtains the lock and processes the transaction. Other requests wait for the lock and then see that the transaction ID has already been processed.

For concurrent debit requests, each request checks the latest wallet balance while holding the lock. Therefore, once the balance reaches zero, further debit requests fail with insufficient funds instead of causing a negative balance.

The implementation is verified by the required concurrency tests:

- 3 identical concurrent requests → 1 succeeds, 2 fail, final balance = ₹400.
- 10 concurrent ₹100 debit requests against ₹500 → 5 succeed, 5 fail, final balance = ₹0.

---

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

During development, the initial approach suggested checking whether the transaction already existed before acquiring the wallet lock.

The flow was initially considered as:

```text
Check transactionId
   ↓
If transaction does not exist
   ↓
Acquire wallet lock
   ↓
Check balance
   ↓
Process transaction
```

This is sub-optimal for concurrent duplicate requests because multiple requests could perform the transaction existence check at nearly the same time and all see that the transaction does not yet exist.

For example:

```text
Request A → transaction does not exist
Request B → transaction does not exist
Request C → transaction does not exist
```

They could then all continue toward processing.

I changed the implementation so that the wallet lock is acquired first:

```text
Acquire wallet lock
   ↓
Check transactionId
   ↓
Check balance
   ↓
Process transaction
```

This ensures that concurrent requests for the same wallet are serialized before the idempotency check.

The database also has a unique constraint on `transactionId` as an additional protection against duplicate transaction records.

This was an important correction because the concurrency requirement cannot be reliably handled by an unlocked `existsByTransactionId()` check alone.
