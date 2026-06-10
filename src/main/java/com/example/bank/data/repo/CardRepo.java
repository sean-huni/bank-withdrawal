package com.example.bank.data.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import com.example.bank.data.model.CardEntity;

public interface CardRepo extends CrudRepository<CardEntity, UUID> {

	Optional<CardEntity> findByCardNumber(String cardNumber);
}
