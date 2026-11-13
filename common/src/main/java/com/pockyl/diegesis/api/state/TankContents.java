package com.pockyl.diegesis.api.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a tank holds: one liquid or a mixture of several, in millibuckets. A liquid is an id - a registered fluid such as
 * {@code minecraft:water}, or any id a mod gives its own liquid (a reagent) and describes on the client with
 * {@code DiegesisClient.registerLiquid}. Immutable.
 */
public final class TankContents {
    public static final TankContents EMPTY = new TankContents(List.of());

    private final List<Portion> portions;
    private final int total;

    private TankContents(List<Portion> portions) {
        this.portions = Collections.unmodifiableList(portions);
        this.total = portions.stream().mapToInt(Portion::amount).sum();
    }

    public static TankContents of(Identifier liquid, int amount) {
        return amount <= 0 ? EMPTY : new TankContents(List.of(new Portion(liquid, amount)));
    }

    public List<Portion> portions() {
        return portions;
    }

    /** Millibuckets in total. */
    public int amount() {
        return total;
    }

    public boolean isEmpty() {
        return total <= 0;
    }

    /** The liquid making up most of it, or null when empty. */
    public Identifier main() {
        return portions.stream().max(java.util.Comparator.comparingInt(Portion::amount)).map(Portion::liquid).orElse(null);
    }

    /** Millibuckets of one liquid. */
    public int amountOf(Identifier liquid) {
        return portions.stream().filter(portion -> portion.liquid.equals(liquid)).mapToInt(Portion::amount).sum();
    }

    /** These contents with {@code other} poured in (same liquids merge). */
    public TankContents plus(TankContents other) {
        List<Portion> merged = new ArrayList<>(portions);
        for (Portion portion : other.portions) {
            boolean found = false;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).liquid.equals(portion.liquid)) {
                    merged.set(i, new Portion(portion.liquid, merged.get(i).amount + portion.amount));
                    found = true;
                    break;
                }
            }
            if (!found) {
                merged.add(portion);
            }
        }
        return new TankContents(merged);
    }

    /**
     * The part that leaves when {@code amount} millibuckets are poured out: every liquid in proportion, as from a mixed
     * flask. {@link #minus} gives what stays.
     */
    public TankContents take(int amount) {
        if (amount <= 0 || isEmpty()) {
            return EMPTY;
        }
        if (amount >= total) {
            return this;
        }
        List<Portion> taken = new ArrayList<>();
        int left = amount;
        for (int i = 0; i < portions.size(); i++) {
            Portion portion = portions.get(i);
            int share = i == portions.size() - 1 ? left : Math.min(left, Math.round((float) portion.amount * amount / total));
            share = Math.min(share, portion.amount);
            if (share > 0) {
                taken.add(new Portion(portion.liquid, share));
                left -= share;
            }
        }
        return new TankContents(taken);
    }

    /** These contents without {@code part} (which must have been taken from them). */
    public TankContents minus(TankContents part) {
        List<Portion> left = new ArrayList<>();
        for (Portion portion : portions) {
            int rest = portion.amount - part.amountOf(portion.liquid);
            if (rest > 0) {
                left.add(new Portion(portion.liquid, rest));
            }
        }
        return new TankContents(left);
    }

    public CompoundTag save() {
        ListTag list = new ListTag();
        for (Portion portion : portions) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Liquid", portion.liquid.toString());
            entry.putInt("Amount", portion.amount);
            list.add(entry);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Portions", list);
        return tag;
    }

    public static TankContents load(CompoundTag tag) {
        List<Portion> portions = new ArrayList<>();
        ListTag list = tag.getListOrEmpty("Portions");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            Identifier liquid = Identifier.tryParse(entry.getStringOr("Liquid", ""));
            int amount = entry.getIntOr("Amount", 0);
            if (liquid != null && amount > 0) {
                portions.add(new Portion(liquid, amount));
            }
        }
        return new TankContents(portions);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(portions.size());
        for (Portion portion : portions) {
            buf.writeIdentifier(portion.liquid);
            buf.writeVarInt(portion.amount);
        }
    }

    public static TankContents read(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 64);
        List<Portion> portions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            portions.add(new Portion(buf.readIdentifier(), buf.readVarInt()));
        }
        return new TankContents(portions);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TankContents contents && contents.portions.equals(portions);
    }

    @Override
    public int hashCode() {
        return portions.hashCode();
    }

    @Override
    public String toString() {
        return portions.toString();
    }

    /** An amount of one liquid. */
    public record Portion(Identifier liquid, int amount) {
    }
}
