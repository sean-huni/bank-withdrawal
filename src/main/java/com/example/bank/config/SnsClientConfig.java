package com.example.bank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Central {@link SnsClient} bean — replaces the original snippet's
 * constructor-built client so the client is configurable, testable and shared.
 */
@Configuration(proxyBeanMethods = false)
public class SnsClientConfig {

	@Bean
	public SnsClient snsClient(final AwsProperties properties) {
		final var builder = SnsClient.builder()
				.region(Region.of(properties.region()))
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
		if (properties.endpoint() != null) {
			builder.endpointOverride(properties.endpoint());
		}
		return builder.build();
	}
}
