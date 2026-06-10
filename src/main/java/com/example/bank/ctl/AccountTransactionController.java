package com.example.bank.ctl;

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

import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.api.validation.AllowedSortProperties;
import com.example.bank.config.TraceIdProvider;
import com.example.bank.dto.req.DepositRequest;
import com.example.bank.dto.resp.TransactionResponse;
import com.example.bank.dto.req.WithdrawalRequest;
import com.example.bank.service.AccountTransactionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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

	/**
	 * Withdraws an amount from the account — creates a {@code DEBIT} ledger
	 * entry and emits a withdrawal event after commit.
	 *
	 * @param accountId      account to debit (path)
	 * @param idempotencyKey client-supplied key making retries safe; replays
	 *                       return the original representation
	 * @param request        validated amount payload
	 * @return the created transaction wrapped in the response envelope
	 */
	@PostMapping("/withdrawals")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Withdraw funds (debit)",
			description = "Atomically debits the account if funds are sufficient, records an immutable "
					+ "ledger entry and emits a withdrawal event after commit. Idempotent per "
					+ "Idempotency-Key: a replay with the same key and body returns the original "
					+ "transaction without debiting again. Responses: 201 created, 400 validation/missing key/unsupported version, 404 unknown account, 409 idempotency conflict, 422 insufficient funds.")
	public ApiResponse<TransactionResponse> withdraw(
			@Parameter(description = "Account to debit", in = ParameterIn.PATH)
			@PathVariable final UUID accountId,
			@Parameter(description = "Idempotency key (UUID); required on every POST", in = ParameterIn.HEADER)
			@RequestHeader("Idempotency-Key") final UUID idempotencyKey,
			@Valid @RequestBody final WithdrawalRequest request) {
		return ok(transactionService.withdraw(accountId, idempotencyKey, request.amount()));
	}

	/**
	 * Deposits an amount into the account — creates a {@code CREDIT} ledger
	 * entry. Same idempotency contract as {@link #withdraw}.
	 *
	 * @param accountId      account to credit (path)
	 * @param idempotencyKey client-supplied key making retries safe
	 * @param request        validated amount payload
	 * @return the created transaction wrapped in the response envelope
	 */
	@PostMapping("/deposits")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Deposit funds (credit)",
			description = "Atomically credits the account and records an immutable ledger entry. "
					+ "Idempotent per Idempotency-Key. Responses: 201 created, 400 validation/missing key, 404 unknown account, 409 idempotency conflict.")
	public ApiResponse<TransactionResponse> deposit(
			@Parameter(description = "Account to credit", in = ParameterIn.PATH)
			@PathVariable final UUID accountId,
			@Parameter(description = "Idempotency key (UUID); required on every POST", in = ParameterIn.HEADER)
			@RequestHeader("Idempotency-Key") final UUID idempotencyKey,
			@Valid @RequestBody final DepositRequest request) {
		return ok(transactionService.deposit(accountId, idempotencyKey, request.amount()));
	}

	/**
	 * Returns the account's ledger as a page, newest first by default.
	 *
	 * @param accountId account whose statement is requested
	 * @param pageable  page/size/sort (defaults: size 20, createdAt desc)
	 * @return one page of transactions in {@link PagedModel} wire format
	 */
	@GetMapping("/transactions")
	@Operation(summary = "Account statement",
			description = "Paged, ordered ledger of the account's transactions (default: newest first). "
					+ "Sortable properties: createdAt, amount, type, balanceAfter. "
					+ "Responses: 200, 400 unsortable sort property, 404 unknown account.")
	public ApiResponse<PagedModel<TransactionResponse>> statement(
			@Parameter(description = "Account whose statement is requested", in = ParameterIn.PATH)
			@PathVariable final UUID accountId,
			@AllowedSortProperties({"createdAt", "amount", "type", "balanceAfter"})
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) final Pageable pageable) {
		return ok(new PagedModel<>(transactionService.statement(accountId, pageable)));
	}

	/**
	 * Returns a single ledger entry; repeated reads are served from the cache
	 * (entries are write-once and can never go stale).
	 *
	 * @param accountId     owning account
	 * @param transactionId ledger entry id
	 * @return the transaction wrapped in the response envelope
	 */
	@GetMapping("/transactions/{transactionId}")
	@Operation(summary = "Single transaction",
			description = "One immutable ledger entry; served from cache on repeated reads. Responses: 200, 404 unknown transaction or account.")
	public ApiResponse<TransactionResponse> getTransaction(
			@Parameter(description = "Owning account", in = ParameterIn.PATH)
			@PathVariable final UUID accountId,
			@Parameter(description = "Ledger entry id", in = ParameterIn.PATH)
			@PathVariable final UUID transactionId) {
		return ok(transactionService.findTransaction(accountId, transactionId));
	}

	/** Wraps a body in the success envelope, attaching the current trace id. */
	private <T> ApiResponse<T> ok(final T body) {
		return ApiResponse.ok(body, traceIdProvider.currentTraceId());
	}
}
