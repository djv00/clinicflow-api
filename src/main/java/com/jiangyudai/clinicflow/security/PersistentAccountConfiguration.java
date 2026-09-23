package com.jiangyudai.clinicflow.security;

import com.jiangyudai.clinicflow.security.repository.UserAccountRepository;
import com.jiangyudai.clinicflow.security.service.AccountProvisioningService;
import com.jiangyudai.clinicflow.security.service.PersistentUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PersistentAccountConfiguration {

    @Bean
    UserDetailsService persistentUserDetailsService(UserAccountRepository accounts) {
        return new PersistentUserDetailsService(accounts);
    }

    @Bean
    AccountProvisioningService accountProvisioningService(UserAccountRepository accounts, PasswordEncoder encoder) {
        return new AccountProvisioningService(accounts, encoder);
    }

    @Bean
    ApplicationRunner initializeAccounts(AccountProvisioningService provisioning, SecurityProperties properties,
            @Value("${clinicflow.security.viewer.username:viewer}") String viewerUsername,
            @Value("${clinicflow.security.viewer.password:}") String viewerPassword,
            @Value("${clinicflow.security.admin.username:admin}") String adminUsername,
            @Value("${clinicflow.security.admin.password:}") String adminPassword) {
        return arguments -> provisioning.initialize(properties.getUser(), viewerUsername, viewerPassword,
                adminUsername, adminPassword);
    }
}
