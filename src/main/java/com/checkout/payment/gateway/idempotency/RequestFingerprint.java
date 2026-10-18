package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Produces a keyed, stable identity without retaining PAN or CVV. */
@Component
public class RequestFingerprint {

  private static final int MINIMUM_SECRET_BYTES = 32;
  private final byte[] secret;

  public RequestFingerprint(@Value("${payment.fingerprint-secret:}") String configuredSecret) {
    if (configuredSecret == null || configuredSecret.isBlank()) {
      this.secret = new byte[32];
      new SecureRandom().nextBytes(this.secret);
    } else {
      byte[] configured = configuredSecret.getBytes(StandardCharsets.UTF_8);
      if (configured.length < MINIMUM_SECRET_BYTES) {
        throw new IllegalArgumentException(
            "payment.fingerprint-secret must contain at least 32 UTF-8 bytes");
      }
      this.secret = configured.clone();
    }
  }

  public String create(PostPaymentRequest request) {
    // CVV is intentionally excluded: persisting even a derived CVV value is inappropriate.
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      update(mac, "AUTHORIZE");
      update(mac, request.getAmount().toString());
      update(mac, request.getCurrency());
      update(mac, request.getExpiryMonth().toString());
      update(mac, request.getExpiryYear().toString());
      update(mac, request.getCardNumber());
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          mac.doFinal());
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
    }
  }

  private static void update(Mac mac, String value) {
    mac.update(value.getBytes(StandardCharsets.UTF_8));
    mac.update((byte) '\n');
  }
}
