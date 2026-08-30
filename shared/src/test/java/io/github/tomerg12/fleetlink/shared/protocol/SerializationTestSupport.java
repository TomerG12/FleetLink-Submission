package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Provides Java serialization round trips for protocol contract tests.
 */
final class SerializationTestSupport {

    /**
     * Prevents construction because serialization support is stateless.
     */
    private SerializationTestSupport() {
    }

    /**
     * Serializes and deserializes one transport value using standard Java serialization.
     *
     * @param value the serializable value to round trip
     * @param expectedType the runtime type expected after deserialization
     * @param <T> the transport value type
     * @return the deserialized copy
     * @throws IOException if serialization data cannot be written or read
     * @throws ClassNotFoundException if the serialized type cannot be resolved
     */
    static <T extends Serializable> T roundTrip(T value, Class<T> expectedType)
            throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return expectedType.cast(input.readObject());
        }
    }
}
