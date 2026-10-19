package net.markandrew.kiosk.music;

import java.util.*;

/** Offline discovery cases: no name-only matching and no ambiguous auto selection. */
public final class DiscoveryPolicyTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Discovery check " + checks); }
    static void rejects(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected refusal " + checks); }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> m = new HashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i+1]); return m;
    }
    public static void main(String[] args) {
        List<DiscoveryPolicy.Choice> choices = DiscoveryPolicy.addons(map("addons", Arrays.asList(
            map("slug", "a123_music_assistant", "name", "Music Assistant"),
            map("slug", "a123_music_assistant_beta", "name", "Music Assistant Beta"),
            map("slug", "a123_other", "name", "Unrelated app"),
            map("slug", "../bad", "name", "Music Assistant"))));
        check(choices.size() == 2); check(choices.get(0).id.equals("a123_music_assistant"));
        check(DiscoveryPolicy.addons(map("addons", Collections.emptyList())).isEmpty());
        rejects(() -> DiscoveryPolicy.addons(map("unexpected", true)));
        rejects(() -> DiscoveryPolicy.addons(null));
        List<DiscoveryPolicy.Choice> nodes = DiscoveryPolicy.nodes(map("esphome", map(
            "bedroom_media_player_list", map("name", "Bedroom player list"), "bedroom_media_player_set", map(),
            "unpaired_media_player_list", map(), "../bad_media_player_list", map(), "../bad_media_player_set", map())));
        check(nodes.size() == 1); check(nodes.get(0).id.equals("bedroom"));
        List<DiscoveryPolicy.Choice> single = Collections.singletonList(choices.get(0));
        check(!DiscoveryPolicy.showInstance(single, ""));
        check(!DiscoveryPolicy.showInstance(single, "a123_music_assistant"));
        check(DiscoveryPolicy.showInstance(single, "another_music_assistant"));
        check(DiscoveryPolicy.showInstance(choices, ""));
        check(DiscoveryPolicy.showInstance(Collections.emptyList(), ""));
        check(choices.get(0).label.equals("Music Assistant"));
        List<DiscoveryPolicy.Choice> friendly = DiscoveryPolicy.nodes(map("esphome", map(
            "ks_a_media_player_list", map("name", "Bedroom 1 Media player list"), "ks_a_media_player_set", map())));
        check(friendly.get(0).id.equals("ks_a")); check(friendly.get(0).label.equals("Bedroom 1"));
        List<DiscoveryPolicy.Choice> duplicates = DiscoveryPolicy.nodes(map("esphome", map(
            "ks_a_media_player_list", map("name", "Bedroom Media player list"), "ks_a_media_player_set", map(),
            "ks_b_media_player_list", map("name", "Bedroom Media player list"), "ks_b_media_player_set", map(),
            "ks_c_media_player_list", map("name", "Bedroom Media player list"), "ks_c_media_player_set", map())));
        check(duplicates.size() == 3);
        check(new HashSet<>(Arrays.asList(duplicates.get(0).label, duplicates.get(1).label, duplicates.get(2).label)).size() == 3);
        check(DiscoveryPolicy.nodes(map()).isEmpty());
        rejects(() -> DiscoveryPolicy.nodes(null));
        PlayerPolicy.Snapshot s = new PlayerPolicy.Snapshot();
        s.players.put("local", new PlayerPolicy.Player("local", "Bedroom", "player", true, true, "", "", "", "idle"));
        s.players.put("homepod", new PlayerPolicy.Player("homepod", "Bedroom", "player", true, true, "", "", "", "idle"));
        s.providers.put("local", "sendspin"); s.providers.put("homepod", "airplay");
        s.addresses.put("local", "192.0.2.10"); s.addresses.put("homepod", "192.0.2.11");
        check(DiscoveryPolicy.uniqueLocal(s, "192.0.2.10").equals("local"));
        check(DiscoveryPolicy.uniqueLocal(s, "192.0.2.11").isEmpty());
        check(DiscoveryPolicy.uniqueLocal(s, "").isEmpty());
        check(DiscoveryPolicy.uniqueLocal(s, null).isEmpty());
        check(DiscoveryPolicy.uniqueLocal(s, "192.0.2.20").isEmpty());
        s.providers.put("local", "sendspin--instance"); check(DiscoveryPolicy.uniqueLocal(s, "192.0.2.10").equals("local"));
        s.providers.put("local", "sendspin_fake"); check(DiscoveryPolicy.uniqueLocal(s, "192.0.2.10").isEmpty());
        s.providers.put("local", "sendspin"); s.providers.put("homepod", "sendspin"); s.addresses.put("homepod", "192.0.2.10");
        check(DiscoveryPolicy.uniqueLocal(s, "192.0.2.10").isEmpty());
        java.net.URI origin = IngressPolicy.origin("https://ha.example/");
        check(DiscoveryPolicy.signedServices(origin, "/api/services?authSig=test.signature").getHost().equals("ha.example"));
        for (String bad : Arrays.asList("https://other.example/api/services?authSig=test", "//other.example/api/services?authSig=test",
            "/api/states?authSig=test", "/api/services", "/api/services?authSig=test&other=x",
            "/api/services?authSig=test#fragment", "/api/../api/services?authSig=test", "/api/services?authSig=",
            "/api/services?authSig=%0D%0A", "https://ha.example/api/services?authSig=test"))
            rejects(() -> DiscoveryPolicy.signedServices(origin, bad));
        rejects(() -> DiscoveryPolicy.signedServices(java.net.URI.create("http://ha.example/"), "/api/services?authSig=test"));
        check(DiscoveryPolicy.failure("catalogue", map("code", "too_large", "message", "must not display this")).contains("32 KB"));
        check(!DiscoveryPolicy.failure("catalogue", map("code", "too_large", "message", "must not display this")).contains("must not display"));
        check(DiscoveryPolicy.failure("catalogue", map("code", "not_allowed")).contains("not_allowed"));
        check(DiscoveryPolicy.failure("catalogue", map("code", "timeout")).contains("Refresh"));
        check(DiscoveryPolicy.failure("catalogue", map("code", "auth_invalid")).contains("refused"));
        check(DiscoveryPolicy.failure("catalogue", map("code", "https://private.example/")).contains("unknown_error"));
        List<DiscoveryPolicy.Choice> servers=DiscoveryPolicy.serverNames(Arrays.asList(new DiscoveryPolicy.Choice("one","Music Assistant · one"),new DiscoveryPolicy.Choice("two","Music Assistant · two")));
        check(servers.get(0).id.equals("one")&&servers.get(1).id.equals("two"));check(servers.get(0).label.equals("Music Assistant (1)")&&servers.get(1).label.equals("Music Assistant (2)"));
        check(DiscoveryPolicy.serverNames(Collections.singletonList(new DiscoveryPolicy.Choice("one","Kitchen music · one"))).get(0).label.equals("Kitchen music"));
        System.out.println("PASS: " + checks + " discovery policy checks (no network)");
    }
}
