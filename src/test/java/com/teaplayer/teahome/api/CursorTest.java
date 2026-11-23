package com.teaplayer.teahome.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void roundTrip() {
        Cursor cursor = new Cursor("tv", 42L, 7L);
        Cursor decoded = Cursor.decode(cursor.encode());
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void roundTripAllCategories() {
        Cursor cursor = new Cursor("", 42L, 7L);
        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("bad_cursor");
    }

    @Test
    void nullAndBlankMeanFirstPage() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode(" ")).isNull();
    }
}
