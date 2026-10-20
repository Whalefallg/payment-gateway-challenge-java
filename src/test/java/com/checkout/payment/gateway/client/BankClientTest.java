package com.checkout.payment.gateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BankClientTest {

  @Test
  void sendsExactWireContractAndMapsAuthorized() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server.expect(once(), requestTo("http://bank.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
        .andExpect(content().json("""
            {"card_number":"2222405343240001","expiry_date":"01/2030",\
            "currency":"GBP","amount":1050,"cvv":"007"}
            """, true))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
            .body("{\"authorized\":true,\"authorization_code\":\"anything\"}"));

    assertEquals(BankOutcome.AUTHORIZED,
        new BankClient(restTemplate, "http://bank.test/").authorize(request("2222405343240001")));
    server.verify();
  }

  @Test
  void mapsExplicitFalseToDeclined() {
    assertEquals(BankOutcome.DECLINED, invokeWithResponse(200, "{\"authorized\":false}"));
  }

  @Test
  void rejectsAmbiguousAndUnexpectedResponses() {
    for (Response response : new Response[] {
        new Response(200, "{}"), new Response(200, "{\"authorized\":null}"),
        new Response(200, "{\"authorized\":\"false\"}"),
        new Response(200, "{\"authorized\":0}"), new Response(200, ""),
        new Response(200, "not-json"), new Response(201, "{\"authorized\":true}"),
        new Response(204, "") , new Response(400, "{\"unsafe\":\"pan\"}")}) {
      GatewayException ex = assertThrows(GatewayException.class,
          () -> invokeWithResponse(response.status(), response.body()));
      assertEquals("BANK_RESPONSE_INVALID", ex.getErrorCode());
      assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
    }
  }

  @Test
  void maps503ToUnavailable() {
    GatewayException ex = assertThrows(GatewayException.class,
        () -> invokeWithResponse(503, "{}"));
    assertEquals("BANK_UNAVAILABLE", ex.getErrorCode());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
  }

  @Test
  void mapsConnectionRefusedToUnavailable() throws Exception {
    int unusedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }
    RestTemplate restTemplate = new RestTemplateBuilder()
        .setConnectTimeout(Duration.ofMillis(200)).setReadTimeout(Duration.ofMillis(200)).build();
    GatewayException ex = assertThrows(GatewayException.class,
        () -> new BankClient(restTemplate, "http://127.0.0.1:" + unusedPort)
            .authorize(request("2222405343240001")));
    assertEquals("BANK_UNAVAILABLE", ex.getErrorCode());
  }

  @Test
  void delayedResponseMapsToTimeoutAndSendsExactlyOneRequest() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/payments", exchange -> {
      calls.incrementAndGet();
      try {
        Thread.sleep(350);
        byte[] body = "{\"authorized\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    try {
      RestTemplate restTemplate = new RestTemplateBuilder()
          .setConnectTimeout(Duration.ofMillis(200)).setReadTimeout(Duration.ofMillis(100)).build();
      GatewayException ex = assertThrows(GatewayException.class,
          () -> new BankClient(restTemplate, "http://127.0.0.1:" + server.getAddress().getPort())
              .authorize(request("2222405343240001")));
      assertEquals("BANK_TIMEOUT", ex.getErrorCode());
      assertEquals(1, calls.get());
    } finally {
      server.stop(0);
    }
  }

  private BankOutcome invokeWithResponse(int status, String body) {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server.expect(once(), requestTo("http://bank.test/payments"))
        .andRespond(withRawStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    try {
      return new BankClient(restTemplate, "http://bank.test")
          .authorize(request("2222405343240001"));
    } finally {
      server.verify();
    }
  }

  private PostPaymentRequest request(String card) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber(card);
    request.setExpiryMonth(1);
    request.setExpiryYear(2030);
    request.setCurrency("GBP");
    request.setAmount(1050);
    request.setCvv("007");
    return request;
  }

  private record Response(int status, String body) {}
}
