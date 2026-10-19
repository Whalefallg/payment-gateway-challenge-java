package com.checkout.payment.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.repository.InMemoryPaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(HistoricalReplayIntegrationTest.ClockConfiguration.class)
class HistoricalReplayIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired InMemoryPaymentRepository repository;
  @Autowired MutableClock clock;
  @MockBean BankClient bankClient;

  @BeforeEach
  void setUp() {
    repository.clear();
    reset(bankClient);
    clock.set(Instant.parse("2026-01-15T00:00:00Z"));
  }

  @Test
  void historicalReplayUsesRealValidationAndIgnoresOnlyElapsedExpiry() throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    String body = """
        {"card_number":"2222405343240001","expiry_month":2,"expiry_year":2026,
         "currency":"GBP","amount":1050,"cvv":"007"}
        """;

    MvcResult initial = mvc.perform(post("/payments")
            .header("Idempotency-Key", "historical-key")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    String id = com.jayway.jsonpath.JsonPath.read(
        initial.getResponse().getContentAsString(), "$.id");

    clock.set(Instant.parse("2026-03-01T00:00:00Z"));
    mvc.perform(post("/payments")
            .header("Idempotency-Key", "historical-key")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.status").value("Authorized"));

    verify(bankClient, times(1)).process(any());
  }

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(Instant.parse("2026-01-15T00:00:00Z"));
    }
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    MutableClock(Instant initial) { this.instant = new AtomicReference<>(initial); }
    void set(Instant value) { instant.set(value); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
      return this;
    }
    @Override public Instant instant() { return instant.get(); }
  }
}

