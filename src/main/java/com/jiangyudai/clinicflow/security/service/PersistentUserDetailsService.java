package com.jiangyudai.clinicflow.security.service;

import com.jiangyudai.clinicflow.security.entity.UserAccount;
import com.jiangyudai.clinicflow.security.repository.UserAccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

public class PersistentUserDetailsService implements UserDetailsService {

    private final UserAccountRepository accounts;

    public PersistentUserDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        var account = accounts.findByUsernameKey(UserAccount.usernameKey(username))
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        return User.withUsername(account.getUsername()).password(account.getPasswordHash())
                .roles(account.getRole().name()).disabled(!account.isEnabled()).build();
    }
}
