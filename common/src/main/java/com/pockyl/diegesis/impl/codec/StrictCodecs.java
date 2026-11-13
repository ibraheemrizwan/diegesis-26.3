package com.pockyl.diegesis.impl.codec;

import com.mojang.datafixers.kinds.App;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Codec helpers for scene files. DFU's {@code optionalFieldOf} silently drops a field whose value does not parse, which
 * hides typos from scene authors; the fields here report them instead, and {@link #record} rejects fields an object
 * does not have (a misspelt optional field would otherwise be dropped without a word).
 */
public final class StrictCodecs {
    /** A position or size in Blockbench pixels, {@code [x, y, z]}. */
    public static final Codec<Vector3f> VEC3 = ExtraCodecs.VECTOR3F.xmap(Vector3f::new, value -> value);
    /** Two numbers, {@code [min, max]}. */
    public static final Codec<float[]> RANGE = Codec.FLOAT.listOf().comapFlatMap(list -> list.size() == 2
            ? DataResult.success(new float[] {list.get(0), list.get(1)})
            : DataResult.error(() -> "Expected [min, max], got " + list), range -> List.of(range[0], range[1]));

    private StrictCodecs() {
    }

    /**
     * Like {@link RecordCodecBuilder#create}, but an object with a field the record does not know is an error that names
     * the field and the closest known one. Keys starting with {@code $} ({@code "$schema"}, {@code "$comment"}) are
     * allowed anywhere, for editors and notes.
     */
    public static <O> Codec<O> record(Function<RecordCodecBuilder.Instance<O>, ? extends App<RecordCodecBuilder.Mu<O>, O>> builder) {
        return closed(RecordCodecBuilder.mapCodec(builder)).codec();
    }

    /** {@code codec}, failing on fields it does not read (see {@link #record}). */
    public static <A> MapCodec<A> closed(MapCodec<A> codec) {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return codec.keys(ops);
            }

            @Override
            public <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input) {
                DataResult<A> result = codec.decode(ops, input);
                List<String> known = codec.keys(ops).map(key -> ops.getStringValue(key).result().orElse("")).toList();
                List<String> unknown = new ArrayList<>();
                input.entries().forEach(entry -> ops.getStringValue(entry.getFirst()).result()
                        .filter(key -> !key.startsWith("$") && !known.contains(key))
                        .ifPresent(unknown::add));
                if (unknown.isEmpty()) {
                    return result;
                }
                String message = describeUnknown(unknown, known);
                return result.flatMap(value -> DataResult.error(() -> message, value));
            }

            @Override
            public <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                return codec.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return "Closed[" + codec + "]";
            }
        };
    }

    private static String describeUnknown(List<String> unknown, List<String> known) {
        StringBuilder message = new StringBuilder();
        for (String key : unknown) {
            if (!message.isEmpty()) {
                message.append("; ");
            }
            message.append("Unknown field \"").append(key).append('"');
            String closest = null;
            int best = Integer.MAX_VALUE;
            for (String candidate : known) {
                int distance = distance(key, candidate);
                if (distance < best) {
                    best = distance;
                    closest = candidate;
                }
            }
            if (closest != null && best <= Math.max(2, key.length() / 3)) {
                message.append(" (did you mean \"").append(closest).append("\"?)");
            }
        }
        return message.append(", this object has: ").append(String.join(", ", known)).toString();
    }

    /** Edit distance between two field names. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** An optional field that fails the whole object when it is present but invalid. */
    public static <A> MapCodec<Optional<A>> optional(Codec<A> codec, String name) {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.of(ops.createString(name));
            }

            @Override
            public <T> DataResult<Optional<A>> decode(DynamicOps<T> ops, MapLike<T> input) {
                T value = input.get(name);
                if (value == null) {
                    return DataResult.success(Optional.empty());
                }
                return codec.parse(ops, value).map(Optional::of).mapError(error -> "\"" + name + "\": " + error);
            }

            @Override
            public <T> RecordBuilder<T> encode(Optional<A> input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                return input.map(value -> prefix.add(name, codec.encodeStart(ops, value))).orElse(prefix);
            }

            @Override
            public String toString() {
                return "StrictOptional[" + name + " " + codec + "]";
            }
        };
    }

    /** An optional field with a default value; present but invalid values are errors. */
    public static <A> MapCodec<A> optional(Codec<A> codec, String name, A defaultValue) {
        return optional(codec, name).xmap(value -> value.orElse(defaultValue),
                value -> value.equals(defaultValue) ? Optional.empty() : Optional.of(value));
    }
}
