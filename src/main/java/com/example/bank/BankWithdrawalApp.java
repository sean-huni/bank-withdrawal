package com.example.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankWithdrawalApp {

	static void main(final String[] args) {
		SpringApplication.run(BankWithdrawalApp.class, args);
	}
}
