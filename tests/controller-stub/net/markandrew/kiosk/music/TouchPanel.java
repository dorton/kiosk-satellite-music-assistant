package net.markandrew.kiosk.music;
import java.util.*;
final class TouchPanel {
    final Map<String, Runnable> buttons = new LinkedHashMap<>();
    final Map<String,String> subtitles = new LinkedHashMap<>();
    final Map<String, java.util.function.Consumer<String>> pickers = new LinkedHashMap<>();
    final Map<String, String> selected = new LinkedHashMap<>();
    final Map<String,List<DiscoveryPolicy.Choice>> choices=new LinkedHashMap<>();
    final List<String> notes=new ArrayList<>();
    final List<String> sections=new ArrayList<>();
    final Set<String> checked=new LinkedHashSet<>();
    String message;
    void add(String label, Runnable action) { buttons.put(label, action); }
    void formActions(String label,Runnable save,Runnable close){buttons.put(label,save);buttons.put("Close",close);}
    void navigation(String icon,String label,Runnable action){buttons.put(label,action);}
    void boundedSlider(String label,int min,int max,int value,java.util.function.IntConsumer action){selected.put(label,String.valueOf(value));pickers.put(label,v->action.accept(Integer.parseInt(v)));}
    void toggle(String label,boolean value,java.util.function.Consumer<Boolean> action){selected.put(label,String.valueOf(value));pickers.put(label,v->action.accept(Boolean.parseBoolean(v)));}
    void closeAction(String label, Runnable action) { buttons.put(label, action); }
    void tool(String icon,String label,boolean active,Runnable action) { buttons.put(label,action); }
    void row(String title,String subtitle,String symbol,boolean selected,boolean enabled,Runnable action,Runnable options) { subtitles.put(title,subtitle);if(selected)checked.add(title);if(enabled)buttons.put(title,action); }
    void message(String value) { message = value; }
    void note(String value) {notes.add(value);}
    void numberInput(String label,String value,java.util.function.Consumer<String> change){selected.put(label,value);pickers.put(label,change);}
    void menuSpacing() {}
    void guard(java.util.function.BooleanSupplier current) {}
    void section(String label) {sections.add(label);}
    void disclosure(String label,java.util.function.Consumer<TouchPanel> build){buttons.put(label,()->build.accept(this));}
    void picker(String title, List<DiscoveryPolicy.Choice> choices, String value, java.util.function.Consumer<String> pick) {
        pickers.put(title, pick); selected.put(title, value);
        this.choices.put(title,choices);
    }
    void picker(String title,List<DiscoveryPolicy.Choice> choices,String value,java.util.function.Consumer<String> pick,boolean separateLabel){picker(title,choices,value,pick);}
}
