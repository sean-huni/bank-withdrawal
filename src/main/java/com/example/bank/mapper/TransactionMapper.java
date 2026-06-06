package com.example.bank.mapper;

import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import com.example.bank.api.dto.resp.TransactionResponse;
import com.example.bank.domain.AccountTransaction;
import com.example.bank.event.WithdrawalEvent;
import com.example.bank.jpa.model.TransactionEntity;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = UUID.class)
public interface TransactionMapper {

	@Mapping(target = "transactionId", source = "id")
	@Mapping(target = "accountId", source = "account.id")
	@Mapping(target = "occurredAt", source = "createdAt")
	AccountTransaction toAccountTransaction(TransactionEntity entity);

	TransactionResponse toTransactionResponse(AccountTransaction transaction);

	@Mapping(target = "eventId", expression = "java(UUID.randomUUID())")
	@Mapping(target = "status", constant = "SUCCESSFUL")
	WithdrawalEvent toWithdrawalEvent(AccountTransaction transaction);
}
