package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.SocketTimeoutException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class BankClient {

  private static final Logger LOG = LoggerFactory.getLogger(BankClient.class);
  private final RestTemplate restTemplate;
  private final String paymentsUrl;

  public BankClient(RestTemplate restTemplate, @Value("${bank.base-url}") String bankBaseUrl) {
    this.restTemplate = restTemplate;
    this.paymentsUrl = bankBaseUrl.replaceAll("/+$", "") + "/payments";
  }

  public BankOutcome process(PostPaymentRequest request) {
    BankRequest bankRequest = new BankRequest(
        request.getCardNumber(),
        String.format(Locale.ROOT, "%02d/%04d", request.getExpiryMonth(), request.getExpiryYear()),
        request.getCurrency(), request.getAmount(), request.getCvv());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    long started = System.nanoTime();
    try {
      ResponseEntity<JsonNode> response = restTemplate.exchange(
          paymentsUrl, HttpMethod.POST, new HttpEntity<>(bankRequest, headers), JsonNode.class);
      if (response.getStatusCode() != HttpStatus.OK) {
        throw invalidResponse();
      }
      JsonNode body = response.getBody();
      JsonNode authorized = body == null ? null : body.get("authorized");
      if (authorized == null || !authorized.isBoolean()) {
        throw invalidResponse();
      }
      BankOutcome outcome = authorized.booleanValue() ? BankOutcome.AUTHORIZED : BankOutcome.DECLINED;
      LOG.info("Bank call completed outcome={} duration_ms={}", outcome, elapsedMillis(started));
      return outcome;
    } catch (GatewayException ex) {
      LOG.warn("Bank call failed category={} duration_ms={}", ex.getCode(), elapsedMillis(started));
      throw ex;
    } catch (HttpStatusCodeException ex) {
      GatewayException mapped = ex.getStatusCode().value() == 503 ? unavailable() : invalidResponse();
      LOG.warn("Bank call failed category={} duration_ms={}", mapped.getCode(), elapsedMillis(started));
      throw mapped;
    } catch (ResourceAccessException ex) {
      GatewayException mapped = hasTimeoutCause(ex) ? timeout() : unavailable();
      LOG.warn("Bank call failed category={} duration_ms={}", mapped.getCode(), elapsedMillis(started));
      throw mapped;
    } catch (RestClientException ex) {
      GatewayException mapped = invalidResponse();
      LOG.warn("Bank call failed category={} duration_ms={}", mapped.getCode(), elapsedMillis(started));
      throw mapped;
    }
  }

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private static boolean hasTimeoutCause(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof SocketTimeoutException
          || current.getClass().getSimpleName().contains("Timeout")) {
        return true;
      }
    }
    return false;
  }

  private static GatewayException unavailable() {
    return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE", "Bank is unavailable");
  }

  private static GatewayException timeout() {
    return new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "BANK_TIMEOUT", "Bank request timed out");
  }

  private static GatewayException invalidResponse() {
    return new GatewayException(HttpStatus.BAD_GATEWAY, "BANK_RESPONSE_INVALID", "Bank returned an invalid response");
  }
}
