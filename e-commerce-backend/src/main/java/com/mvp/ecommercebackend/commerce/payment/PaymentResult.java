package com.mvp.ecommercebackend.commerce.payment;

/**
 * The outcome of a charge.
 *
 * @param approved      whether the money was taken
 * @param transactionId the gateway's reference for the charge, null when declined
 * @param declineReason a reason safe to show a customer, null when approved. Anything more specific
 *                      than "declined" is a decision for the gateway, not this application: telling
 *                      a caller exactly why a card failed is how card testing gets easy.
 */
public record PaymentResult(boolean approved, String transactionId, String declineReason) {

    public static PaymentResult approved(String transactionId) {
        return new PaymentResult(true, transactionId, null);
    }

    public static PaymentResult declined(String reason) {
        return new PaymentResult(false, null, reason);
    }
}
