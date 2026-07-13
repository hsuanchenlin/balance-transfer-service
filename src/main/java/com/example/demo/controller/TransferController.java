package com.example.demo.controller;

import com.example.demo.model.PageResponse;
import com.example.demo.model.TransferHistoryItem;
import com.example.demo.model.TransferRequest;
import com.example.demo.model.TransferResponse;
import com.example.demo.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
@Validated
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transferService.transfer(request));
    }

    @GetMapping
    public PageResponse<TransferHistoryItem> history(
            @RequestParam @NotBlank String userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return transferService.history(userId, page, size);
    }

    @PostMapping("/{transferId}/cancel")
    public TransferResponse cancel(@PathVariable long transferId) {
        return transferService.cancel(transferId);
    }
}
