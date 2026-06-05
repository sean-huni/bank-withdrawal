package com.example.bank.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS connection settings. Defaults (application.yaml) point at the LocalStack
 * container from compose.yaml; real environments would omit {@code endpoint}
 * and rely on the default credentials provider chain instead of static keys.
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
		String region,
		URI endpoint,
		String accessKey,
		String secretKey,
		Sns sns) {

	public record Sns(String withdrawalTopicArn) {
	}
}
