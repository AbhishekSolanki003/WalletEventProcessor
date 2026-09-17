package com.wallet.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wallet.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
	
	boolean existsByTransactionId(UUID transactionID);
}
