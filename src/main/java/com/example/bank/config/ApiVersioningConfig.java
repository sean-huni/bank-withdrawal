package com.example.bank.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Framework 7 native API versioning, configured in Java rather than
 * {@code spring.mvc.api-version.*} properties for one reason: only the
 * programmatic API accepts the path predicate that scopes versioning to
 * {@code /api/**}. Without it the path-segment resolver versions EVERY
 * request — springdoc's {@code /v3/api-docs} was being rejected as
 * "API version 'api-docs' is not supported".
 */
@Configuration(proxyBeanMethods = false)
public class ApiVersioningConfig implements WebMvcConfigurer {

	/** Single source of truth for the supported version — also drives the OpenAPI path rewrite. */
	static final String SUPPORTED_VERSION = "1";

	private static final int VERSION_SEGMENT_INDEX = 1; // /api/{api-version}/... (0-based)

	@Override
	public void configureApiVersioning(final ApiVersionConfigurer configurer) {
		configurer
				.usePathSegment(VERSION_SEGMENT_INDEX, path -> path.value().startsWith("/api/"))
				.addSupportedVersions(SUPPORTED_VERSION);
	}
}
