package com.example.demo.service;

import com.example.demo.exception.CancellationNotAllowedException;
import com.example.demo.exception.DuplicateRequestException;
import com.example.demo.exception.InsufficientFundsException;
import com.example.demo.exception.SelfTransferException;
import com.example.demo.exception.TransferNotFoundException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.model.PageResponse;
import com.example.demo.model.TransferHistoryItem;
import com.example.demo.model.TransferRequest;
import com.example.demo.model.TransferResponse;
import com.example.demo.cache.BalanceCache;
import com.example.demo.event.TransferCancelledEvent;
import com.example.demo.event.TransferCompletedEvent;
import com.example.demo.event.TransferEventPublisher;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.TransferRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class TransferService {

    /** Upper bound on page size so a client can't ask for an unbounded scan. */
    static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final BalanceCache balanceCache;
    private final TransferEventPublisher eventPublisher;

    public TransferService(AccountRepository accounts, TransferRepository transfers,
                           BalanceCache balanceCache, TransferEventPublisher eventPublisher) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.balanceCache = balanceCache;
        this.eventPublisher = eventPublisher;
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
        moveInLockOrder(from, to, request.amount());

        long transferId;
        try {
            transferId = transfers.insertCompleted(from, to, request.amount(), requestId);
        } catch (DuplicateKeyException e) {
            // Lost a concurrent race on the same requestId — roll this attempt back
            // (the balance changes above are undone) so the transfer applies once.
            throw new DuplicateRequestException(requestId);
        }
        // Run side-effects only after the DB commit: invalidate the read-path cache
        // (so a concurrent read can't repopulate it with uncommitted balances) and
        // publish the event. The write transaction never blocks on message delivery.
        TransferCompletedEvent event = new TransferCompletedEvent(transferId, from, to, request.amount());
        afterCommit(() -> {
            balanceCache.evict(from);
            balanceCache.evict(to);
            eventPublisher.publishCompleted(event);
        });
        return new TransferResponse(transferId, "COMPLETED");
    }

    /**
     * Lists the transfers {@code userId} was involved in (as sender or receiver),
     * newest first, one page at a time. Read-only path; the size is clamped to
     * {@link #MAX_PAGE_SIZE} as a backstop even though the controller validates it.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransferHistoryItem> history(String userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        long offset = (long) safePage * safeSize;
        List<TransferHistoryItem> content = transfers.listByUser(userId, safeSize, offset);
        long total = transfers.countByUser(userId);
        return new PageResponse<>(content, safePage, safeSize, total);
    }

    /**
     * Cancels a recent transfer as a compensating reversal (ADR-0002): a guarded
     * status flip (COMPLETED within the last 10 minutes) plus an atomic reversal
     * that moves the amount back from receiver to sender, in one transaction.
     *
     * <ul>
     *   <li>Unknown transfer → {@link TransferNotFoundException} (404).</li>
     *   <li>Already cancelled → idempotent success (200), no second reversal.</li>
     *   <li>Too old to cancel → {@link CancellationNotAllowedException} (409).</li>
     *   <li>Receiver can no longer cover the reversal → {@link InsufficientFundsException}
     *       (409); the whole cancel rolls back rather than driving them negative.</li>
     * </ul>
     */
    @Transactional
    public TransferResponse cancel(long transferId) {
        // Guarded flip first (it takes the row lock). 0 rows ⇒ not cancellable now;
        // classify why with a locking read that sees the latest committed state.
        int flipped = transfers.markCancelled(transferId);
        if (flipped == 0) {
            TransferHistoryItem current = transfers.findByIdForUpdate(transferId)
                    .orElseThrow(() -> new TransferNotFoundException(transferId));
            if ("CANCELLED".equals(current.status())) {
                return new TransferResponse(transferId, "CANCELLED"); // idempotent double-cancel
            }
            throw new CancellationNotAllowedException(transferId); // COMPLETED but outside the window
        }

        // We hold the row lock from the flip; read the original to reverse it.
        TransferHistoryItem original = transfers.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
        String sender = original.fromUserId();
        String receiver = original.toUserId();
        BigDecimal amount = original.amount();

        // Reverse the movement: debit the receiver (conditionally — never negative),
        // credit the sender. Same ascending-userId lock order as a forward transfer.
        moveInLockOrder(receiver, sender, amount);
        long reversalId = transfers.insertReversal(receiver, sender, amount, transferId);

        TransferCancelledEvent event =
                new TransferCancelledEvent(transferId, reversalId, sender, receiver, amount);
        afterCommit(() -> {
            balanceCache.evict(sender);
            balanceCache.evict(receiver);
            eventPublisher.publishCancelled(event);
        });
        return new TransferResponse(transferId, "CANCELLED");
    }

    /**
     * Atomically moves {@code amount} from {@code from} to {@code to}, locking the
     * two account rows in ascending userId order so opposing operations can never
     * deadlock. Throws {@link InsufficientFundsException} if {@code from} can't cover it.
     */
    private void moveInLockOrder(String from, String to, BigDecimal amount) {
        if (from.compareTo(to) < 0) {
            debitOrThrow(from, amount);
            accounts.credit(to, amount);
        } else {
            accounts.credit(to, amount);
            debitOrThrow(from, amount);
        }
    }

    private void debitOrThrow(String user, BigDecimal amount) {
        int rows = accounts.debit(user, amount);
        if (rows == 0) {
            throw new InsufficientFundsException(user);
        }
    }

    private void afterCommit(Runnable sideEffects) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sideEffects.run();
            }
        });
    }
}
