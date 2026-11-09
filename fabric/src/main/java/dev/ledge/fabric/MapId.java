package dev.ledge.fabric;

/** Map id wrapper: before 1.20.5 a map id is a plain int stored in the item's "map" NBT tag. */
final class MapId {
    private final int id;

    MapId(int id) { this.id = id; }

    int id() { return id; }

    @Override public boolean equals(Object other) { return other instanceof MapId && ((MapId) other).id == id; }

    @Override public int hashCode() { return id; }
}
