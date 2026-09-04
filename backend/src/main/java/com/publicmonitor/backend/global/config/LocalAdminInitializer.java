package com.publicmonitor.backend.global.config;

import com.publicmonitor.backend.domain.user.entity.Role;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(LocalAdminProperties.class)
public class LocalAdminInitializer implements ApplicationRunner {

    private final LocalAdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        if (!StringUtils.hasText(properties.loginId()) || !StringUtils.hasText(properties.password())) {
            throw new IllegalStateException("로컬 관리자 생성이 활성화되면 아이디와 비밀번호가 필요합니다.");
        }
        if (userRepository.existsByLoginId(properties.loginId())) {
            return;
        }

        User admin = User.create(
                properties.loginId(),
                passwordEncoder.encode(properties.password()),
                Role.ADMIN
        );
        userRepository.save(admin);
    }
}
