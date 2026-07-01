package com.paymentgateway.apigateway.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHasherTest {

    @Test
    void identicalRequestsProduceTheSameHash() {
        var a = new PaymentRequest("acc-1", "acc-2", "1000", "JPY");
        var b = new PaymentRequest("acc-1", "acc-2", "1000", "JPY");
        assertThat(RequestHasher.hash(a)).isEqualTo(RequestHasher.hash(b));
    }

    @Test
    void differentAmountProducesADifferentHash() {
        var a = new PaymentRequest("acc-1", "acc-2", "1000", "JPY");
        var b = new PaymentRequest("acc-1", "acc-2", "2000", "JPY");
        assertThat(RequestHasher.hash(a)).isNotEqualTo(RequestHasher.hash(b));
    }

    @Test
    void fieldBoundaryShiftProducesADifferentHash() {
        // "acc-1"+"2acc" vs "acc-12"+"acc" - guards against naive string concatenation
        // without a separator producing the same hash for shifted field boundaries.
        var a = new PaymentRequest("acc-1", "2acc", "1000", "JPY");
        var b = new PaymentRequest("acc-12", "acc", "1000", "JPY");
        assertThat(RequestHasher.hash(a)).isNotEqualTo(RequestHasher.hash(b));
    }
}
