package com.example.bank.api.validation;

import java.util.Set;

import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Rejects a {@link Pageable} whose sort references any property outside the
 * annotation's whitelist. The allowed list travels as a Hibernate Validator
 * MESSAGE PARAMETER ({list}) — never string-built into the template, which
 * would be an EL-injection vector; the client-supplied property is still
 * deliberately never echoed.
 */
public class AllowedSortPropertiesValidator implements ConstraintValidator<AllowedSortProperties, Pageable> {

	private Set<String> allowed;
	private String allowedList;

	@Override
	public void initialize(final AllowedSortProperties constraint) {
		allowed = Set.of(constraint.value());
		allowedList = String.join(", ", constraint.value());
	}

	@Override
	public boolean isValid(final Pageable pageable, final ConstraintValidatorContext context) {
		if (pageable == null || pageable.getSort().stream().map(Sort.Order::getProperty).allMatch(allowed::contains)) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("list", allowedList);
		// default template = "{error.sort.unsupported}" → resolved from the shared bundle
		context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addPropertyNode("sort")
				.addConstraintViolation();
		return false;
	}
}
