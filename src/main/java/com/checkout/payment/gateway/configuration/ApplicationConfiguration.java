package com.checkout.payment.gateway.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.time.Duration;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ApplicationConfiguration {

  @Bean
  public RestTemplate restTemplate(
      RestTemplateBuilder builder,
      @Value("${bank.connect-timeout}") Duration connectTimeout,
      @Value("${bank.read-timeout}") Duration readTimeout) {
    return builder
        .setConnectTimeout(connectTimeout)
        .setReadTimeout(readTimeout)
        .build();
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer strictJsonTypes() {
    return builder -> builder
        .featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
  }
}
