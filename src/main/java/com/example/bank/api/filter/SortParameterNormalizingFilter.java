package com.example.bank.api.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Swagger UI renders Pageable's {@code sort} as an editable JSON array and can
 * submit the raw text as a single parameter value ({@code sort=["type,asc"]}),
 * while Spring Data expects plain {@code property(,direction)} values. Unwraps
 * that JSON decoration before argument binding so decorated requests behave
 * exactly like their plain equivalents — whether the URL arrived
 * percent-encoded or raw (the latter let through by
 * {@code server.tomcat.relaxed-query-chars}). The unwrapped properties still
 * pass through the sort whitelist; nothing is validated here.
 */
@Component
@RequiredArgsConstructor
public class SortParameterNormalizingFilter extends OncePerRequestFilter {

	private static final String SORT_PARAMETER = "sort";

	private final ObjectMapper objectMapper;

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain filterChain) throws ServletException, IOException {
		final String[] raw = request.getParameterValues(SORT_PARAMETER);
		final String[] normalized = normalize(raw);
		filterChain.doFilter(Arrays.equals(raw, normalized)
				? request
				: new NormalizedSortRequest(request, normalized), response);
	}

	private String[] normalize(final String[] values) {
		return values == null
				? null
				: Arrays.stream(values).flatMap(value -> normalize(value).stream()).toArray(String[]::new);
	}

	/** {@code ["a","b"]} → a, b; {@code "a"} → a; anything else passes through untouched. */
	private List<String> normalize(final String value) {
		final String trimmed = value.trim();
		if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
			return jsonArrayItems(trimmed);
		}
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return List.of(trimmed.substring(1, trimmed.length() - 1));
		}
		return List.of(value);
	}

	private List<String> jsonArrayItems(final String value) {
		try {
			return objectMapper.readValue(value, new TypeReference<List<String>>() {
					}).stream()
					.map(String::trim)
					.filter(item -> !item.isEmpty())
					.toList();
		} catch (final JacksonException e) {
			// bracketed but not valid JSON — strip the decoration and let validation judge the rest
			return List.of(value.replaceAll("[\\[\\]\"]", ""));
		}
	}

	/** Servlet-API view of the request with the sort values replaced. */
	private static final class NormalizedSortRequest extends HttpServletRequestWrapper {

		private final String[] sortValues;

		private NormalizedSortRequest(final HttpServletRequest request, final String[] sortValues) {
			super(request);
			this.sortValues = sortValues;
		}

		@Override
		public String getParameter(final String name) {
			return SORT_PARAMETER.equals(name)
					? (sortValues.length == 0 ? null : sortValues[0])
					: super.getParameter(name);
		}

		@Override
		public String[] getParameterValues(final String name) {
			return SORT_PARAMETER.equals(name) ? sortValues.clone() : super.getParameterValues(name);
		}

		@Override
		public Map<String, String[]> getParameterMap() {
			final Map<String, String[]> parameters = new LinkedHashMap<>(super.getParameterMap());
			parameters.put(SORT_PARAMETER, sortValues.clone());
			return Collections.unmodifiableMap(parameters);
		}
	}
}
