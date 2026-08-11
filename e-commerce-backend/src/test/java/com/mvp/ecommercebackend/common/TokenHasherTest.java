package com.mvp.ecommercebackend.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenHasherTest {

    @Test
    void matchesTheKnownSha256VectorForAbc() {
        assertThat(TokenHasher.sha256Hex("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void producesSixtyFourLowercaseHexCharacters() {
        String hash = TokenHasher.sha256Hex("some-refresh-token-value");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministic() {
        assertThat(TokenHasher.sha256Hex("same")).isEqualTo(TokenHasher.sha256Hex("same"));
    }

    @Test
    void producesDifferentHashesForDifferentInputs() {
        assertThat(TokenHasher.sha256Hex("token-a")).isNotEqualTo(TokenHasher.sha256Hex("token-b"));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> TokenHasher.sha256Hex(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
