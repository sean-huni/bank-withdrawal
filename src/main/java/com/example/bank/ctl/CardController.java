package com.example.bank.ctl;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bank.api.dto.req.PinVerifyRequest;
import com.example.bank.config.TraceIdProvider;
import com.example.bank.api.dto.resp.AccountResponse;
import com.example.bank.api.dto.resp.ApiResponse;
import com.example.bank.api.dto.resp.CardSummaryResponse;
import com.example.bank.service.CardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Card greeting and PIN authentication — the ATM "insert card" then "enter PIN" steps. The summary
 * carries no balance; balance + accountId are returned only after a verified PIN. PIN is body-only
 * over HTTPS and never logged.
 */
@Tag(name = "Cards", description = "Card lookup and PIN authentication")
@RestController
@RequestMapping(value = "/api/{api-version}/cards")
@RequiredArgsConstructor
public class CardController {

	private final CardService cardService;
	private final TraceIdProvider traceIdProvider;

	@GetMapping("/{cardNumber}")
	@Operation(summary = "Card greeting", description = "Resolves a 16-digit card to its holder + masked "
			+ "number (no balance). Responses: 200, 400 malformed, 404 unknown card.")
	public ApiResponse<CardSummaryResponse> summary(
			@Parameter(description = "16-digit card number", in = ParameterIn.PATH)
			@Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") @PathVariable final String cardNumber) {
		return ApiResponse.ok(cardService.summary(cardNumber), traceIdProvider.currentTraceId());
	}

	@PostMapping("/{cardNumber}/pin")
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Verify PIN", description = "BCrypt-verifies the 4-digit PIN and returns the "
			+ "authenticated account snapshot (with balance). Responses: 200, 400 malformed, 401 incorrect PIN, 404 unknown card.")
	public ApiResponse<AccountResponse> verifyPin(
			@Parameter(description = "16-digit card number", in = ParameterIn.PATH)
			@Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") @PathVariable final String cardNumber,
			@Valid @RequestBody final PinVerifyRequest request) {
		return ApiResponse.ok(cardService.verifyPin(cardNumber, request.pin()), traceIdProvider.currentTraceId());
	}
}
