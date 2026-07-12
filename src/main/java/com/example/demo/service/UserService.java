package com.example.demo.service;

import com.example.demo.exception.UserAlreadyExistsException;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.model.BalanceResponse;
import com.example.demo.model.CreateUserRequest;
import com.example.demo.repository.AccountRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class UserService {

    private final AccountRepository accounts;

    public UserService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public void createUser(CreateUserRequest request) {
        try {
            accounts.insert(request.userId(), request.initialBalance());
        } catch (DuplicateKeyException e) {
            throw new UserAlreadyExistsException(request.userId());
        }
    }

    public BalanceResponse getBalance(String userId) {
        BigDecimal balance = accounts.findBalance(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return new BalanceResponse(userId, balance);
    }
}
