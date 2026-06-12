package com.example.bank.event.publisher;

import com.example.bank.event.WithdrawalEvent;

/**
 * Outbound port for withdrawal events — keeps the service layer independent of
 * the messaging technology (SNS today, anything tomorrow).
 */
public interface WithdrawalEventPublisher {

	void publish(WithdrawalEvent event);
}
