package net.markandrew.kiosk.music;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Real loopback HTTP; response secrets must not appear in exception diagnostics. */
public final class MusicApiDiagnosticsTest {
    static int checks, mode;
    static volatile int requests;
    static volatile String lastRequest;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("HTTP diagnostic check " + checks); }
    public static void main(String[] args) throws Exception {
        String fixture = "disposable-response-secret";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            try {
                requests++;
                java.io.ByteArrayOutputStream requestBody = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[512]; int count;
                while ((count = exchange.getRequestBody().read(chunk)) != -1) requestBody.write(chunk, 0, count);
                lastRequest = new String(requestBody.toByteArray(), StandardCharsets.UTF_8);
                byte[] body;
                int code = mode >= 300 ? mode : 200;
                String type = mode == 1 ? "text/html" : "application/json";
                if (mode == 2) body = ("{broken-" + fixture).getBytes(StandardCharsets.UTF_8);
                else if (mode == 3) body = ("{} " + fixture).getBytes(StandardCharsets.UTF_8);
                else if (mode == 0) body = "{\"admin\":[\"*\"]}".getBytes(StandardCharsets.UTF_8);
                else if (mode == 4) body = "null".getBytes(StandardCharsets.UTF_8);
                else body = fixture.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                if (code == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/" + fixture);
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            MusicApi api = new MusicApi();
            IngressAccess.Session session = new IngressAccess.Session(new URI("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            mode = 0;
            check(api.command(session, "auth/scopes", new JSONObject()) instanceof JSONObject);
            check(api.lastHttpStatus() == 200);
            mode = 4;
            check(api.mutate(session, "sendspin/pair_web_player", new JSONObject().put("pairing_token", fixture), () -> true) == JSONObject.NULL);
            JSONObject sent = new JSONObject(lastRequest);
            check("sendspin/pair_web_player".equals(sent.getString("command")));
            check(fixture.equals(sent.getJSONObject("args").getString("pairing_token")));
            int before = requests;
            try { api.mutate(session, "sendspin/pair_web_player", new JSONObject().put("pairing_token", fixture), () -> false); throw new AssertionError("stale mutation allowed"); }
            catch (IllegalArgumentException expected) { check(requests == before); }
            mode = 403;
            try { api.mutate(session, "sendspin/pair_web_player", new JSONObject().put("pairing_token", fixture), () -> true); throw new AssertionError("denied mutation allowed"); }
            catch (PairingDiagnostics.Failure expected) {
                check(requests == before + 1 && expected.http == 403);
                check(!PairingDiagnostics.describe(expected).contains(fixture));
            }
            for (int test : new int[]{401, 403, 400, 500, 302, 1, 2, 3}) {
                mode = test;
                try { api.command(session, "auth/scopes", new JSONObject()); throw new AssertionError("Expected failure"); }
                catch (PairingDiagnostics.Failure error) {
                    int expectedCode = test >= 300 ? test : 200;
                    check(error.http == expectedCode && api.lastHttpStatus() == expectedCode);
                    check(error.kind == (test >= 300 ? PairingDiagnostics.Kind.HTTP : test == 1 ? PairingDiagnostics.Kind.NON_JSON : PairingDiagnostics.Kind.INVALID_JSON));
                    check(!PairingDiagnostics.describe(error).contains(fixture));
                    check(!error.toString().contains(fixture) && error.getCause() == null);
                }
            }
            mode = 0;
            check(api.command(session, "auth/scopes", new JSONObject()) instanceof JSONObject);
            check(api.lastHttpStatus() == 200);
            System.out.println("MusicApiDiagnostics: " + checks + " real loopback HTTP checks passed (no HA/MA access)");
        } finally { server.stop(0); }
    }
}
