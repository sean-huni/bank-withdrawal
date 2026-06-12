package com.example.bank.dto.resp;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a successful ATM session bootstrap. The HttpSession is now
 * authenticated as this customer, so the passkey-enrolment ceremony
 * ({@code POST /webauthn/register/options}) is reachable. {@code passkeyEnrolled}
 * tells the kiosk UI whether to offer "Enable passkey on this ATM" or simply
 * acknowledge an already-enrolled customer.
 */
@Schema(description = "Authenticated ATM session: account id, masked card, and whether a passkey is already enrolled")
public record AtmSessionResponse(
		@Schema(description = "Owning account id; the authenticated session principal")
		UUID accountId,
		@Schema(description = "Card number masked to the last four digits", example = "•••• •••• •••• 6467")
		String maskedCardNumber,
		@Schema(description = "True if this customer already enrolled a passkey on this relying party", example = "false")
		boolean passkeyEnrolled) {
}
