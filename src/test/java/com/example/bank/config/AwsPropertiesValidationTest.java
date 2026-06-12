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

/**
 * Fail-fast config validation (point 18): a blank mandatory {@code app.aws.*}
 * value must abort context startup, and the braced i18n key
 * ({@code {config.aws.region.required}}) must resolve to bundle text rather than
 * leak the raw template. Valid config (the committed yml defaults) must start.
 */
class AwsPropertiesValidationTest {

	/**
	 * Mirrors production: the app MessageSource (i18n bundle) + the
	 * {@link I18nConfig} validator that routes constraint interpolation through
	 * it, so the braced key resolves to localized text exactly as at runtime.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AwsProperties.class)
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
					"app.aws.endpoint=http://localhost:4566",
					"app.aws.access-key=test",
					"app.aws.secret-key=test",
					"app.aws.sns.withdrawal-topic-arn=arn:aws:sns:us-east-1:000000000000:t");

	@Test
	void blankRegionFailsFastWithResolvedMessage() {
		runner.withPropertyValues("app.aws.region=")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					// braced key resolves through the shared MessageSource (localized bundle
					// text in the bind-validation cause), NOT the raw {config.aws.region.required}
					assertThat(ctx.getStartupFailure())
							.hasStackTraceContaining("AWS region is required")
							.hasStackTraceContaining("region");
				});
	}

	@Test
	void missingTopicArnFailsFast() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
				.withUserConfiguration(Holder.class)
				.withPropertyValues(
						"app.aws.region=us-east-1",
						"app.aws.access-key=test",
						"app.aws.secret-key=test",
						"app.aws.sns.withdrawal-topic-arn=")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void validConfigStarts() {
		runner.withPropertyValues("app.aws.region=us-east-1")
				.run(ctx -> assertThat(ctx).hasNotFailed());
	}
}
