package com.example.demo.service;

import com.example.demo.exception.InsufficientFundsException;
import com.example.demo.exception.SelfTransferException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.model.TransferRequest;
import com.example.demo.model.TransferResponse;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public TransferService(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    /**
     * Moves {@code amount} from sender to receiver atomically. Correctness comes
     * from the DB (ADR-0001): the debit is a conditional update guarded by
     * {@code balance >= amount}, and the two account rows are locked in a
     * deterministic order (by userId) to prevent deadlocks between opposing
     * transfers.
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        String from = request.fromUserId();
        String to = request.toUserId();

        if (from.equals(to)) {
            throw new SelfTransferException(from);
        }
        if (!accounts.exists(from)) {
            throw new UserNotFoundException(from);
        }
        if (!accounts.exists(to)) {
            throw new UserNotFoundException(to);
        }

        // Touch the two rows in ascending userId order so concurrent opposing
        // transfers acquire locks in the same order (deadlock-free).
        if (from.compareTo(to) < 0) {
            debitOrThrow(from, request);
            accounts.credit(to, request.amount());
        } else {
            accounts.credit(to, request.amount());
            debitOrThrow(from, request);
        }

        long transferId = transfers.insertCompleted(from, to, request.amount());
        return new TransferResponse(transferId, "COMPLETED");
    }

    private void debitOrThrow(String from, TransferRequest request) {
        int rows = accounts.debit(from, request.amount());
        if (rows == 0) {
            throw new InsufficientFundsException(from);
        }
    }
}
