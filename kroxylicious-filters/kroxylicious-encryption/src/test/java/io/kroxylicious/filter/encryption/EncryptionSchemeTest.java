/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import io.kroxylicious.kms.service.KekId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionSchemeTest {

    @Test
    void shouldRejectInvalidConstructorArgs() {
        EnumSet<RecordField> nonEmpty = EnumSet.of(RecordField.RECORD_VALUE);
        var empty = EnumSet.noneOf(RecordField.class);
        assertThrows(NullPointerException.class, () -> new EncryptionScheme(null, nonEmpty));
        Object kekId = new Object();

        final MyKekId myKekId = new MyKekId(kekId);

        assertThrows(NullPointerException.class, () -> new EncryptionScheme(myKekId, null));
        assertThrows(IllegalArgumentException.class, () -> new EncryptionScheme(myKekId, empty));
    }

    @Test
    void shouldAcceptValidConstructorArgs() {
        EnumSet<RecordField> nonEmpty = EnumSet.of(RecordField.RECORD_VALUE);
        Object kekId = new Object();
        var es = new EncryptionScheme(new MyKekId(kekId), nonEmpty);
        assertEquals(new MyKekId(kekId), es.kekId());
        assertEquals(nonEmpty, es.recordFields());
    }

    private record MyKekId(Object kekId) implements KekId {
        @SuppressWarnings("unchecked")
        @Override
        public <K> K getId(Class<K> keyType) {
            return (K) kekId;
        }
    }
}