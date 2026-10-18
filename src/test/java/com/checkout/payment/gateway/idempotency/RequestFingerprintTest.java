package com.checkout.payment.gateway.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  @Test
  void configuredSecretMustProvideAtLeast256BitsOfMaterial() {
    assertThrows(IllegalArgumentException.class,
        () -> new RequestFingerprint("too-short"));
    new RequestFingerprint("0123456789abcdef0123456789abcdef");
  }

  @Test
  void cvvIsExcludedButInstrumentIdentityIsIncluded() {
    RequestFingerprint fingerprint = new RequestFingerprint(
        "0123456789abcdef0123456789abcdef");
    PostPaymentRequest original = request("2222405343240001", "007");
    PostPaymentRequest changedCvv = request("2222405343240001", "999");
    PostPaymentRequest changedPan = request("2222405343240003", "007");

    assertEquals(fingerprint.create(original), fingerprint.create(changedCvv));
    assertNotEquals(fingerprint.create(original), fingerprint.create(changedPan));
  }

  private PostPaymentRequest request(String pan, String cvv) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber(pan);
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency("GBP");
    request.setAmount(1050);
    request.setCvv(cvv);
    return request;
  }
}
