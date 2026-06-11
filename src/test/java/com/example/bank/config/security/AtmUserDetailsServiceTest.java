package com.example.bank.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.bank.config.properties.SecurityProperties;
import com.example.bank.data.model.AccountEntity;
import com.example.bank.data.repo.AccountRepo;

@ExtendWith(MockitoExtension.class)
class AtmUserDetailsServiceTest {

	private static final UUID ACCOUNT_ID = UUID.randomUUID();

	@Mock
	private AccountRepo accountRepo;

	private final PasswordEncoder encoder = new BCryptPasswordEncoder();
	private AtmUserDetailsService service;

	@BeforeEach
	void setUp() {
		service = new AtmUserDetailsService(
				new SecurityProperties("operator", "atm-demo", "atm-ops-secret"), accountRepo, encoder);
	}

	@Test
	void operatorResolvesWithRoleOperatorAndMatchingPassword() {
		final UserDetails operator = service.loadUserByUsername("operator");
		assertThat(operator.getAuthorities()).extracting("authority").containsExactly("ROLE_OPERATOR");
		assertThat(encoder.matches("atm-demo", operator.getPassword())).isTrue();
	}

	@Test
	void accountIdResolvesAsCustomerWhenAccountExists() {
		final AccountEntity account = mock(AccountEntity.class);
		when(account.getId()).thenReturn(ACCOUNT_ID);
		when(accountRepo.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

		final UserDetails customer = service.loadUserByUsername(ACCOUNT_ID.toString());

		assertThat(customer.getUsername()).isEqualTo(ACCOUNT_ID.toString());
		assertThat(customer.getAuthorities()).extracting("authority").containsExactly("ROLE_CUSTOMER");
		assertThat(encoder.matches("anything", customer.getPassword())).isFalse();
	}

	@Test
	void unknownAccountIdIsRefused() {
		when(accountRepo.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.loadUserByUsername(ACCOUNT_ID.toString()))
				.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void nonUuidNonOperatorUsernameIsRefused() {
		assertThatThrownBy(() -> service.loadUserByUsername("admin'; DROP TABLE--"))
				.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void nullUsernameIsRefused() {
		assertThatThrownBy(() -> service.loadUserByUsername(null))
				.isInstanceOf(UsernameNotFoundException.class);
	}
}
