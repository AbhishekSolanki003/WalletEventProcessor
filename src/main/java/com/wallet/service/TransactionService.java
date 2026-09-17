package com.wallet.service;

import com.wallet.dto.TransactionRequest;

public interface TransactionService {
	
	void processTransaction(TransactionRequest request);
}
