/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestroyableRawSecretKeyTest {

    @Test
    void destroy() {
        byte[] bytes = { 0, 1, 2 };
        var dk = DestroyableRawSecretKey.byOwnershipTransfer("foo", bytes);
        assertThat(dk.getFormat()).isEqualTo("RAW");
        assertThat(dk.getAlgorithm()).isEqualTo("foo");
        var encoded = dk.getEncoded();
        assertThat(dk.isDestroyed()).isFalse();
        dk.destroy();
        assertThat(dk.isDestroyed()).isTrue();
        assertThat(bytes).isEqualTo(new byte[]{ 0, 0, 0 });
        assertThatThrownBy(dk::getEncoded).isExactlyInstanceOf(IllegalStateException.class);
        dk.destroy(); // should be idempotent
        assertThat(encoded).isEqualTo(new byte[]{ 0, 1, 2 });
    }

    @Test
    void same() {
        byte[] bytes1 = { 0, 1, 2 };
        var dk1 = DestroyableRawSecretKey.byOwnershipTransfer("foo", bytes1);
        var dk2 = DestroyableRawSecretKey.byClone("foo", bytes1);
        byte[] bytes3 = { 9, 8, 7 };
        var dk3 = DestroyableRawSecretKey.byOwnershipTransfer("foo", bytes3);

        assertThat(DestroyableRawSecretKey.same(dk1, dk1)).isTrue();
        assertThat(DestroyableRawSecretKey.same(dk1, dk2)).isTrue();
        assertThat(DestroyableRawSecretKey.same(dk1, dk3)).isFalse();

        assertThat(DestroyableRawSecretKey.same(dk2, dk2)).isTrue();
        assertThat(DestroyableRawSecretKey.same(dk2, dk1)).isTrue();
        assertThat(DestroyableRawSecretKey.same(dk2, dk3)).isFalse();

        assertThat(DestroyableRawSecretKey.same(dk3, dk1)).isFalse();
        assertThat(DestroyableRawSecretKey.same(dk3, dk2)).isFalse();
        assertThat(DestroyableRawSecretKey.same(dk3, dk3)).isTrue();

        dk1.destroy();
        assertThatThrownBy(() -> DestroyableRawSecretKey.same(dk1, dk3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DestroyableRawSecretKey.same(dk3, dk1)).isInstanceOf(IllegalStateException.class);

    }

    @Test
    void toDestroyableKey() {
        byte[] bytes1 = { 0, 1, 2 };
        var dk1 = DestroyableRawSecretKey.byClone("foo", bytes1);
        var dk2 = DestroyableRawSecretKey.toDestroyableKey(dk1);
        assertThat(dk1.isDestroyed()).isTrue();
        assertThat(dk2.isDestroyed()).isFalse();
        assertThat(dk2.getEncoded()).isEqualTo(bytes1);
        assertThat(dk2.getAlgorithm()).isEqualTo("foo");
    }

}