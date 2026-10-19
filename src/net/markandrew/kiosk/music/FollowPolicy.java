package net.markandrew.kiosk.music;

/** Native source identity is not interchangeable with the local audio device. */
final class FollowPolicy {
    static String source(KioskPlayerBridge.Pick pick) {
        switch (pick.source) {
            case "device": return pick.id.isEmpty() ? "This device · Sendspin" : "This device · Local Media Session";
            case "music_assistant": return "Music Assistant";
            case "home_assistant": return "Home Assistant";
            case "sonos": return "Sonos directly";
            default: return "Unsupported source";
        }
    }
    static PlayerPolicy.Player resolve(KioskPlayerBridge.Pick pick, String local, PlayerPolicy.Snapshot snapshot) {
        if (pick == null) throw new IllegalArgumentException("Kiosk player has not been identified. Open Setup for connection status.");
        if (snapshot == null) throw new IllegalArgumentException("Music Assistant players are unavailable. Refresh Setup.");
        String id;
        switch (pick.source) {
            case "device":
                if (!pick.id.isEmpty()) throw new IllegalArgumentException("Kiosk follows a Local Media Session. That Android app cannot accept Music Assistant library items. Choose Sendspin or an MA source in Kiosk's Media Player settings.");
                if (local.isEmpty()) throw new IllegalArgumentException("This kiosk's Sendspin player could not be uniquely matched in Music Assistant. Enable local Sendspin in Kiosk, or check Advanced mapping.");
                if (!DiscoveryPolicy.sendspin(snapshot, local))
                    throw new IllegalArgumentException("The local mapping is not a verified Sendspin player. Check Advanced; no other speaker was selected.");
                id = local; break;
            case "music_assistant": id = pick.id; break;
            case "home_assistant": case "sonos":
                throw new IllegalArgumentException("Kiosk follows " + source(pick) + ". Its mapping to a Music Assistant player is not verified. Select that speaker through Kiosk's Music Assistant source, or use a fixed MA player override.");
            default: throw new IllegalArgumentException("Kiosk's selected source is unsupported. No fallback player was selected.");
        }
        // Native and automatically discovered identities must be exact IDs, never display-name guesses.
        PlayerPolicy.Player player = snapshot.players.get(id);
        if (player == null || !player.audio()) throw new IllegalArgumentException("Kiosk's player is missing from this MA instance. No fallback was selected.");
        if (!player.available) throw new IllegalArgumentException("Kiosk's selected player is offline. No fallback was selected.");
        return player;
    }
    static String summary(KioskPlayerBridge.State kiosk, String local, PlayerPolicy.Snapshot snapshot) {
        if (kiosk == null) return "Source: not identified\nPlayer: not identified\nConnection: not verified";
        KioskPlayerBridge.Pick pick = kiosk.current;
        String player = pick.name;
        try {
            PlayerPolicy.Player mapped = resolve(pick, local, snapshot);
            return "Source: " + source(pick) + "\nPlayer: " + mapped.name + "\nConnection: ready";
        } catch (IllegalArgumentException error) {
            if (player.isEmpty()) player = "device".equals(pick.source) ? source(pick) : "Not identified";
            return "Source: " + source(pick) + "\nPlayer: " + player + "\nConnection: " + error.getMessage();
        }
    }
}
