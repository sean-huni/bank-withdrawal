package com.example.bank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine3.23"));
	}

	@Bean
	LocalStackContainer localStackContainer() {
		// 4.x community line — the 2026.x CalVer images require a license token
		return new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14"))
				.withServices(LocalStackContainer.Service.SNS)
				// same ready-hook as compose.yml — creates the withdrawal topic
				.withCopyFileToContainer(
						MountableFile.forHostPath("localstack/init-sns.sh"),
						"/etc/localstack/init/ready.d/init-sns.sh");
	}

	/**
	 * No built-in {@code @ServiceConnection} exists for LocalStack (that lives in
	 * Spring Cloud AWS, which has no Boot 4 release yet) — wire the endpoint manually.
	 */
	@Bean
	DynamicPropertyRegistrar awsProperties(final LocalStackContainer localStack) {
		return registry -> {
			registry.add("app.aws.endpoint", () -> localStack.getEndpoint().toString());
			registry.add("app.aws.region", localStack::getRegion);
			registry.add("app.aws.access-key", localStack::getAccessKey);
			registry.add("app.aws.secret-key", localStack::getSecretKey);
		};
	}
}
