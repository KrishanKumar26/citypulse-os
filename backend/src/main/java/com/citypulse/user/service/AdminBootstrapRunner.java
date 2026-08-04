package com.citypulse.user.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.service.AuditService;
import com.citypulse.common.config.BootstrapProperties;
import com.citypulse.user.domain.Role;
import com.citypulse.user.domain.RoleName;
import com.citypulse.user.domain.User;
import com.citypulse.user.domain.UserStatus;
import com.citypulse.user.repository.RoleRepository;
import com.citypulse.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Creates the first {@code SUPER_ADMIN} from configuration, so a fresh
 * deployment has a way in without shipping a default credential.
 *
 * <p>Three properties hold:
 * <ul>
 *   <li>Nothing happens unless both email and password are supplied.</li>
 *   <li>An existing account is never modified — no privilege is granted, and no
 *       password is reset, by restarting the application.</li>
 *   <li>The password is read from the environment and never logged.</li>
 * </ul>
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final BootstrapProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminBootstrapRunner(BootstrapProperties properties,
                                UserRepository userRepository,
                                RoleRepository roleRepository,
                                PasswordEncoder passwordEncoder,
                                AuditService auditService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("No bootstrap administrator configured; skipping. "
                     + "Set CITYPULSE_BOOTSTRAP_ADMIN_EMAIL and CITYPULSE_BOOTSTRAP_ADMIN_PASSWORD to create one.");
            return;
        }

        String email = properties.adminEmail().trim().toLowerCase();

        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            // Deliberately does not update the existing account: a restart must
            // never silently reset a password or re-grant SUPER_ADMIN.
            log.info("Bootstrap administrator already exists; leaving it unchanged.");
            return;
        }

        Role superAdmin = roleRepository.findByNameAndDeletedAtIsNull(RoleName.SUPER_ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "SUPER_ADMIN role is missing; check migration V2"));

        User admin = new User();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(properties.adminPassword()));
        admin.setFullName(properties.adminName() == null || properties.adminName().isBlank()
                ? "Platform Administrator" : properties.adminName().trim());
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerified(true);
        admin.setPasswordChangedAt(Instant.now());
        admin.getRoles().add(superAdmin);

        User saved = userRepository.save(admin);

        auditService.recordResourceChange(AuditAction.USER_CREATED, saved.getId(), saved.getEmail(),
                "USER", saved.getUid().toString(), "Bootstrap SUPER_ADMIN created from configuration");

        log.warn("Created bootstrap SUPER_ADMIN account for {}. "
                 + "Sign in and change this password before exposing the service.", email);
    }
}
