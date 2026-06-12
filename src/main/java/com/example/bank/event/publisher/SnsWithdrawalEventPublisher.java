package com.example.bank.event.publisher;

import com.example.bank.config.AwsProperties;
import com.example.bank.event.WithdrawalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * SNS adapter for the {@link WithdrawalEventPublisher} port. Failures are
 * logged, not propagated — the withdrawal has already committed and must not
 * appear failed to the caller. Guaranteed delivery would require a
 * transactional outbox (see README).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnsWithdrawalEventPublisher implements WithdrawalEventPublisher {

	private final SnsClient snsClient;
	private final ObjectMapper objectMapper;
	private final AwsProperties awsProperties;

	@Override
	public void publish(final WithdrawalEvent event) {
		try {
			final String message = objectMapper.writeValueAsString(event);
			snsClient.publish(PublishRequest.builder()
					.topicArn(awsProperties.sns().withdrawalTopicArn())
					.message(message)
					.build());
			log.info("Published withdrawal event {} for account {}", event.eventId(), event.accountId());
		} catch (final Exception e) {
			log.error("Failed to publish withdrawal event {} for account {}",
					event.eventId(), event.accountId(), e);
		}
	}
}
