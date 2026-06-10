package com.example.bank.api.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.bank.dto.resp.ApiResponse;
import com.example.bank.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auth/authz category (the slot the WAT advice table reserves for security):
 * Spring Security's {@link AuthenticationException} → 401 and
 * {@link AccessDeniedException} → 403. Both map to a single generic, localized
 * envelope code — USER-ENUMERATION-SAFE: the response never reveals whether the
 * identity, card or credential exists, only that authentication/authorization
 * failed. Logs carry the exception type for operators but never the principal.
 *
 * <p>Higher precedence than the catch-all so security failures get their
 * semantic 401/403 status rather than a 500. The framework's WebAuthn ceremony
 * filters answer their own endpoints directly (challenge JSON / 4xx), so this
 * advice covers security failures that propagate to the {@code @Controller}
 * dispatch — e.g. an unauthenticated call to a secured endpoint surfaced via the
 * {@code AuthenticationEntryPoint}/{@code ExceptionTranslationFilter} path.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class SecurityExceptionHandler {

	private final ErrorResponseFactory errors;

	@ExceptionHandler(AuthenticationException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ApiResponse<Void> handleUnauthenticated(final AuthenticationException ex) {
		// type only — never the principal/credential (user-enumeration safety)
		log.warn("Authentication failed: {}", ex.getClass().getSimpleName());
		return errors.failure(errors.errorOf(ErrorCode.UNAUTHORIZED));
	}

	@ExceptionHandler(AccessDeniedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ApiResponse<Void> handleAccessDenied(final AccessDeniedException ex) {
		log.warn("Access denied: {}", ex.getClass().getSimpleName());
		return errors.failure(errors.errorOf(ErrorCode.FORBIDDEN));
	}
}
