package net.markandrew.kiosk.music;

/** Pure naming rules; never change receiver identity to rename it. */
final class ReceiverNamePolicy {
    static final String FALLBACK = "Music Assistant Plugin";
    static String clean(Object value) {
        if (!(value instanceof String)) return "";
        String name = ((String)value).replaceAll("[\\p{Cntrl}]", " ").trim();
        if (name.length() > 80) {
            int end = Character.isHighSurrogate(name.charAt(79)) ? 79 : 80;
            name = name.substring(0, end).trim();
        }
        return name;
    }
    static String override(Object value) {
        String name = clean(value);
        // The old shipped placeholder was not an explicit device-specific name.
        return FALLBACK.equals(name) ? "" : name;
    }
    static String resolve(Object override, Object kioskName) {
        String custom = override(override);
        if (!custom.isEmpty()) return custom;
        String kiosk = clean(kioskName);
        return kiosk.isEmpty() ? FALLBACK : kiosk;
    }
}
