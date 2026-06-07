package com.example.bank.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Routes Bean Validation interpolation through the application MessageSource so
 * constraint annotations and the advice share ONE catalog (i18n/messages*).
 * Boot's auto-configured validator backs off in favour of this bean; Spring
 * wraps the interpolator in a LocaleContextMessageInterpolator, so
 * Accept-Language is honored without extra code. Keys missing from our bundle
 * (e.g. built-in {jakarta.validation...} templates) still fall through to
 * Hibernate Validator's own bundles.
 */
@Configuration(proxyBeanMethods = false)
public class I18nConfig {

	@Bean
	public LocalValidatorFactoryBean validator(final MessageSource messageSource) {
		final LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
		factoryBean.setValidationMessageSource(messageSource);
		return factoryBean;
	}
}
