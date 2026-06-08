package com.example.bank.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;

import com.example.bank.api.dto.resp.AccountResponse;
import com.example.bank.jdbc.model.AccountEntity;

/** Persistence → controller-boundary dto for account snapshots; masks the PAN to last-4. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface AccountMapper {

	@Mapping(target = "accountId", source = "id")
	@Mapping(target = "maskedCardNumber", source = "cardNumber", qualifiedByName = "maskCard")
	AccountResponse toAccountResponse(AccountEntity entity);

	/** Keep the last four digits, mask the rest in the conventional card grouping. */
	@Named("maskCard")
	static String maskCard(final String cardNumber) {
		final String last4 = cardNumber.substring(cardNumber.length() - 4);
		return "•••• •••• •••• %s".formatted(last4);
	}
}
