package com.checkout.payment.gateway.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentGatewayControllerTest {

  @Autowired MockMvc mvc;
  @Autowired PaymentsRepository repository;
  @Autowired Clock clock;
  @Autowired ObjectMapper objectMapper;
  @MockBean BankClient bankClient;

  @BeforeEach
  void setUp() {
    repository.clear();
    reset(bankClient);
  }

  @Test
  void authorizedPaymentIsCreatedAndRetrievable() throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    MvcResult created = mvc.perform(post("/payments")
            .contentType(MediaType.APPLICATION_JSON).content(validJson(1050)))
        .andExpect(status().isCreated())
        .andExpect(header().exists("X-Request-ID"))
        .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/payments/.+")))
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.card_number_last_four").value("0001"))
        .andExpect(jsonPath("$.cvv").doesNotExist())
        .andExpect(jsonPath("$.card_number").doesNotExist())
        .andReturn();
    String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(get("/payments/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.card_number_last_four").value("0001"));
  }

  @Test
  void declinedPaymentIsStillCreated() throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.DECLINED);
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(validJson(0).replace("0001", "0002")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Declined"));
  }

  @Test
  void unknownAndMalformedIdsHaveDistinctErrors() throws Exception {
    mvc.perform(get("/payments/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
        .andExpect(jsonPath("$.status").doesNotExist());
    mvc.perform(get("/payments/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_ID"));
  }

  @Test
  void invalidRequestIsRejectedBeforeBankCall() throws Exception {
    String invalid = validJson(1050).replace(
        "\"expiry_month\":%d".formatted(future().getMonthValue()), "\"expiry_month\":13");
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"))
        .andExpect(jsonPath("$.details", hasSize(1)));
    verify(bankClient, never()).process(any(PostPaymentRequest.class));
    org.junit.jupiter.api.Assertions.assertEquals(0, repository.size());
  }

  @Test
  void strictJsonTypesAreRejectedWithoutBankCall() throws Exception {
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(validJson(1).replace("\"2222405343240001\"", "2222405343240001")))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(validJson(1).replace("\"007\"", "7")))
        .andExpect(status().isBadRequest());
    verify(bankClient, never()).process(any());
  }

  @Test
  void zeroAndNegativeIntegerAmountsAreAccepted() throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(validJson(0)))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(0));
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(validJson(-1)))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(-1));
  }

  @Test
  void currentMonthAndInvalidCardFieldsAreRejected() throws Exception {
    YearMonth current = YearMonth.now(clock);
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(json(current.getMonthValue(), current.getYear(), "2222405343240001", "USD", "007", "1")))
        .andExpect(status().isBadRequest());
    for (String body : new String[] {
        json(future().getMonthValue(), future().getYear(), "1234567890123", "USD", "007", "1"),
        json(future().getMonthValue(), future().getYear(), "12345678901234X", "USD", "007", "1"),
        json(future().getMonthValue(), future().getYear(), "2222405343240001", "usd", "007", "1"),
        json(future().getMonthValue(), future().getYear(), "2222405343240001", "USD", "12", "1")}) {
      mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    verify(bankClient, never()).process(any());
  }

  @Test
  void cardCvvAndMonthBoundariesAreEnforced() throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    int futureYear = YearMonth.now(clock).plusYears(2).getYear();
    for (String valid : new String[] {
        json(6, futureYear, "12345678901234", "EUR", "123", "1"),
        json(6, futureYear, "1234567890123456789", "USD", "1234", "1")}) {
      mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(valid))
          .andExpect(status().isCreated());
    }
    reset(bankClient);
    for (String invalid : new String[] {
        json(6, futureYear, "1234567890123", "USD", "123", "1"),
        json(6, futureYear, "12345678901234567890", "USD", "123", "1"),
        json(6, futureYear, "12345678901234", "USD", "12", "1"),
        json(6, futureYear, "12345678901234", "USD", "12345", "1"),
        json(6, futureYear, "12345678901234", " USD", "123", "1"),
        json(6, futureYear, "12345678901234", "JPY", "123", "1")}) {
      mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(invalid))
          .andExpect(status().isBadRequest());
    }
    verify(bankClient, never()).process(any());
  }

  @ParameterizedTest(name = "missing required field {0}")
  @MethodSource("requiredFields")
  void missingRequiredFieldsAreRejected(String field) throws Exception {
    ObjectNode request = (ObjectNode) objectMapper.readTree(validJson(1));
    request.remove(field);
    assertRejectedWithoutBankCall(objectMapper.writeValueAsString(request));
  }

  @ParameterizedTest(name = "null required field {0}")
  @MethodSource("requiredFields")
  void nullRequiredFieldsAreRejected(String field) throws Exception {
    ObjectNode request = (ObjectNode) objectMapper.readTree(validJson(1));
    request.putNull(field);
    assertRejectedWithoutBankCall(objectMapper.writeValueAsString(request));
  }

  @ParameterizedTest(name = "expiry month {0} returns HTTP {1}")
  @MethodSource("expiryMonthBoundaries")
  void expiryMonthBoundariesAreEnforced(int month, int expectedStatus) throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    int futureYear = YearMonth.now(clock).plusYears(2).getYear();
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(json(month, futureYear, "2222405343240001", "USD", "007", "1")))
        .andExpect(status().is(expectedStatus));
    if (expectedStatus == 400) {
      verify(bankClient, never()).process(any());
    } else {
      verify(bankClient).process(any());
    }
  }

  @ParameterizedTest(name = "invalid expiry year token {0}")
  @MethodSource("invalidExpiryYears")
  void invalidExpiryYearRangesAndOverflowAreRejected(String yearToken) throws Exception {
    YearMonth future = future();
    assertRejectedWithoutBankCall(jsonWithTokens(
        Integer.toString(future.getMonthValue()), yearToken, "1"));
  }

  @Test
  void expiredCurrentAndNextMonthFollowUtcContract() throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    YearMonth current = YearMonth.now(clock);
    for (YearMonth invalid : new YearMonth[] {current.minusMonths(1), current}) {
      mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
              .content(json(invalid.getMonthValue(), invalid.getYear(),
                  "2222405343240001", "USD", "007", "1")))
          .andExpect(status().isBadRequest());
    }
    verify(bankClient, never()).process(any());
    reset(bankClient);
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    YearMonth next = current.plusMonths(1);
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(json(next.getMonthValue(), next.getYear(),
                "2222405343240001", "USD", "007", "1")))
        .andExpect(status().isCreated());
    verify(bankClient).process(any());
  }

  @ParameterizedTest(name = "invalid amount JSON token {0}")
  @MethodSource("invalidAmountTokens")
  void invalidAmountTypesAndOverflowAreRejected(String amountToken) throws Exception {
    assertRejectedWithoutBankCall(validJsonToken(amountToken));
  }

  @ParameterizedTest(name = "valid amount integer boundary {0}")
  @MethodSource("validAmountBoundaries")
  void signedIntegerAmountBoundariesAreAccepted(String amountToken) throws Exception {
    when(bankClient.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(validJsonToken(amountToken)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(Long.parseLong(amountToken)));
    verify(bankClient).process(any());
  }

  @Test
  void malformedJsonAndProtocolErrorsAreSanitized() throws Exception {
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content("{\"card_number\":\"sensitive-value"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Payment request validation failed"));
    mvc.perform(post("/payments").contentType(MediaType.TEXT_PLAIN).content("anything"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").doesNotExist());
    mvc.perform(put("/payments")
            .contentType(MediaType.APPLICATION_JSON).content(validJson(1)))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "POST"))
        .andExpect(jsonPath("$.status").doesNotExist());
    mvc.perform(get("/payments/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    verify(bankClient, never()).process(any());
  }

  @Test
  void upstreamFailureUsesInfrastructureErrorAndCreatesNoRecord() throws Exception {
    when(bankClient.process(any())).thenThrow(new GatewayException(
        HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE", "Bank is unavailable"));
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(validJson(1)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("BANK_UNAVAILABLE"))
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.request_id").isNotEmpty());
    org.junit.jupiter.api.Assertions.assertEquals(0, repository.size());
  }

  @Test
  void correlationIdsAreGeneratedPerRequest() throws Exception {
    String first = mvc.perform(get("/payments/{id}", UUID.randomUUID()))
        .andReturn().getResponse().getHeader("X-Request-ID");
    String second = mvc.perform(get("/payments/{id}", UUID.randomUUID()))
        .andReturn().getResponse().getHeader("X-Request-ID");
    org.junit.jupiter.api.Assertions.assertNotNull(first);
    org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
  }

  private String validJson(int amount) { return validJsonToken(Integer.toString(amount)); }
  private String validJsonToken(String amount) {
    YearMonth expiry = future();
    return json(expiry.getMonthValue(), expiry.getYear(), "2222405343240001", "GBP", "007", amount);
  }
  private YearMonth future() { return YearMonth.now(clock).plusMonths(2); }
  private String json(int month, int year, String card, String currency, String cvv, String amount) {
    return """
        {"card_number":"%s","expiry_month":%d,"expiry_year":%d,"currency":"%s","amount":%s,"cvv":"%s"}
        """.formatted(card, month, year, currency, amount, cvv);
  }

  private String jsonWithTokens(String month, String year, String amount) {
    return """
        {"card_number":"2222405343240001","expiry_month":%s,"expiry_year":%s,"currency":"USD","amount":%s,"cvv":"007"}
        """.formatted(month, year, amount);
  }

  private void assertRejectedWithoutBankCall(String content) throws Exception {
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(content))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
    verify(bankClient, never()).process(any());
  }

  private static Stream<String> requiredFields() {
    return Stream.of("card_number", "expiry_month", "expiry_year", "currency", "amount", "cvv");
  }

  private static Stream<Arguments> expiryMonthBoundaries() {
    return Stream.of(Arguments.of(0, 400), Arguments.of(1, 201),
        Arguments.of(12, 201), Arguments.of(13, 400));
  }

  private static Stream<String> invalidExpiryYears() {
    return Stream.of("999", "10000", "2147483648", "-2147483649");
  }

  private static Stream<String> invalidAmountTokens() {
    return Stream.of("1050.99", "1050.0", "1e3", "\"1050\"",
        "2147483648", "-2147483649");
  }

  private static Stream<String> validAmountBoundaries() {
    return Stream.of("-2147483648", "2147483647");
  }
}
