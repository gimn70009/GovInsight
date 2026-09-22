package com.publicmonitor.backend.domain.email;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {
    @Bean(destroyMethod = "close")
    ExecutorService emailDeliveryExecutor() { return Executors.newFixedThreadPool(3); }
    @Bean
    JavaMailSenderImpl reportMailSender(EmailProperties properties) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(properties.host()); sender.setPort(587);
        sender.setUsername(properties.username()); sender.setPassword(properties.password()); sender.setDefaultEncoding("UTF-8");
        var config = sender.getJavaMailProperties();
        config.setProperty("mail.smtp.auth", "true");
        config.setProperty("mail.smtp.starttls.enable", "true");
        config.setProperty("mail.smtp.starttls.required", "true");
        config.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        config.setProperty("mail.smtp.connectiontimeout", "3000");
        config.setProperty("mail.smtp.timeout", "10000");
        config.setProperty("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
