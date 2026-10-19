package net.markandrew.kiosk.music;

import org.json.JSONArray;
import org.json.JSONObject;

/** Display and navigation fields only, detached from the server response. */
final class BrowserItem {
    final String name, subtitle, uri, type, id, provider, imagePath;
    final boolean available;
    final boolean favorite;
    final boolean editable, dynamic;
    final int duration, position;
    final String section;

    BrowserItem(JSONObject item) {
        this(item, "");
    }
    BrowserItem(JSONObject item, String section) {
        this.section = section; favorite = item.optBoolean("favorite", false);
        editable = item.optBoolean("is_editable", false); dynamic = item.optBoolean("is_dynamic", false);
        duration = item.optInt("duration", 0); position = item.optInt("position", -1);
        name = BrowserPage.label(item.optString("name", "Untitled"));
        type = item.optString("media_type", "");
        id = item.optString("item_id", ""); provider = item.optString("provider", "");
        uri = item.optString("uri", ""); available = item.optBoolean("available", true);
        JSONArray artists = item.optJSONArray("artists");
        subtitle = artists != null && artists.optJSONObject(0) != null
            ? BrowserPage.label(artists.optJSONObject(0).optString("name", "")) : BrowserPage.label(type);
        String image = "";
        JSONObject metadata = item.optJSONObject("metadata");
        JSONArray images = metadata == null ? null : metadata.optJSONArray("images");
        if (images != null) for (int i = 0; i < images.length(); i++) {
            JSONObject candidate = images.optJSONObject(i);
            if (candidate == null || !"thumb".equals(candidate.optString("type"))) continue;
            String proxy = candidate.optString("proxy_id", "");
            if (proxy.matches("[A-Za-z0-9_-]{1,512}")) image = "imageproxy/" + proxy + "?size=256";
            else if (!candidate.optString("path").isEmpty() && !candidate.optString("provider").isEmpty()) {
                try {
                    image = "imageproxy?path=" + java.net.URLEncoder.encode(candidate.getString("path"), "UTF-8")
                        + "&provider=" + java.net.URLEncoder.encode(candidate.getString("provider"), "UTF-8") + "&size=256";
                } catch (Exception ignored) { image = ""; }
            }
            break;
        }
        imagePath = image.length() <= 4096 ? image : "";
    }

    boolean expandable() { return type.equals("album") || type.equals("playlist") || type.equals("artist") || type.equals("podcast"); }
    boolean playable() {
        return available && !uri.isEmpty() && (type.equals("artist") || type.equals("album") || type.equals("playlist") || type.equals("radio")
            || type.equals("track") || type.equals("audiobook") || type.equals("podcast_episode"));
    }
}
