package me.jxl.kiosk.plugins;
import java.util.Map;
/** Test-only minimal host; never part of the Android source set or plugin DEX. */
public interface PluginHost {
    interface CommandCallback { void onResult(boolean ok, Object data, String error); }
    void haCall(Map<String, Object> message, CommandCallback callback);
    default void executeCommand(String command, Map<String, Object> values, CommandCallback callback) { throw new UnsupportedOperationException(); }
}
