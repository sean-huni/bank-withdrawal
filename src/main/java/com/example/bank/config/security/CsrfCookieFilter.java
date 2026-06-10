package com.example.bank.config.security;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Primes the {@code XSRF-TOKEN} cookie on EVERY response — the official Spring
 * Security SPA pattern (<a href="https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-javascript-spa">
 * docs: Single-Page Applications</a>).
 *
 * <p>The kiosk bootstrap ({@code POST /api/v1/atm/session}) is CSRF-EXEMPT (under
 * {@code /api/**}), so without this filter its response never writes the token
 * cookie and the SPA's first CSRF-protected ceremony POST
 * ({@code /webauthn/register/options}) would 403 — the masked failure the
 * retry-on-403 test dance was hiding.
 *
 * <p>Key fact: {@code CsrfFilter} ALWAYS populates the {@code CsrfToken} request
 * attribute (a deferred/lazy token) — the {@code ignoringRequestMatchers} matcher
 * only skips ENFORCEMENT, not token creation. Reading the attribute and calling
 * {@link CsrfToken#getToken()} here forces that deferred token to render, which
 * triggers {@code CookieCsrfTokenRepository} to write the cookie. Registered
 * {@code addFilterAfter(..., BasicAuthenticationFilter.class)} so the
 * {@code CsrfFilter} has already run and stashed the attribute.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(final HttpServletRequest request,
			final HttpServletResponse response, final FilterChain filterChain)
			throws ServletException, IOException {
		final CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (csrfToken != null) {
			// Force the deferred token to render so the repository writes the cookie,
			// including on the CSRF-exempt bootstrap response.
			csrfToken.getToken();
		}
		filterChain.doFilter(request, response);
	}
}
