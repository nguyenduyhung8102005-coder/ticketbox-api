package com.ticketbox.payment.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    /**
     * HTTP client gọi API VNPay. Bắt buộc có timeout: job đối soát không được treo vô hạn khi VNPay chậm.
     * Dùng RestClient.Builder do Spring Boot cấu hình sẵn (đã có Jackson, observability).
     */
    @Bean
    public RestClient vnPayRestClient(RestClient.Builder builder, PaymentProperties properties) {
        PaymentProperties.VnPay config = properties.vnpay();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.connectTimeout());
        requestFactory.setReadTimeout(config.readTimeout());
        return builder.clone()
                .baseUrl(config.apiUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
