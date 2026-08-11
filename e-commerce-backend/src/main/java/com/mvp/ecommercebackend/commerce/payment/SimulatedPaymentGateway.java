package com.mvp.ecommercebackend.commerce.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Approves everything except one well-known token.
 *
 * <p>This is the whole of "simulated payment": no network call, no card data. The declined token is
 * a fixed string rather than a random failure or an amount-based rule, because a test that needs to
 * exercise the decline path must be able to ask for one, and a customer must never be declined by
 * accident.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    /** Send this as the payment token to get a declined charge. */
    public static final String DECLINED_TOKEN = "tok_declined";

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final SecureRandom random = new SecureRandom();

    @Override
    public PaymentResult charge(String orderNumber, BigDecimal amount, String currency,
                                String paymentMethodToken) {
        if (DECLINED_TOKEN.equals(paymentMethodToken)) {
            // The token is not in this message, and must never be: a rejected instrument is still
            // the customer's payment credential.
            log.info("Simulated charge for {} declined", orderNumber);
            return PaymentResult.declined("The payment method was declined");
        }

        String transactionId = "sim_" + HexFormat.of().formatHex(nextBytes());
        log.info("Simulated charge for {} approved: {} {} as {}",
                orderNumber, amount, currency, transactionId);
        return PaymentResult.approved(transactionId);
    }

    private byte[] nextBytes() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return bytes;
    }
}
