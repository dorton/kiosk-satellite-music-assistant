package net.markandrew.kiosk.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only display fields leave the API layer. No raw responses reach UI or logs. */
final class BrowserPage {
    final String heading, message;
    final List<BrowserItem> rows;
    final boolean next, busy, error;
    final int offset;
    final BrowserItem parent;

    BrowserPage(String heading, String message, List<BrowserItem> rows, boolean next, int offset, boolean busy, boolean error) {
        this(heading, message, rows, next, offset, busy, error, null);
    }
    BrowserPage(String heading, String message, List<BrowserItem> rows, boolean next, int offset, boolean busy, boolean error, BrowserItem parent) {
        this.heading = heading; this.message = message;
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        this.next = next; this.offset = offset; this.busy = busy; this.error = error;
        this.parent = parent;
    }
    static BrowserPage status(String heading, String message, boolean busy, boolean error) {
        return new BrowserPage(heading, message, Collections.emptyList(), false, 0, busy, error);
    }
    BrowserPage append(BrowserPage more) {
        if (more.offset <= offset) throw new IllegalArgumentException("Out-of-order page response.");
        List<BrowserItem> combined = new ArrayList<>(rows); combined.addAll(more.rows);
        boolean next = more.next && combined.size() + IngressPolicy.PAGE_SIZE <= IngressPolicy.MAX_BROWSE_ITEMS;
        return new BrowserPage(more.heading, next || !more.next ? more.message : "Browse limit reached; narrow the library filter.", combined, next, more.offset, false, false, more.parent);
    }
    static BrowserPage library(String heading, Object response, int offset) {
        if (!(response instanceof JSONArray)) throw new IllegalArgumentException();
        JSONArray items = (JSONArray) response;
        return new BrowserPage(heading, items.length() == 0 ? "No items found." : "Library",
            rows(items), items.length() == IngressPolicy.PAGE_SIZE, offset, false, false);
    }
    static BrowserPage search(Object response) {
        if (!(response instanceof JSONObject)) throw new IllegalArgumentException();
        JSONObject object = (JSONObject) response;
        List<BrowserItem> rows = new ArrayList<>();
        for (String key : new String[] {"albums", "playlists", "artists", "radios", "tracks", "podcasts", "audiobooks"}) {
            JSONArray values = object.optJSONArray(key);
            if (values != null) for (int i = 0; i < Math.min(25, values.length()); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item != null) rows.add(new BrowserItem(item, Character.toUpperCase(key.charAt(0)) + key.substring(1)));
            }
        }
        return new BrowserPage("Search", rows.isEmpty() ? "No matching items found." : "Search results",
            rows, false, 0, false, false);
    }
    private static List<BrowserItem> rows(JSONArray items) {
        List<BrowserItem> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(64, items.length()); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            rows.add(new BrowserItem(item));
        }
        return rows;
    }
    static String label(String input) {
        String clean = input.replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(clean.length(), 240));
    }

    static BrowserPage detail(String title, Object response, int offset) {
        BrowserPage value = library(title, response, offset);
        return new BrowserPage(title, value.message, value.rows, value.next, offset, false, false);
    }
}
