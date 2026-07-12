package com.example.demo.service;

import com.example.demo.exception.DuplicateRequestException;
import com.example.demo.exception.InsufficientFundsException;
import com.example.demo.exception.SelfTransferException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.model.TransferRequest;
import com.example.demo.model.TransferResponse;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.TransferRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
     *
     * <p>When a {@code requestId} is supplied it is stored under a UNIQUE
     * constraint, making the operation idempotent: a sequential retry replays the
     * original result; a concurrent duplicate is rejected (rolling back its
     * attempt) so the transfer applies at most once.
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        String from = request.fromUserId();
        String to = request.toUserId();
        String requestId = request.requestId();

        if (from.equals(to)) {
            throw new SelfTransferException(from);
        }
        if (!accounts.exists(from)) {
            throw new UserNotFoundException(from);
        }
        if (!accounts.exists(to)) {
            throw new UserNotFoundException(to);
        }

        // Idempotency replay: a completed transfer already exists for this key.
        if (requestId != null) {
            Optional<TransferResponse> existing = transfers.findByRequestId(requestId);
            if (existing.isPresent()) {
                return existing.get();
            }
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

        long transferId;
        try {
            transferId = transfers.insertCompleted(from, to, request.amount(), requestId);
        } catch (DuplicateKeyException e) {
            // Lost a concurrent race on the same requestId — roll this attempt back
            // (the balance changes above are undone) so the transfer applies once.
            throw new DuplicateRequestException(requestId);
        }
        return new TransferResponse(transferId, "COMPLETED");
    }

    private void debitOrThrow(String from, TransferRequest request) {
        int rows = accounts.debit(from, request.amount());
        if (rows == 0) {
            throw new InsufficientFundsException(from);
        }
    }
}
