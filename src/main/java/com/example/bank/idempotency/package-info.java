/**
 * Idempotency for unsafe operations: an {@code Idempotency-Key} header is
 * reserved in the same physical transaction as the business mutation, so a
 * retry either replays the cached response or proceeds cleanly — never
 * double-executes.
 */
package com.example.bank.idempotency;
