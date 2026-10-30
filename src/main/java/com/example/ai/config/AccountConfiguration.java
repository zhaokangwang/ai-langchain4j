package com.example.ai.config;

import com.example.ai.service.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {
    @Bean
    public PasswordEncoder accountPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ApplicationRunner initializeAdministrator(AccountService accounts,
            @Value("${app.accounts.initial-admin-password:admin}") String initialPassword) {
        return arguments -> accounts.initializeAdministrator(initialPassword);
    }
}
