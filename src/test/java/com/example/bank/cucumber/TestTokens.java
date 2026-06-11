package com.example.bank.cucumber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import tools.jackson.databind.ObjectMapper;

/**
 * Fetches a client-credentials bearer token from the embedded authorization
 * server for the Cucumber suite. The atm-ops demo client/secret come from
 * application.yml defaults (atm.security.ops-client-secret).
 *
 * <p>Uses the JDK {@link HttpClient} for a minimal, converter-free form POST —
 * a plain socket call has no Spring message-converter machinery to misconfigure.
 */
final class TestTokens {

	private TestTokens() {
	}

	/**
	 * Obtains a bearer token via the client-credentials flow.
	 *
	 * @param baseUrl e.g. {@code "http://localhost:12345"}
	 * @param scopes  space-delimited OAuth scopes (e.g. {@code "atm.read atm.write"})
	 * @return the value to set as the {@code Authorization} header, e.g. {@code "Bearer eyJ..."}
	 */
	static String bearer(final String baseUrl, final String scopes) {
		// Basic auth: base64(clientId:clientSecret) — atm-ops uses CLIENT_SECRET_BASIC
		final String credentials = Base64.getEncoder()
				.encodeToString("atm-ops:atm-ops-secret".getBytes(StandardCharsets.UTF_8));
		final String formBody = "grant_type=client_credentials&scope="
				+ scopes.replace(" ", "+");

		try {
			final HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(baseUrl + "/oauth2/token"))
					.header("Authorization", "Basic " + credentials)
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.US_ASCII))
					.build();

			final HttpResponse<String> response = HttpClient.newHttpClient()
					.send(request, HttpResponse.BodyHandlers.ofString());

			final String responseBody = response.body();
			if (responseBody == null || responseBody.isBlank()) {
				throw new IllegalStateException(
						"Token endpoint returned empty body (status %d)".formatted(response.statusCode()));
			}
			final var json = new ObjectMapper().readTree(responseBody);
			final String token = json.at("/access_token").asString();
			if (token == null || token.isBlank()) {
				throw new IllegalStateException("No access_token in response: " + responseBody);
			}
			return "Bearer " + token;
		} catch (final IOException | InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Failed to fetch bearer token from " + baseUrl, e);
		}
	}
}
