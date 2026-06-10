package com.example.bank.ctl;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bank.config.TraceIdProvider;
import com.example.bank.dto.req.AtmSessionRequest;
import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.dto.resp.AtmSessionResponse;
import com.example.bank.service.AtmSessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * ATM session bootstrap — the enrolment entry point of the passkey-enabled ATM.
 * Card + PIN (the existing authentication) establishes an authenticated
 * HttpSession as the customer; that session is what makes the passkey
 * REGISTRATION ceremony ({@code POST /webauthn/register/options}) reachable.
 * On a return visit the customer skips this step entirely via {@code POST
 * /login/webauthn} (discoverable credential, username-less).
 *
 * <p>Lives under {@code /api/**} so it shares the existing API contract (CSRF
 * exempt, version-scoped). The controller is a lean pass-through: the service
 * owns identity + passkey logic; the only servlet concern here is persisting the
 * established {@link SecurityContext} into the session so the WebAuthn filters
 * see an authenticated principal on the follow-up ceremony call.
 */
@Tag(name = "ATM Session", description = "Card+PIN bootstrap of an authenticated kiosk session for passkey enrolment")
@RestController
@RequestMapping(value = "/api/{api-version}/atm")
@RequiredArgsConstructor
public class AtmSessionController {

	private final AtmSessionService atmSessionService;
	private final TraceIdProvider traceIdProvider;
	private final SecurityContextRepository securityContextRepository;

	@PostMapping("/session")
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Bootstrap an ATM session",
			description = "Verifies card + PIN and authenticates the HttpSession as the customer so passkey "
					+ "enrolment becomes reachable. Responses: 200 (session cookie set), 400 malformed, "
					+ "401 incorrect PIN, 404 unknown card.")
	public ApiResponse<AtmSessionResponse> bootstrap(
			@Valid @RequestBody final AtmSessionRequest request,
			final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse) {
		final AtmSessionService.Bootstrap bootstrap =
				atmSessionService.bootstrap(request.cardNumber(), request.pin());

		// OWASP A07 / ASVS V3.2.1 — rotate the session id on privilege elevation so a
		// pre-auth (pre-bootstrap) session cannot survive into the authenticated state
		// (session-fixation defence). Ensure a session exists first, THEN change its id,
		// BEFORE saving the security context.
		httpRequest.getSession(true);
		httpRequest.changeSessionId();

		// Persist a fresh context into the session — the WebAuthn registration filter
		// reads SecurityContextHolder via the session on the subsequent ceremony call.
		final SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(bootstrap.authentication());
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, httpRequest, httpResponse);

		return ApiResponse.ok(bootstrap.response(), traceIdProvider.currentTraceId());
	}
}
