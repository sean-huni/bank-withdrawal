package com.example.bank.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AWS connection settings. Defaults (application.yml) point at the LocalStack
 * container from compose.yml; real environments would omit {@code endpoint}
 * and rely on the default credentials provider chain instead of static keys.
 *
 * <p>Validated at startup ({@code @Validated}) so a missing mandatory value
 * fails fast rather than surfacing as an opaque AWS error on first publish.
 * {@code endpoint} is intentionally optional (omitted against real AWS). Messages
 * are braced i18n keys so the failure is localized and machine-readable.
 */
@Validated
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
		@NotBlank(message = "{config.aws.region.required}") String region,
		URI endpoint,
		@NotBlank(message = "{config.aws.access-key.required}") String accessKey,
		@NotBlank(message = "{config.aws.secret-key.required}") String secretKey,
		@NotNull(message = "{config.aws.sns.required}") @Valid Sns sns) {

	public record Sns(
			@NotBlank(message = "{config.aws.sns.topic-arn.required}") String withdrawalTopicArn) {
	}
}
