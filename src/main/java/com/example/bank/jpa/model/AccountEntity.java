package com.example.bank.jpa.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountEntity extends BaseEntity {

	@NotBlank
	@Column(name = "holder_name", nullable = false)
	private String holderName;

	@NotNull
	@PositiveOrZero
	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal balance;

	@NotBlank
	@Size(min = 3, max = 3)
	@Column(nullable = false, length = 3)
	private String currency;

	public AccountEntity(final String holderName, final BigDecimal balance, final String currency) {
		this.holderName = holderName;
		this.balance = balance;
		this.currency = currency;
	}

	public void debit(final BigDecimal amount) {
		this.balance = this.balance.subtract(amount);
	}
}
