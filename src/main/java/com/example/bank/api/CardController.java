package com.example.bank.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.bank.api.dto.resp.AccountResponse;
import com.example.bank.api.dto.resp.ApiResponse;
import com.example.bank.service.CardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Card lookup / balance inquiry — the ATM "insert card" step. A 16-digit card
 * number resolves to its account snapshot (id, holder, masked card, balance);
 * the returned {@code accountId} drives subsequent withdrawal/deposit/statement
 * calls. A malformed card is a 400 (path-var format constraint); a well-formed
 * unknown card is a 404 ({@code CARD_NOT_FOUND}). The full PAN is never returned.
 */
@Slf4j
@Tag(name = "Cards", description = "Card lookup and balance inquiry")
@RestController
@RequestMapping(value = "/api/{api-version}/cards")
@RequiredArgsConstructor
public class CardController {

	private final CardService cardService;
	private final TraceIdProvider traceIdProvider;

	@GetMapping("/{cardNumber}")
	@Operation(summary = "Card lookup / balance inquiry",
			description = "Resolves a 16-digit card number to its account snapshot (id, holder, masked "
					+ "card number, current balance). Responses: 200, 400 malformed card number, 404 unknown card.")
	public ApiResponse<AccountResponse> lookup(
			@Parameter(description = "16-digit card number", in = ParameterIn.PATH)
			@Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") @PathVariable final String cardNumber) {
		return ApiResponse.ok(cardService.lookup(cardNumber), traceIdProvider.currentTraceId());
	}
}
