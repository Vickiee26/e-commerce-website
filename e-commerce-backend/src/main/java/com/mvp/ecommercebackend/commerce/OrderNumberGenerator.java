package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.commerce.repository.OrderRepository;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates the customer-facing order number.
 *
 * <p>Random, not a sequence. The number appears in emails and support conversations, so a
 * sequential one would tell anybody who placed two orders how many the shop takes in between, and
 * would let a caller walk their neighbours' order numbers one at a time.
 *
 * <p>The alphabet is Crockford base32 — digits and uppercase letters with {@code I}, {@code L},
 * {@code O} and {@code U} removed, so nothing is misread over the phone and no word is spelled by
 * accident. Twelve characters is 60 bits.
 */
@Component
public class OrderNumberGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final String PREFIX = "ORD-";
    private static final int LENGTH = 12;
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final OrderRepository orderRepository;

    public OrderNumberGenerator(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * A number no order currently uses.
     *
     * <p>A collision in 60 bits is not something to plan around, but the unique constraint would
     * turn one into a 500, so it is cheaper to look before writing. Give up after a handful of
     * attempts rather than loop: at that point the database is telling us something else is wrong.
     */
    public String next() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = candidate();
            if (!orderRepository.existsByOrderNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate an unused order number in " + MAX_ATTEMPTS + " attempts");
    }

    private String candidate() {
        StringBuilder number = new StringBuilder(PREFIX.length() + LENGTH).append(PREFIX);
        for (int index = 0; index < LENGTH; index++) {
            number.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return number.toString();
    }
}
