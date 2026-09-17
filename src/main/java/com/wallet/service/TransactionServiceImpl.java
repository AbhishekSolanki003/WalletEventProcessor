package com.wallet.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wallet.dto.TransactionRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;

@Service
public class TransactionServiceImpl implements TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionServiceImpl(WalletRepository walletRepository, TransactionRepository transactionRepository) {

        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional
    public void processTransaction(TransactionRequest request) {

        // To Find and lock the wallet
    	Wallet wallet = walletRepository
    	        .findWalletForUpdate(request.getUserId())
    	        .orElseThrow(() ->
    	                new WalletNotFoundException("Wallet not found"));

        // To Check idempotency AFTER acquiring the wallet lock
        if (transactionRepository.existsByTransactionId(request.getTransactionId())) {

            throw new IllegalStateException(
                    "Transaction already processed");
        }

        // To Check balance
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds");
        }

        // To Deduct amount
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount())
        );

        //  To Create transaction record
        Transaction transaction = new Transaction();

        transaction.setTransactionId(request.getTransactionId());
        transaction.setUserId(request.getUserId());
        transaction.setAmount(request.getAmount());
        transaction.setType(request.getType());

        // To Save transaction
        transactionRepository.save(transaction);
    }
}