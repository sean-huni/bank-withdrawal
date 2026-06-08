package com.example.bank.jdbc.model;

import java.math.BigDecimal;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Table("accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountEntity extends BaseEntity {

	@NotBlank
	@Column("holder_name")
	private String holderName;

	@NotNull
	@PositiveOrZero
	private BigDecimal balance;

	@NotBlank
	@Size(min = 3, max = 3)
	private String currency;

	@NotBlank
	@Pattern(regexp = "\\d{16}")
	@Column("card_number")
	private String cardNumber;

	public AccountEntity(final String holderName, final BigDecimal balance,
			final String currency, final String cardNumber) {
		this.holderName = holderName;
		this.balance = balance;
		this.currency = currency;
		this.cardNumber = cardNumber;
	}
}
