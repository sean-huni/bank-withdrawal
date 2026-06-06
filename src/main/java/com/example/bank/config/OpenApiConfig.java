package com.example.bank.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Paths;

/**
 * springdoc is unaware of Spring Framework 7 native API versioning: the
 * {@code {api-version}} URI template variable is resolved by the framework's
 * path-segment resolver and never bound as a handler parameter, so the
 * generated spec would otherwise publish an unsubstitutable placeholder and
 * Swagger UI's "Try it out" would emit broken URLs. Rewrites every documented
 * path to the concrete supported version — single source of truth stays
 * {@link ApiVersioningConfig#SUPPORTED_VERSION} (this branch declares the
 * supported set programmatically, not via {@code spring.mvc.api-version.*}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	public OpenApiCustomizer versionedPathsCustomizer() {
		final String version = "v%s".formatted(ApiVersioningConfig.SUPPORTED_VERSION);
		return openApi -> {
			final Paths rewritten = new Paths();
			openApi.getPaths().forEach((path, item) ->
					rewritten.addPathItem(path.replace("{api-version}", version), item));
			openApi.setPaths(rewritten);
		};
	}
}
