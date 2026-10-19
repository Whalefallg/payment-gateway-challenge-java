package com.checkout.payment.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.repository.InMemoryPaymentRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PaymentGatewayIntegrationTest {

  private static final AtomicInteger BANK_CALLS = new AtomicInteger();
  private static final HttpServer BANK = startBank();

  @Autowired MockMvc mvc;
  @Autowired InMemoryPaymentRepository repository;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("bank.base-url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
    registry.add("bank.read-timeout", () -> "1s");
  }

  @BeforeEach
  void setUp() {
    BANK_CALLS.set(0);
    repository.clear();
  }

  @AfterAll
  static void stopBank() {
    BANK.stop(0);
  }

  @Test
  void realMvcServiceClientAndRepositoryPathSupportsPostThenGet(CapturedOutput output)
      throws Exception {
    String request = validJson("2222405343240001");
    MvcResult created = mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(request))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.card_number_last_four").value("0001"))
        .andReturn();
    String body = created.getResponse().getContentAsString();
    String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

    mvc.perform(get("/payments/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.status").value("Authorized"));

    assertEqualsOneBankCall();
    assertFalse(body.contains("2222405343240001"));
    assertFalse(body.contains("007"));
    assertSensitiveDataAbsent(output.getOut(), "2222405343240001", "007");
  }

  @Test
  void invalidRawJsonNeverCrossesHttpTransportBoundary() throws Exception {
    mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
            .content(validJson("2222405343240001").replace("\"amount\":1050", "\"amount\":1050.0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"));
    assertTrue(BANK_CALLS.get() == 0);
    assertTrue(repository.size() == 0);
  }

  @Test
  void malformedPaymentPathDoesNotWriteRawSensitiveValueToLogs(CapturedOutput output)
      throws Exception {
    String sensitivePathValue = "4111111111111111-cvv-987";

    mvc.perform(get("/payments/{id}", sensitivePathValue))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_ID"));

    assertFalse(output.getOut().contains(sensitivePathValue));
  }

  private static HttpServer startBank() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/payments", exchange -> {
        BANK_CALLS.incrementAndGet();
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean authorized = request.contains("0001\"");
        byte[] response = ("{\"authorized\":" + authorized + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
      return server;
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private String validJson(String card) {
    YearMonth expiry = YearMonth.now().plusYears(1);
    return """
        {"card_number":"%s","expiry_month":%d,"expiry_year":%d,"currency":"GBP","amount":1050,"cvv":"007"}
        """.formatted(card, expiry.getMonthValue(), expiry.getYear());
  }

  private void assertEqualsOneBankCall() {
    org.junit.jupiter.api.Assertions.assertEquals(1, BANK_CALLS.get());
  }

  private void assertSensitiveDataAbsent(String logs, String pan, String cvv) {
    assertFalse(logs.contains(pan), "logs must not contain the full PAN");
    assertFalse(logs.contains("\"" + cvv + "\""),
        "logs must not contain a quoted CVV value");
    assertFalse(logs.contains("'" + cvv + "'"),
        "logs must not contain a single-quoted CVV value");
    Pattern labelledCvv = Pattern.compile(
        "(?i)\\bcvv\\b.{0,32}([=:]|is|value)?\\s*[\\\"']?" + Pattern.quote(cvv) + "\\b");
    assertFalse(logs.lines().anyMatch(line -> labelledCvv.matcher(line).find()),
        "logs must not contain a labelled CVV value");
  }
}
