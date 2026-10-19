package net.markandrew.kiosk.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Detached discovery choices only: no raw settings, credentials or name-based room guesses. */
final class DiscoveryPolicy {
    static final int MAX_CATALOGUE = 2 * 1024 * 1024;
    static java.net.URI signedServices(java.net.URI origin, String path) {
        try {
            if (!origin.equals(IngressPolicy.origin(origin.toString()))) throw new IllegalArgumentException();
            java.net.URI relative = new java.net.URI(path);
            if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawFragment() != null
                || !"/api/services".equals(relative.getRawPath()) || relative.getRawQuery() == null
                || !relative.getRawQuery().matches("authSig=[A-Za-z0-9_.-]{1,4096}")) throw new IllegalArgumentException();
            return origin.resolve(relative);
        } catch (Exception ignored) { throw new IllegalArgumentException("HA returned an unexpected signed service path."); }
    }
    static String failure(String stage, Object data) {
        String code = data instanceof Map ? text((Map<?, ?>) data, "code") : "";
        // Show only a bounded code, never raw HA error text, URLs or signatures.
        if (!code.matches("[a-z_]{1,40}")) code = "unknown_error";
        String hint;
        switch (code) {
            case "too_large": hint = "Response exceeds Kiosk's 32 KB bridge limit."; break;
            case "unauthorized": case "auth_invalid": hint = "HA refused this kiosk user's access."; break;
            case "not_allowed": hint = "This Kiosk build does not allow that API command."; break;
            case "timeout": case "unreachable": case "connection_lost": hint = "HA did not complete the read; try Refresh."; break;
            default: hint = "The read was not completed; other setup choices can still load.";
        }
        return stage + " failed [" + code + "]. " + hint;
    }
    static final class Choice {
        final String id, label;
        Choice(String id, String label) { this.id = id; this.label = label; }
    }
    static boolean showInstance(List<Choice> addons, String saved) {
        return addons.size() != 1 || (!saved.isEmpty() && !saved.equals(addons.get(0).id));
    }
    static String uniqueNode(List<Choice> nodes, Map<String, String> addresses, String ip) {
        if (ip == null || ip.isEmpty()) return "";
        String found = "";
        for (Choice node : nodes) if (ip.equals(addresses.get(node.id))) {
            if (!found.isEmpty()) return "";
            found = node.id;
        }
        return found;
    }
    static List<Choice> addons(Object response) {
        if (!(response instanceof Map) || !(((Map<?, ?>) response).get("addons") instanceof List))
            throw new IllegalArgumentException("HA did not return its installed apps. Check Supervisor permissions.");
        List<Choice> result = new ArrayList<>();
        for (Object row : (List<?>) ((Map<?, ?>) response).get("addons")) {
            if (!(row instanceof Map)) continue;
            Map<?, ?> app = (Map<?, ?>) row;
            String slug = text(app, "slug"), name = text(app, "name");
            if (!slug.matches("[a-z0-9_]{1,100}")) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.equals("music assistant") || lower.startsWith("music assistant ")
                || slug.matches("(?:[a-z0-9]+_)?music_assistant(?:_(?:beta|dev))?"))) continue;
            boolean duplicate = ((List<?>) ((Map<?, ?>) response).get("addons")).stream()
                .filter(rowValue -> rowValue instanceof Map && name.equals(text((Map<?, ?>) rowValue, "name"))
                    && text((Map<?, ?>) rowValue, "slug").matches("[a-z0-9_]{1,100}")).count() > 1;
            result.add(new Choice(slug, duplicate ? name + " · " + slug : name));
            if (result.size() >= 32) throw new IllegalArgumentException("Too many MA instances; discovery was not complete.");
        }
        return Collections.unmodifiableList(result);
    }
    /** HA's user-filtered sidebar is public discovery; /addons itself requires admin. */
    static List<Choice> panels(Object response) {
        if(!(response instanceof Map))throw new IllegalArgumentException("HA did not return accessible app panels.");
        List<Choice> result=new ArrayList<>();
        for(Object value:((Map<?,?>)response).values()){
            if(!(value instanceof Map))continue;
            Map<?,?> panel=(Map<?,?>)value;
            String component=text(panel,"component_name");
            if(!component.equals("app")&&!component.equals("hassio"))continue;
            Object config=panel.get("config");if(!(config instanceof Map))continue;
            String slug=text((Map<?,?>)config,"addon"),name=text(panel,"title");
            if(!slug.matches("(?:[a-z0-9]+_)?music_assistant(?:_(?:beta|dev))?"))continue;
            if(result.stream().anyMatch(p->p.id.equals(slug)))continue;
            result.add(new Choice(slug,name.isEmpty()?"Music Assistant":name));
            if(result.size()>=32)throw new IllegalArgumentException("Too many accessible MA panels.");
        }
        return Collections.unmodifiableList(result);
    }
    static List<Choice> serverNames(List<Choice> choices){
        List<Choice> names=new ArrayList<>(),result=new ArrayList<>();
        for(Choice c:choices){String suffix=" · "+c.id;names.add(new Choice(c.id,c.label.endsWith(suffix)?c.label.substring(0,c.label.length()-suffix.length()):c.label));}
        for(int i=0;i<names.size();i++){
            Choice c=names.get(i);boolean duplicate=names.stream().filter(other->other.label.equals(c.label)).count()>1;
            int number=1;for(int j=0;j<i;j++)if(names.get(j).label.equals(c.label))number++;
            result.add(new Choice(c.id,c.label+(duplicate?" ("+number+")":"")));
        }
        return result;
    }
    static List<Choice> nodes(Object response) {
        if (!(response instanceof Map)) throw new IllegalArgumentException("HA services are unavailable.");
        Object domain = ((Map<?, ?>) response).get("esphome");
        if (!(domain instanceof Map)) return Collections.emptyList();
        Map<?, ?> actions = (Map<?, ?>) domain;
        List<Choice> result = new ArrayList<>();
        String suffix = "_media_player_list";
        for (Object value : actions.keySet()) {
            String key = String.valueOf(value);
            if (!key.endsWith(suffix)) continue;
            String node = key.substring(0, key.length() - suffix.length());
            if (!node.matches("[a-z0-9_]{1,100}") || !actions.containsKey(node + "_media_player_set")) continue;
            Object description = actions.get(key);
            String label = description instanceof Map ? text((Map<?, ?>) description, "name") : "";
            label = label.replaceFirst("(?i)\\s*[:·-]?\\s*media[ _]player[ _]list\\s*$", "").trim();
            if (label.isEmpty()) label = node.replace('_', ' ');
            final String friendly = label;
            boolean duplicate = result.stream().anyMatch(choice -> choice.label.equals(friendly));
            if (duplicate) {
                for (int i = 0; i < result.size(); i++) {
                    Choice previous = result.get(i);
                    if (previous.label.equals(friendly)) result.set(i, new Choice(previous.id, friendly + " · " + previous.id));
                }
            }
            // Disambiguate all identical names without changing the service IDs used for control.
            if (result.stream().anyMatch(choice -> choice.label.startsWith(friendly + " · "))) duplicate = true;
            result.add(new Choice(node, duplicate ? friendly + " · " + node : friendly));
            if (result.size() >= 256) throw new IllegalArgumentException("Too many kiosks; discovery was not complete.");
        }
        return Collections.unmodifiableList(result);
    }
    static String uniqueLocal(PlayerPolicy.Snapshot snapshot, String ip) {
        if (ip == null || ip.isEmpty()) return "";
        String found = "";
        for (PlayerPolicy.Player p : snapshot.players.values()) {
            if (!p.audio() || "group".equals(p.type) || !sendspin(snapshot, p.id)
                || !ip.equals(snapshot.addresses.get(p.id))) continue;
            if (!found.isEmpty()) return "";
            found = p.id;
        }
        return found;
    }
    static boolean sendspin(PlayerPolicy.Snapshot snapshot, String id) {
        if (snapshot.sendspinOutputs.containsKey(id)) return Boolean.TRUE.equals(snapshot.sendspinOutputs.get(id));
        String provider = snapshot.providers.get(id);
        return provider != null && (provider.equals("sendspin") || provider.startsWith("sendspin--"));
    }
    static String text(Map<?, ?> map, String key) {
        Object value = map.get(key); return value instanceof String ? (String) value : "";
    }
}
