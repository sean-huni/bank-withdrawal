package com.example.bank.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.example.bank.exception.ErrorCode;

/**
 * The catalog's safety net: a key referenced in code but missing from a bundle
 * must be a red build, never a silent runtime fallback. The bundle files are
 * loaded directly (not through MessageSource) because bundle-hierarchy
 * fallback to the base file would mask missing translations.
 */
class MessageCatalogCompletenessTest {

	@Test
	void everyErrorCodeKeyIsDeclaredInTheBaseBundle() throws IOException {
		final Properties base = bundle("i18n/messages.properties");
		for (final ErrorCode code : ErrorCode.values()) {
			assertThat(base.getProperty(code.messageKey()))
					.as("messages.properties must declare %s (used by ErrorCode.%s)", code.messageKey(), code)
					.isNotBlank();
		}
	}

	@Test
	void everyTranslatedBundleDeclaresExactlyTheBaseKeys() throws IOException {
		final Properties base = bundle("i18n/messages.properties");
		final Properties shona = bundle("i18n/messages_sn.properties");
		assertThat(shona.stringPropertyNames())
				.containsExactlyInAnyOrderElementsOf(base.stringPropertyNames());
		// key-set parity alone would let a whitespace "translation" through
		for (final String key : base.stringPropertyNames()) {
			assertThat(shona.getProperty(key))
					.as("messages_sn.properties value for '%s' must not be blank", key)
					.isNotBlank();
		}
	}

	private Properties bundle(final String classpathLocation) throws IOException {
		final Properties properties = new Properties();
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(classpathLocation)) {
			assertThat(stream).as("bundle %s must exist on the classpath", classpathLocation).isNotNull();
			properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
		}
		return properties;
	}
}
