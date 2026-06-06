package com.example.bank.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
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
 * {@code spring.mvc.api-version.supported}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	public OpenApiCustomizer versionedPathsCustomizer(
			@Value("${spring.mvc.api-version.supported[0]}") final String version) {
		return openApi -> {
			final Paths rewritten = new Paths();
			openApi.getPaths().forEach((path, item) ->
					rewritten.addPathItem(path.replace("{api-version}", "v%s".formatted(version)), item));
			openApi.setPaths(rewritten);
		};
	}
}
