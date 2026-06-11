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

import com.example.bank.config.properties.SecurityProperties;

/**
 * Fail-fast config validation for the OAuth 2.1 identity properties: a blank
 * mandatory {@code atm.security.*} value must abort context startup, and the
 * braced i18n key (e.g. {@code {config.security.operator-username.required}})
 * must resolve to bundle text rather than leak the raw template. Valid config
 * (the committed yml defaults) must start. Mirrors
 * {@code PasskeyPropertiesValidationTest}.
 */
class SecurityPropertiesValidationTest {

	/**
	 * Mirrors production: the app MessageSource (i18n bundle) + the
	 * {@link I18nConfig} validator that routes constraint interpolation through
	 * it, so the braced key resolves to localized text exactly as at runtime.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SecurityProperties.class)
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
					"atm.security.operator-username=operator",
					"atm.security.operator-password=atm-demo",
					"atm.security.ops-client-secret=atm-ops-secret");

	@Test
	void validConfigStarts() {
		runner.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void blankOperatorUsernameFailsFastWithResolvedMessage() {
		runner.withPropertyValues("atm.security.operator-username=")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					// braced key resolves through the shared MessageSource (localized bundle
					// text in the bind-validation cause), NOT the raw {config.security.operator-username.required}
					assertThat(ctx.getStartupFailure())
							.hasStackTraceContaining("Operator username must be configured (ATM_OPERATOR_USERNAME)")
							.hasStackTraceContaining("operatorUsername");
				});
	}

	@Test
	void blankOpsClientSecretFailsFastWithResolvedMessage() {
		runner.withPropertyValues("atm.security.ops-client-secret=")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					// braced key resolves through the shared MessageSource (localized bundle
					// text in the bind-validation cause), NOT the raw {config.security.ops-client-secret.required}
					assertThat(ctx.getStartupFailure())
							.hasStackTraceContaining("Ops client secret must be configured (ATM_OPS_CLIENT_SECRET)")
							.hasStackTraceContaining("opsClientSecret");
				});
	}
}
