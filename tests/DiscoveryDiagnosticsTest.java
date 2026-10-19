package net.markandrew.kiosk.music;

import java.util.List;

public final class DiscoveryDiagnosticsTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Diagnostic check " + checks); }
    public static void main(String[] args) {
        check(DiscoveryDiagnostics.rows(null, "").get(0).contains("read failure"));
        PlayerPolicy.Snapshot s = new PlayerPolicy.Snapshot();
        check(DiscoveryDiagnostics.rows(s, "").get(0).contains("returned: 0"));
        s.players.put("echo", new PlayerPolicy.Player("echo", "Echo\nShow", "player", true, true, "", "", "", "idle"));
        s.providers.put("echo", "universal_player"); s.addresses.put("echo", "192.0.2.9");
        s.diagnosticProtocols.put("echo", "sendspin (available)");
        String row = DiscoveryDiagnostics.rows(s, "192.0.2.9").get(1);
        check(row.contains("Echo Show")); check(row.contains("universal_player"));
        check(row.contains("sendspin (available)")); check(row.contains("no recognised Sendspin provider/output"));
        check(row.contains("Device match: yes")); check(!row.contains("192.0.2.9"));
        check(!row.contains("ID: echo")); check(s.providers.get("echo").equals("universal_player"));
        s.providers.put("echo", "sendspin--test");
        check(DiscoveryDiagnostics.rows(s, "192.0.2.8").get(1).contains("Local picker: eligible"));
        check(DiscoveryDiagnostics.rows(s, "192.0.2.8").get(1).contains("Device match: no"));
        s.addresses.clear(); check(DiscoveryDiagnostics.rows(s, "").get(1).contains("IP metadata: missing"));
        check(DiscoveryDiagnostics.rows(s, "").get(1).contains("Device match: unknown"));
        s.players.put("echo", new PlayerPolicy.Player("echo", "Echo", "protocol", false, false, "", "", "", "idle"));
        row = DiscoveryDiagnostics.rows(s, "").get(1);
        check(row.contains("non-audio type")); check(row.contains("hidden/private"));
        check(row.contains("Available/enabled: false"));
        check(DiscoveryDiagnostics.token("https://secret.example/?token=abc").equals("missing/unsupported"));
        check(DiscoveryDiagnostics.token(null).equals("missing/unsupported"));
        for (int i = 0; i < 45; i++) s.players.put("p" + i,
            new PlayerPolicy.Player("p" + i, "Player", "player", true, true, "", "", "", "idle"));
        List<String> rows = DiscoveryDiagnostics.rows(s, "");
        check(rows.size() == 42); check(rows.get(41).contains("6 more not displayed"));
        check(DiscoveryDiagnostics.addressFormat("192.0.2.9").equals("IPv4"));
        check(DiscoveryDiagnostics.addressFormat("127.0.0.1").equals("IPv4 loopback"));
        check(DiscoveryDiagnostics.addressFormat("::1").equals("IPv6 loopback"));
        check(DiscoveryDiagnostics.addressFormat("0.0.0.0").contains("unspecified"));
        check(DiscoveryDiagnostics.addressFormat("::ffff:192.0.2.9").equals("IPv4-mapped IPv6"));
        check(DiscoveryDiagnostics.addressFormat("192.0.2.9 ").contains("whitespace"));
        check(DiscoveryDiagnostics.addressFormat("999.0.2.9").equals("invalid IPv4"));
        check(DiscoveryDiagnostics.addressFormat("https://example.test").contains("not a plain"));
        check(DiscoveryDiagnostics.comparison("192.0.2.9", "::ffff:192.0.2.9").contains("notation difference"));
        check(DiscoveryDiagnostics.comparison("192.0.2.9", "192.0.2.9 ").contains("whitespace difference"));
        check(DiscoveryDiagnostics.comparison("192.0.2.9", "192.0.2.10").contains("different reported"));
        PlayerPolicy.Snapshot wrapper = new PlayerPolicy.Snapshot();
        wrapper.players.put("echo", new PlayerPolicy.Player("echo", "Echo", "player", true, true, "", "", "", "idle"));
        wrapper.providers.put("echo", "universal_player"); wrapper.addresses.put("echo", "192.0.2.9");
        wrapper.sendspinOutputs.put("echo", true);
        check(DiscoveryPolicy.sendspin(wrapper, "echo"));
        check(DiscoveryPolicy.uniqueLocal(wrapper, "192.0.2.9").equals("echo"));
        check(DiscoveryPolicy.uniqueLocal(wrapper, "192.0.2.10").isEmpty());
        check(DiscoveryPolicy.uniqueLocal(wrapper, "::ffff:192.0.2.9").isEmpty());
        check(DiscoveryDiagnostics.rows(wrapper, "192.0.2.10").get(1).contains("Local picker: eligible"));
        wrapper.sendspinOutputs.put("echo", false);
        check(!DiscoveryPolicy.sendspin(wrapper, "echo"));
        check(DiscoveryPolicy.uniqueLocal(wrapper, "192.0.2.9").isEmpty());
        check(DiscoveryDiagnostics.rows(wrapper, "192.0.2.9").get(1).contains("output unavailable/unverified"));
        wrapper.providers.put("echo", "sendspin");
        check(!DiscoveryPolicy.sendspin(wrapper, "echo"));
        wrapper.sendspinOutputs.clear(); check(DiscoveryPolicy.sendspin(wrapper, "echo"));
        wrapper.providers.put("echo", "universal_player"); check(!DiscoveryPolicy.sendspin(wrapper, "echo"));
        System.out.println("PASS: " + checks + " discovery diagnostic checks (no network)");
    }
}
