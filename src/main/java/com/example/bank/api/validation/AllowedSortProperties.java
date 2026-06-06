package com.example.bank.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Whitelists the property names a client may pass in the {@code sort} query
 * parameter of a {@code Pageable} endpoint. Spring binds {@code sort} into
 * {@code Pageable} unvalidated; any non-property string (Swagger UI's default
 * {@code ["string"]} placeholder, an unknown field, an SQL expression) only
 * blows up at query time inside Spring Data — far past the boundary where a
 * client error must be rejected as 400, and a deliberate injection probe must
 * never reach the SQL renderer.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AllowedSortPropertiesValidator.class)
public @interface AllowedSortProperties {

	String message() default "sort property is not sortable";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/** Property names clients may sort by. */
	String[] value();
}
