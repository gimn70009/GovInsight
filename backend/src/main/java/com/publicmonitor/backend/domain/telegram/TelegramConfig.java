package com.publicmonitor.backend.domain.telegram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {
    @Bean(destroyMethod = "close")
    java.util.concurrent.ExecutorService telegramDeliveryExecutor() {
        return java.util.concurrent.Executors.newFixedThreadPool(3);
    }


    @Bean
    RestClient telegramRestClient(TelegramProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl("https://api.telegram.org")
                .requestFactory(requestFactory)
                .build();
    }
}