package events.equaliser.java.util;

import java.util.Objects;

/**
 * An extension of BiConsumer.
 * @param <T> The type of the first parameter.
 * @param <U> The type of the second parameter.
 * @param <V> The type of the third parameter.
 */
@FunctionalInterface
public interface TriConsumer<T, U, V> {

    void accept(T t, U u, V v);

    default TriConsumer<T, U, V> andThen(TriConsumer<? super T, ? super U, ? super V> after) {
        Objects.requireNonNull(after);
        return (a, b, c) -> {
            accept(a, b, c);
            after.accept(a, b, c);
        };
    }
}