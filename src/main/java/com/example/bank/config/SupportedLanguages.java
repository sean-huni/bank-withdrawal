package com.example.bank.config;

import java.util.List;

/**
 * Single source of truth for the language tags shipped as bundles under
 * {@code i18n/} — read by the OpenAPI customizer and the dev banner. Adding a
 * locale = add the bundle file + extend this list (the catalog completeness
 * test keeps the bundle honest).
 */
public final class SupportedLanguages {

	public static final List<String> TAGS = List.of("en", "sn");

	private SupportedLanguages() {
	}
}
