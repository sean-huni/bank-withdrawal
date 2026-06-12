package com.example.bank.domain;

/**
 * Accounting direction of a ledger entry. Internal vocabulary — the API speaks
 * the customer's language (withdrawal/deposit) via the resource paths.
 */
public enum TransactionType {
	CREDIT, // money in  — deposit
	DEBIT   // money out — withdrawal
}
