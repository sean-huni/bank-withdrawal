package com.example.bank.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.example.bank.api.dto.req.DepositRequest;
import com.example.bank.api.dto.req.WithdrawalRequest;
import com.example.bank.api.validation.AllowedSortProperties;
import com.example.bank.exception.ErrorCode;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;

/**
 * The catalog's safety net: a key referenced in code but missing from a bundle
 * must be a red build, never a silent runtime fallback. The bundle files are
 * loaded directly (not through MessageSource) because bundle-hierarchy
 * fallback to the base file would mask missing translations.
 */
class MessageCatalogCompletenessTest {

	private static final List<Class<?>> CONSTRAINED_PAYLOADS =
			List.of(WithdrawalRequest.class, DepositRequest.class);

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

	@Test
	void everyBracedConstraintTemplateIsDeclaredInTheBaseBundle() throws Exception {
		final Properties base = bundle("i18n/messages.properties");
		try (var factory = Validation.buildDefaultValidatorFactory()) {
			final Validator validator = factory.getValidator();
			for (final Class<?> payload : CONSTRAINED_PAYLOADS) {
				validator.getConstraintsForClass(payload).getConstrainedProperties().stream()
						.flatMap(property -> property.getConstraintDescriptors().stream())
						.map(ConstraintDescriptor::getMessageTemplate)
						.filter(template -> template.startsWith("{error."))
						.forEach(template -> assertThat(
								base.getProperty(template.substring(1, template.length() - 1)))
								.as("%s references %s", payload.getSimpleName(), template)
								.isNotBlank());
			}
		}
		// the sort whitelist's key lives on the annotation default, not a payload
		final String sortTemplate =
				(String) AllowedSortProperties.class.getMethod("message").getDefaultValue();
		assertThat(base.getProperty(sortTemplate.substring(1, sortTemplate.length() - 1)))
				.as("@AllowedSortProperties default message")
				.isNotBlank();
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
