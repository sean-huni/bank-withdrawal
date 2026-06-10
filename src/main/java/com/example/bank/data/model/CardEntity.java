package com.example.bank.data.model;

import java.util.UUID;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Table("cards")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardEntity extends BaseEntity {

	@NotNull
	@Column("account_id")
	private UUID accountId;

	@NotBlank
	@Pattern(regexp = "\\d{16}")
	@Column("card_number")
	private String cardNumber;

	@NotBlank
	@Column("pin_hash")
	private String pinHash;

	public CardEntity(final UUID accountId, final String cardNumber, final String pinHash) {
		this.accountId = accountId;
		this.cardNumber = cardNumber;
		this.pinHash = pinHash;
	}
}
