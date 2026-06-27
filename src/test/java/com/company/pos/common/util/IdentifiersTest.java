package com.company.pos.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void generatesUniqueVersion4Ids() {
        UUID a = Identifiers.newId();
        UUID b = Identifiers.newId();

        assertThat(a).isNotEqualTo(b);
        assertThat(a.version()).isEqualTo(4);
    }
}
