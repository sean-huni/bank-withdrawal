package com.example.bank.api;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bank.api.dto.resp.ApiResponse;
import com.example.bank.api.dto.req.DepositRequest;
import com.example.bank.api.dto.resp.TransactionResponse;
import com.example.bank.api.dto.req.WithdrawalRequest;
import com.example.bank.service.AccountTransactionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lean HTTP boundary: validate, delegate, wrap in the envelope — nothing else.
 * The service returns ready-to-serve DTOs; statuses are declared with
 * {@code @ResponseStatus}; errors are the advice's job.
 */
@Slf4j
@Tag(name = "Account Transactions", description = "Withdrawals, deposits and statement for an account")
@RestController
// Spring Framework 7 native API versioning: the {api-version} segment is resolved via
// spring.mvc.api-version.use.path-segment, and the supported set comes from
// spring.mvc.api-version.supported (application.yml) — an unversioned mapping matches any
// supported version, so no version attribute and no hardcoded /v1 prefix here.
@RequestMapping(value = "/api/{api-version}/accounts/{accountId}")
@RequiredArgsConstructor
public class AccountTransactionController {

	private final AccountTransactionService transactionService;
	private final TraceIdProvider traceIdProvider;

	@PostMapping(value = "/withdrawals")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Withdraw funds (debit)",
			description = "Creates a debit transaction. Requires an Idempotency-Key header.")
	public ApiResponse<TransactionResponse> withdraw(
			@PathVariable final UUID accountId,
			@RequestHeader("Idempotency-Key") final UUID idempotencyKey,
			@Valid @RequestBody final WithdrawalRequest request) {
		return ok(transactionService.withdraw(accountId, idempotencyKey, request.amount()));
	}

	@PostMapping("/deposits")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Deposit funds (credit)",
			description = "Creates a credit transaction. Requires an Idempotency-Key header.")
	public ApiResponse<TransactionResponse> deposit(
			@PathVariable final UUID accountId,
			@RequestHeader("Idempotency-Key") final UUID idempotencyKey,
			@Valid @RequestBody final DepositRequest request) {
		return ok(transactionService.deposit(accountId, idempotencyKey, request.amount()));
	}

	@GetMapping("/transactions")
	@Operation(summary = "Account statement", description = "Paged ledger of transactions for the account.")
	public ApiResponse<PagedModel<TransactionResponse>> statement(
			@PathVariable final UUID accountId,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) final Pageable pageable) {
		return ok(new PagedModel<>(transactionService.statement(accountId, pageable)));
	}

	@GetMapping("/transactions/{transactionId}")
	@Operation(summary = "Single transaction")
	public ApiResponse<TransactionResponse> getTransaction(
			@PathVariable final UUID accountId,
			@PathVariable final UUID transactionId) {
		return ok(transactionService.findTransaction(accountId, transactionId));
	}

	private <T> ApiResponse<T> ok(final T body) {
		return ApiResponse.ok(body, traceIdProvider.currentTraceId());
	}
}
