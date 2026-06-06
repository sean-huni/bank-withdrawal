package com.example.bank.api.validation;

import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Rejects a {@link Pageable} whose sort references any property outside the
 * annotation's whitelist. The violation message lists only the allowed names
 * (trusted annotation values) — the client-supplied property is deliberately
 * never echoed into the message template, which would be an EL-injection
 * vector in the interpolator.
 */
public class AllowedSortPropertiesValidator implements ConstraintValidator<AllowedSortProperties, Pageable> {

	private Set<String> allowed;
	private String message;

	@Override
	public void initialize(final AllowedSortProperties constraint) {
		allowed = Set.of(constraint.value());
		message = "must be one of: %s".formatted(String.join(", ", constraint.value()));
	}

	@Override
	public boolean isValid(final Pageable pageable, final ConstraintValidatorContext context) {
		if (pageable == null || pageable.getSort().stream().map(Sort.Order::getProperty).allMatch(allowed::contains)) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		// property node "sort" surfaces as the violated field in the error envelope
		context.buildConstraintViolationWithTemplate(message)
				.addPropertyNode("sort")
				.addConstraintViolation();
		return false;
	}
}
