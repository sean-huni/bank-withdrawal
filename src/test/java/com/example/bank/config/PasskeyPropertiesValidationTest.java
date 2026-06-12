package com.example.bank.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.example.bank.config.properties.PasskeyProperties;

/**
 * Fail-fast config validation (point 18) for the passkey relying party: a blank
 * mandatory {@code atm.passkey.*} value must abort context startup, and the
 * braced i18n key ({@code {config.passkey.rp-id.required}}) must resolve to
 * bundle text rather than leak the raw template. Valid config (the committed yml
 * defaults) must start. Mirrors {@code AwsPropertiesValidationTest}.
 */
class PasskeyPropertiesValidationTest {

	/**
	 * Mirrors production: the app MessageSource (i18n bundle) + the
	 * {@link I18nConfig} validator that routes constraint interpolation through
	 * it, so the braced key resolves to localized text exactly as at runtime.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PasskeyProperties.class)
	@org.springframework.context.annotation.Import(I18nConfig.class)
	static class Holder {

		@Bean
		MessageSource messageSource() {
			final ResourceBundleMessageSource source = new ResourceBundleMessageSource();
			source.setBasename("i18n/messages");
			source.setDefaultEncoding("UTF-8");
			return source;
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
			.withUserConfiguration(Holder.class)
			.withPropertyValues(
					"atm.passkey.rp-id=localhost",
					"atm.passkey.rp-name=Bank Withdrawal ATM",
					"atm.passkey.origins=http://localhost:5173");

	@Test
	void blankRpIdFailsFastWithResolvedMessage() {
		runner.withPropertyValues("atm.passkey.rp-id=")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					// braced key resolves through the shared MessageSource (localized bundle
					// text in the bind-validation cause), NOT the raw {config.passkey.rp-id.required}
					assertThat(ctx.getStartupFailure())
							.hasStackTraceContaining("Passkey relying-party id (rpId) is required")
							.hasStackTraceContaining("rpId");
				});
	}

	@Test
	void missingOriginsFailsFast() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
				.withUserConfiguration(Holder.class)
				.withPropertyValues(
						"atm.passkey.rp-id=localhost",
						"atm.passkey.rp-name=Bank Withdrawal ATM")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void validConfigStarts() {
		runner.run(ctx -> assertThat(ctx).hasNotFailed());
	}
}
