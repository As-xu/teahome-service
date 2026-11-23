package com.teaplayer.teahome.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

    @Test
    void hashesKnownValueToLowercaseHex() {
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void isStableAndSixtyFourChars() {
        String first = TokenHasher.sha256Hex("ths_test_token");
        String second = TokenHasher.sha256Hex("ths_test_token");
        assertThat(first).hasSize(64).isEqualTo(second).matches("[0-9a-f]{64}");
    }
}
