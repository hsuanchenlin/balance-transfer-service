package com.example.demo.service;

import com.example.demo.cache.BalanceCache;
import com.example.demo.exception.UserAlreadyExistsException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.model.BalanceResponse;
import com.example.demo.model.CreateUserRequest;
import com.example.demo.repository.AccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class UserService {

    private final AccountRepository accounts;
    private final BalanceCache balanceCache;

    public UserService(AccountRepository accounts, BalanceCache balanceCache) {
        this.accounts = accounts;
        this.balanceCache = balanceCache;
    }

    public void createUser(CreateUserRequest request) {
        try {
            accounts.insert(request.userId(), request.initialBalance());
        } catch (DuplicateKeyException e) {
            throw new UserAlreadyExistsException(request.userId());
        }
    }

    public BalanceResponse getBalance(String userId) {
        Optional<BigDecimal> cached = balanceCache.get(userId);
        if (cached.isPresent()) {
            return new BalanceResponse(userId, cached.get());
        }
        BigDecimal balance = accounts.findBalance(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        balanceCache.put(userId, balance);
        return new BalanceResponse(userId, balance);
    }
}
