package com.example.bank.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.example.bank.dto.resp.AccountResponse;
import com.example.bank.dto.resp.CardSummaryResponse;
import com.example.bank.exception.AccountNotFoundException;
import com.example.bank.exception.CardNotFoundException;
import com.example.bank.exception.PinInvalidException;
import com.example.bank.data.model.CardEntity;
import com.example.bank.data.repo.AccountRepo;
import com.example.bank.data.repo.CardRepo;
import com.example.bank.mapper.AccountMapper;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Card lookup (greeting) and PIN verification. The raw PIN is BCrypt-matched against the stored
 * hash ({@code matches}, not equality) and never logged. Balance is returned ONLY after a verified PIN.
 */
@Service
@Validated
@RequiredArgsConstructor
public class CardService {

	private final CardRepo cardRepo;
	private final AccountRepo accountRepo;
	private final AccountMapper accountMapper;
	private final PasswordEncoder passwordEncoder;

	@Transactional(readOnly = true)
	@Observed(name = "card.summary")
	public CardSummaryResponse summary(
			@NotBlank @Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") final String cardNumber) {
		final CardEntity card = card(cardNumber);
		final String holderName = accountRepo.findById(card.getAccountId())
				.orElseThrow(() -> new AccountNotFoundException(card.getAccountId()))
				.getHolderName();
		return new CardSummaryResponse(holderName, AccountMapper.maskCard(card.getCardNumber()));
	}

	@Transactional(readOnly = true)
	@Observed(name = "card.pin.verify")
	public AccountResponse verifyPin(
			@NotBlank @Pattern(regexp = "\\d{16}", message = "{error.card.invalid}") final String cardNumber,
			@NotBlank @Pattern(regexp = "\\d{4}", message = "{error.pin.invalid-format}") final String pin) {
		final CardEntity card = card(cardNumber);
		if (!passwordEncoder.matches(pin, card.getPinHash())) {
			throw new PinInvalidException();
		}
		return accountRepo.findById(card.getAccountId())
				.map(account -> accountMapper.toAccountResponse(account, card.getCardNumber()))
				.orElseThrow(() -> new AccountNotFoundException(card.getAccountId()));
	}

	private CardEntity card(final String cardNumber) {
		return cardRepo.findByCardNumber(cardNumber).orElseThrow(CardNotFoundException::new);
	}
}
