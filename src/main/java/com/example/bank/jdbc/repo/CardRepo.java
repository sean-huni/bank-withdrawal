package com.example.bank.jdbc.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import com.example.bank.jdbc.model.CardEntity;

public interface CardRepo extends CrudRepository<CardEntity, UUID> {

	Optional<CardEntity> findByCardNumber(String cardNumber);
}
