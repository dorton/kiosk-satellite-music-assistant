package net.markandrew.kiosk.music;

import java.util.ArrayList;
import java.util.List;

/** Bounded, allowlisted display only. Never selects players or persists response data. */
final class DiscoveryDiagnostics {
    static String token(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,100}") ? value : "missing/unsupported";
    }
    static List<String> rows(PlayerPolicy.Snapshot snapshot, String ip) {
        List<String> result = new ArrayList<>();
        if (snapshot == null) {
            result.add("No MA snapshot was loaded. This is a read failure, not an empty player list.");
            return result;
        }
        result.add("MA players returned: " + snapshot.players.size()
            + ". Device address: " + (ip == null || ip.isEmpty() ? "unavailable" : "read (not displayed)")
            + " · Format: " + addressFormat(ip)
            + ". Auto mapping still requires one exact address match; no name fallback.");
        int count = 0;
        for (PlayerPolicy.Player player : snapshot.players.values()) {
            if (count++ == 40) {
                result.add("Display limited to 40 players; " + (snapshot.players.size() - 40) + " more not displayed."); break;
            }
            String provider = snapshot.providers.get(player.id), address = snapshot.addresses.get(player.id);
            boolean sendspin = DiscoveryPolicy.sendspin(snapshot, player.id);
            boolean match = ip != null && !ip.isEmpty() && ip.equals(address);
            List<String> excluded = new ArrayList<>();
            if (!player.audio()) excluded.add("non-audio type");
            if (!player.visible) excluded.add("hidden/private");
            if ("group".equals(player.type)) excluded.add("group type");
            if (!sendspin) excluded.add(snapshot.sendspinOutputs.containsKey(player.id)
                ? "Sendspin output unavailable/unverified" : "no recognised Sendspin provider/output");
            String name = player.name == null ? "Unnamed" : player.name.replaceAll("[\\p{Cntrl}]", " ");
            if (name.length() > 64) name = name.substring(0, 64) + "…";
            String protocols = snapshot.diagnosticProtocols.get(player.id);
            result.add(name + "\nProvider: " + token(provider) + " · Type: " + token(player.type)
                + "\nAvailable/enabled: " + player.available + " · Visible: " + player.visible
                + "\nOutput protocols: " + (protocols == null || protocols.isEmpty() ? "none returned" : protocols)
                + "\nIP metadata: " + (address == null || address.isEmpty() ? "missing" : "present")
                + " · Device match: " + (ip == null || ip.isEmpty() ? "unknown" : match ? "yes" : "no")
                + "\nMA address format: " + addressFormat(address)
                + " · Comparison: " + comparison(ip, address)
                + "\nLocal picker: " + (excluded.isEmpty() ? "eligible" : "excluded — " + String.join(", ", excluded)));
        }
        return result;
    }
    static String addressFormat(String value) {
        if (value == null || value.isEmpty()) return "missing";
        if (!value.equals(value.trim())) return "surrounding whitespace";
        if (value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = value.split("\\.");
            for (String part : parts) if (Integer.parseInt(part) > 255) return "invalid IPv4";
            if (parts[0].equals("127")) return "IPv4 loopback";
            if (value.equals("0.0.0.0")) return "IPv4 unspecified";
            return "IPv4";
        }
        if (value.matches("(?i)::ffff:[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return "IPv4-mapped IPv6";
        if (value.matches("[0-9a-fA-F:]+") && value.contains(":")) {
            if (value.equals("::1")) return "IPv6 loopback";
            if (value.equals("::")) return "IPv6 unspecified";
            return "IPv6-like literal";
        }
        return "not a plain address literal";
    }
    static String comparison(String device, String player) {
        if (device == null || device.isEmpty() || player == null || player.isEmpty()) return "incomplete metadata";
        if (device.equals(player)) return "exact match";
        if (device.trim().equals(player.trim())) return "whitespace difference (not auto-matched)";
        if (("::ffff:" + device).equalsIgnoreCase(player) || ("::ffff:" + player).equalsIgnoreCase(device))
            return "IPv4/mapped-IPv6 notation difference (not auto-matched)";
        return "different reported values; identity not verified";
    }
}
