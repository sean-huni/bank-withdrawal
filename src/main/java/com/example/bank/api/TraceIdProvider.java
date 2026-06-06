package com.example.bank.api;

import org.springframework.stereotype.Component;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;

/**
 * Resolves the current trace id for the response envelope — one place instead
 * of duplicating tracer null-handling in every controller and advice.
 */
@Component
@RequiredArgsConstructor
public class TraceIdProvider {

	private final Tracer tracer;

	public String currentTraceId() {
		return tracer.currentSpan() == null ? null : tracer.currentSpan().context().traceId();
	}
}
