package com.pockyl.diegesis.api.station;

/** Who owns a station's state. */
public enum Authority {
    /**
     * The client alone: works on any server, even one without the library, but other players do not see what the player
     * does, and nothing can move items between the station and the player's inventory. For visual stations, previews,
     * toys and animations.
     */
    CLIENT,
    /**
     * The server: every action is checked and applied there, items move safely, other players see the station change
     * and watch the objects being carried. Needs the library on the server; without it the block works as it does in
     * vanilla.
     */
    SERVER
}
