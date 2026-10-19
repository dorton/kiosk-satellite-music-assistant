package net.markandrew.kiosk.music;
import java.util.*;
import java.util.regex.*;

/** Bounded LRC parsing, including offsets and multiple timestamps per line. */
final class LyricsData {
    static final class Line {
        final double time; final String text;
        Line(double time, String text) { this.time = time; this.text = text; }
    }
    final List<Line> lines;
    final String plain;
    LyricsData(List<Line> lines, String plain) { this.lines = Collections.unmodifiableList(lines); this.plain = plain; }
    static LyricsData parse(String plain, String timed) {
        String raw = timed == null || timed.trim().isEmpty() ? plain : timed;
        if (raw == null) raw = "";
        List<Line> lines = new ArrayList<>(); double offset = 0;
        Matcher offsetMatch = Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (offsetMatch.find()) try { offset = Double.parseDouble(offsetMatch.group(1)) / 1000; } catch (NumberFormatException ignored) {}
        Pattern stamp = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]");
        for (String row : raw.split("\\r?\\n")) {
            Matcher m = stamp.matcher(row); if (!m.find() || m.start() != 0) continue;
            String text = row.replaceAll("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]", "").replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "").trim();
            do { if (lines.size() >= 2000) break;
                lines.add(new Line(Math.max(0, Double.parseDouble(m.group(1)) * 60 + Double.parseDouble(m.group(2).replace(':', '.')) + offset), text));
            } while (m.find());
        }
        Collections.sort(lines, (a, b) -> Double.compare(a.time, b.time));
        return new LyricsData(lines, plain == null ? "" : plain.trim());
    }
    int index(double elapsed) { int result = -1; for (int i = 0; i < lines.size() && lines.get(i).time <= elapsed; i++) result = i; return result; }
}
