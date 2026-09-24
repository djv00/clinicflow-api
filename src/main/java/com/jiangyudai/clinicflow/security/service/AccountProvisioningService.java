package com.jiangyudai.clinicflow.security.service;

import com.jiangyudai.clinicflow.security.entity.AccountRole;
import com.jiangyudai.clinicflow.security.entity.UserAccount;
import com.jiangyudai.clinicflow.security.repository.UserAccountRepository;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public class AccountProvisioningService {

    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;

    public AccountProvisioningService(UserAccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    @Transactional
    public void initialize(SecurityProperties.User operator, String viewerUsername, String viewerPassword) {
        initialize(operator, viewerUsername, viewerPassword, "admin", "");
    }

    @Transactional
    public void initialize(SecurityProperties.User operator, String viewerUsername, String viewerPassword,
                           String adminUsername, String adminPassword) {
        UserAccount.validateUsername(operator.getName());
        boolean provisionViewer = StringUtils.hasText(viewerPassword);
        if (provisionViewer) {
            UserAccount.validateUsername(viewerUsername);
            Assert.isTrue(!UserAccount.usernameKey(operator.getName()).equals(UserAccount.usernameKey(viewerUsername)),
                    "Viewer and operator usernames must be different");
        }
        boolean provisionAdmin = StringUtils.hasText(adminPassword);
        if (provisionAdmin) {
            UserAccount.validateUsername(adminUsername);
            Assert.isTrue(!UserAccount.usernameKey(adminUsername).equals(UserAccount.usernameKey(operator.getName()))
                            && (!provisionViewer || !UserAccount.usernameKey(adminUsername).equals(UserAccount.usernameKey(viewerUsername))),
                    "Administrator username must differ from other configured accounts");
        }
        provision(operator.getName(), operator.isPasswordGenerated() ? null : operator.getPassword(), AccountRole.OPERATOR);
        if (provisionViewer) {
            provision(viewerUsername, viewerPassword, AccountRole.VIEWER);
        }
        if (provisionAdmin) {
            provision(adminUsername, adminPassword, AccountRole.ADMIN);
        }
    }

    private void provision(String username, String password, AccountRole role) {
        var existing = accounts.findByUsernameKey(UserAccount.usernameKey(username));
        if (existing.isPresent()) {
            Assert.state(existing.get().getRole() == role,
                    "Configured account already exists with a different role");
            // Startup configuration must not reset passwords or re-enable an account.
            return;
        }
        Assert.hasText(password, "Set SPRING_SECURITY_USER_PASSWORD to initialize a PostgreSQL operator account");
        accounts.save(new UserAccount(username, encoder.encode(password), role));
    }
}
