package com.jiangyudai.clinicflow.security.repository;

import com.jiangyudai.clinicflow.security.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByUsernameKey(String usernameKey);
}
