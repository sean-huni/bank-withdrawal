/**
 * REST controllers — the HTTP entry points (canon: {@code ctl/}). Lean
 * pass-throughs: validate, delegate to the service, wrap in the response
 * envelope. Boundary plumbing (advice/filter/validation) lives in
 * {@code com.example.bank.api}.
 */
package com.example.bank.ctl;
