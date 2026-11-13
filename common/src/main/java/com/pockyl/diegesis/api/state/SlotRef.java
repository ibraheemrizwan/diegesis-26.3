package com.pockyl.diegesis.api.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One slot of a station: a slot group and an index in it. Written {@code "grid[4]"} in scene files, or {@code "fuel"}
 * for index 0.
 */
public record SlotRef(String group, int index) {
    private static final Pattern FORMAT = Pattern.compile("([a-z0-9_]+)(?:\\[(\\d+)])?");

    public static final Codec<SlotRef> CODEC = Codec.STRING.comapFlatMap(SlotRef::parse, SlotRef::toString);

    public SlotRef {
        if (index < 0) {
            throw new IllegalArgumentException("Negative slot index " + index);
        }
    }

    public static SlotRef of(String group) {
        return new SlotRef(group, 0);
    }

    public static SlotRef of(String group, int index) {
        return new SlotRef(group, index);
    }

    public static DataResult<SlotRef> parse(String text) {
        Matcher matcher = FORMAT.matcher(text);
        if (!matcher.matches()) {
            return DataResult.error(() -> "Not a slot (expected name or name[index]): " + text);
        }
        int index = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return DataResult.success(new SlotRef(matcher.group(1), index));
    }

    @Override
    public String toString() {
        return group + "[" + index + "]";
    }
}
