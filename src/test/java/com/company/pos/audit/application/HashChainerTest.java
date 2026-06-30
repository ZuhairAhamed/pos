package com.company.pos.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HashChainerTest {

    private static final Instant TS = Instant.parse("2026-06-30T10:15:30Z");

    @Test
    void hashIsDeterministicForSameInput() {
        String a = HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS");
        String b = HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS");
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void hashChangesWhenAnyFieldChanges() {
        String base = HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS");
        assertThat(HashChainer.chainHash(2, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(HashChainer.chainHash(1, TS, "bob", "LOGIN_FAILED", "alice", "{}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "PREV"))
                .isNotEqualTo(base);
    }

    @Test
    void linkageMeansNextHashDependsOnPrevHash() {
        String first = HashChainer.chainHash(1, TS, "a", "X", "e", "{}", "GENESIS");
        String secondLinked = HashChainer.chainHash(2, TS, "a", "X", "e", "{}", first);
        String secondTampered = HashChainer.chainHash(2, TS, "a", "X", "e", "{}", "GENESIS");
        assertThat(secondLinked).isNotEqualTo(secondTampered);
    }
}
