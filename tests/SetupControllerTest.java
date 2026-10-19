package net.markandrew.kiosk.music;

import java.util.*;
import java.util.function.Consumer;

/** Real staged Setup with mocked MA/HA IO. No settings saved to a device. */
public final class SetupControllerTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Setup check " + checks); }
    static final class Ui implements SetupController.Ui {
        final Map<String, Object> settings = new HashMap<>();
        Map<String, Object> saved;
        TouchPanel panel;
        boolean dismissed;
        String own="";
        public String localIdentity(){return own;}
        public boolean controllerOnly(Map<String,Object> draft){return !own.isEmpty()&&OwnReceiverPolicy.controllerOnly(DefaultPlayerPolicy.saved(draft),own);}
        public Map<String, Object> settings() { return new HashMap<>(settings); }
        public void panel(String title, Consumer<TouchPanel> build) { panel = new TouchPanel(); build.accept(panel); }
        public void save(Map<String, Object> value) { saved = value; }
        public void dismiss() { dismissed = true; }
    }
    public static void main(String[] args) {
        MusicApi.setupSnapshot = new PlayerPolicy.Snapshot();
        for (String id : Arrays.asList("echo", "remote")) MusicApi.setupSnapshot.players.put(id,
            new PlayerPolicy.Player(id, id, "player", true, true, "", "", "", "idle"));
        Ui ui = new Ui(); ui.settings.put("playerMode", "Follow Kiosk Satellite"); ui.settings.put("localPlayer", "echo");
        SetupController setup = new SetupController(new PlayerControllerTest.Host(), new IngressAccess(),
            new PlayerControllerTest.Inline(), () -> true, ui);
        setup.open();
        check(ui.saved == null); check(ui.panel.pickers.containsKey("Default player"));
        check(!ui.panel.pickers.containsKey("Local Sendspin player")); check(!ui.panel.pickers.containsKey("This kiosk"));
        check(!ui.panel.pickers.containsKey("Default mode")); check(ui.panel.selected.get("Default player").equals("echo"));
        check(!ui.panel.buttons.containsKey("This device · receiver"));
        check(ui.panel.buttons.containsKey("Diagnostics"));check(ui.panel.pickers.containsKey("Music Assistant server"));check(ui.panel.sections.equals(Arrays.asList("Connection","Playback","This device")));
        check(ui.panel.selected.get("Ducking · % of current local music volume").equals("10"));
        check(ui.panel.selected.get("Prevent screensaver while playing").equals("false"));
        ui.panel.pickers.get("Ducking · % of current local music volume").accept("4");
        ui.panel.pickers.get("Prevent screensaver while playing").accept("true");
        ui.panel.buttons.get("Diagnostics").run();check(ui.panel.pickers.containsKey("Music Assistant server"));check(ui.saved==null);
        ui.panel.pickers.get("Default player").accept("remote");
        check(ui.saved == null && ui.settings.get("localPlayer").equals("echo"));
        ui.panel.buttons.get("Save").run();
        check(ui.saved.get("defaultPlayer").equals("remote")); check(ui.saved.get("playerMode").equals("Fixed default player"));
        check(ui.saved.get("localPlayer").equals("echo"));
        check(ui.saved.get("duckPercent").equals(4));check(ui.saved.get("preventScreensaverWhilePlaying").equals(true));
        ui.saved = null; ui.settings.clear(); setup.open(); ui.panel.buttons.get("Save").run();
        check(ui.saved == null); check(ui.panel.message.contains("default player in Setup"));
        ui.panel.pickers.get("Default player").accept("missing"); ui.panel.buttons.get("Save").run();
        check(ui.saved == null); check(ui.panel.message.contains("no fallback"));
        setup.open(); ui.panel.pickers.get("Default player").accept("echo"); ui.panel.buttons.get("Close").run();
        check(ui.saved == null && ui.dismissed);
        ui.dismissed = false; setup.open(); TouchPanel stale = ui.panel; setup.cancel(); stale.buttons.get("Save").run();
        check(ui.saved == null);
        ui.own="kiosk";ui.settings.put("defaultPlayer","remote");ui.settings.put("receiverEnabled",true);ui.settings.put("receiverName","Custom kiosk");setup.open();
        check(!ui.panel.pickers.containsKey("Ducking · % of current local music volume"));check(ui.saved==null);
        ui.panel.pickers.get("Default player").accept("kiosk");check(ui.panel.pickers.containsKey("Ducking · % of current local music volume"));
        ui.panel.buttons.get("Save").run();check(ui.saved.get("defaultPlayer").equals("kiosk"));check(ui.saved.get("receiverName").equals("Custom kiosk"));check(ui.saved.get("receiverEnabled").equals(true));
        ui.saved=null;ui.own="echo";setup.open();
        List<DiscoveryPolicy.Choice> choices=ui.panel.choices.get("Default player");check(choices.stream().filter(c->c.id.equals("echo")).count()==1);check(choices.stream().anyMatch(c->c.id.equals("echo")&&c.label.equals("This kiosk · echo")));
        String timer="Auto-revert after idle (seconds) · 0 = disabled";
        check(!ui.panel.choices.containsKey(timer));ui.panel.pickers.get(timer).accept("86401");ui.panel.buttons.get("Save").run();check(ui.saved==null&&ui.panel.message.contains("86400"));
        ui.panel.pickers.get(timer).accept("");ui.panel.buttons.get("Save").run();check(ui.saved==null);
        ui.panel.pickers.get(timer).accept("0");ui.panel.buttons.get("Save").run();check(ui.saved.get("idleRevertSeconds").equals("0"));
        ui.saved=null;setup.open();ui.panel.buttons.get("Diagnostics").run();check(ui.panel.buttons.containsKey("Check connection"));check(!ui.panel.buttons.containsKey("Retry registration"));
        ui.panel.buttons.get("Check connection").run();check(ui.panel.notes.contains("Player access · OK"));check(ui.saved==null);ui.panel.buttons.get("‹ Back").run();check(ui.panel.buttons.containsKey("Save"));
        MusicApi.setupSnapshot = null;
        System.out.println("PASS: " + checks + " mocked saved-default Setup checks (no network)");
    }
}
