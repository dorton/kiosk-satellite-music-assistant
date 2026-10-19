package net.markandrew.kiosk.music;

import java.util.Map;

/** Local chooser exception only. MA still authorises every read and playback command. */
final class OwnReceiverPolicy {
    static boolean controllerOnly(String savedDefault,String localId){
        return savedDefault!=null&&!savedDefault.isEmpty()&&(localId==null||localId.isEmpty()||!savedDefault.equals(localId));
    }
    static String id(Map<String, Object> state, boolean enabled, String configuredAddon, String requestedAddon) {
        if (!enabled || configuredAddon == null || configuredAddon.isEmpty() || !configuredAddon.equals(requestedAddon)
                || state == null || !Boolean.TRUE.equals(state.get("running"))
                || !Boolean.TRUE.equals(state.get("connected")) || !Boolean.TRUE.equals(state.get("paired"))) return "";
        Object id = state.get("clientId");
        return id instanceof String && ((String)id).matches("[A-Za-z0-9_-]{43}") ? (String)id : "";
    }
    static boolean selectable(String id, String type, String ownId, boolean hidden) {
        return !hidden || ("player".equals(type) && id != null && !id.isEmpty() && id.equals(ownId));
    }
}
