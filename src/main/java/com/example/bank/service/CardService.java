package com.example.bank.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.example.bank.api.dto.resp.AccountResponse;
import com.example.bank.exception.CardNotFoundException;
import com.example.bank.jdbc.repo.AccountRepo;
import com.example.bank.mapper.AccountMapper;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Card lookup / balance inquiry. {@code @Validated} enforces the 16-digit format
 * at this boundary (defense-in-depth behind the controller's path-var constraint);
 * a well-formed but unknown card is the only business outcome → 404.
 */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class CardService {

	private final AccountRepo accountRepo;
	private final AccountMapper accountMapper;

	@Transactional(readOnly = true)
	@Observed(name = "card.lookup")
	public AccountResponse lookup(
			@NotBlank @Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") final String cardNumber) {
		return accountRepo.findByCardNumber(cardNumber)
				.map(accountMapper::toAccountResponse)
				.orElseThrow(CardNotFoundException::new);
	}
}
