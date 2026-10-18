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

  private final byte[] secret;

  public RequestFingerprint(@Value("${payment.fingerprint-secret:}") String configuredSecret) {
    if (configuredSecret == null || configuredSecret.isBlank()) {
      this.secret = new byte[32];
      new SecureRandom().nextBytes(this.secret);
    } else {
      this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
    }
  }

  public String create(PostPaymentRequest request) {
    // CVV is intentionally excluded: persisting even a derived CVV value is inappropriate.
    String canonical = "AUTHORIZE\n" + request.getAmount() + "\n" + request.getCurrency()
        + "\n" + request.getExpiryMonth() + "\n" + request.getExpiryYear()
        + "\n" + request.getCardNumber();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
    }
  }
}
