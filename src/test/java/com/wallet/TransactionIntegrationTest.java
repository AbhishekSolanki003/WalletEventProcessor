package com.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.wallet.dto.TransactionRequest;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.service.TransactionService;

@SpringBootTest
class TransactionIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private WalletRepository walletRepository;
    
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @BeforeEach
    void setUp() {
    	transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void processesSingleValidDebitSuccessfully() {

        // Arrange
        UUID userId = UUID.randomUUID();

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("500.00"));

        walletRepository.save(wallet);

        TransactionRequest request = new TransactionRequest(
                UUID.randomUUID(),
                userId,
                new BigDecimal("100.00"),
                TransactionType.DEBIT
        );

        // Act
        transactionService.processTransaction(request);

        // Assert
        Wallet updatedWallet = walletRepository
                .findByUserId(userId)
                .orElseThrow();

        assertEquals(
                new BigDecimal("400.00"),
                updatedWallet.getBalance()
        );
        
        System.out.println(
                "PASS: Single debit processed. Final balance = ₹"
                + updatedWallet.getBalance()
        );
    }
    
    
    
    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void sendsThreeIdenticalTransactionsSimultaneously() throws Exception {

        // Arrange
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("500.00"));

        walletRepository.save(wallet);

        TransactionRequest request = new TransactionRequest(
                transactionId,
                userId,
                new BigDecimal("100.00"),
                TransactionType.DEBIT
        );

        int threadCount = 3;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready =
                new CountDownLatch(threadCount);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();

        // Act
        for (int i = 0; i < threadCount; i++) {

            futures.add(executor.submit(() -> {

                ready.countDown();

                // Wait until all threads are ready
                start.await();

                transactionService.processTransaction(request);

                return null;
            }));
        }

        // Wait until all 3 threads are ready
        ready.await();

        // Start all 3 requests
        start.countDown();

        int successCount = 0;
        int failureCount = 0;

        for (Future<?> future : futures) {

            try {
                future.get();
                successCount++;

            } catch (Exception e) {
                failureCount++;
            }
        }

        executor.shutdown();

        // Assert
        Wallet updatedWallet =
                walletRepository.findByUserId(userId).orElseThrow();

        assertEquals(
                new BigDecimal("400.00"),
                updatedWallet.getBalance()
        );

        assertEquals(1, successCount);
        assertEquals(2, failureCount);

        System.out.println(
                "PASS: Idempotency verified. Success = "
                + successCount
                + ", Failures = "
                + failureCount
                + ", Final balance = ₹"
                + updatedWallet.getBalance()
        );
    }
    
    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void sendsTenConcurrentDebits() throws Exception {

        // Arrange
        UUID userId = UUID.randomUUID();

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setBalance(new BigDecimal("500.00"));

        walletRepository.save(wallet);

        ExecutorService executor = Executors.newFixedThreadPool(10);

        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();

        // Act
        for (int i = 0; i < 10; i++) {

            futures.add(executor.submit(() -> {

                ready.countDown();

                // Wait until all 10 threads are ready
                start.await();

                TransactionRequest request = new TransactionRequest(
                        UUID.randomUUID(),
                        userId,
                        new BigDecimal("100.00"),
                        TransactionType.DEBIT
                );

                transactionService.processTransaction(request);

                return null;
            }));
        }

        // Wait until all 10 threads are ready
        ready.await();

        // Start all requests
        start.countDown();

        int successCount = 0;
        int failureCount = 0;

        for (Future<?> future : futures) {

            try {
                future.get();
                successCount++;

            } catch (Exception e) {
                failureCount++;
            }
        }

        executor.shutdown();

        // Assert
        Wallet updatedWallet = walletRepository
                .findByUserId(userId)
                .orElseThrow();

        assertEquals(
                new BigDecimal("0.00"),
                updatedWallet.getBalance()
        );

        assertEquals(5, successCount);
        assertEquals(5, failureCount);
        
        System.out.println(
                "PASS: Race condition handled. Success = "
                + successCount
                + ", Failures = "
                + failureCount
                + ", Final balance = ₹"
                + updatedWallet.getBalance()
        );
        
        
    }
}