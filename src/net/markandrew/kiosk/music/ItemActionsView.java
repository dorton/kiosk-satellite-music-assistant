package net.markandrew.kiosk.music;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/** A native SDK overlay, not an Android dialog outside Kiosk's view texture. */
final class ItemActionsView extends LinearLayout {
    ItemActionsView(Context context, KsTheme theme, BrowserItem item, BrowserView.Actions actions, Runnable dismiss) {
        super(context);
        setOrientation(VERTICAL); setBackground(NativeStyle.background());
        setPadding(theme.px(12), theme.px(8), theme.px(12), theme.px(8));
        TextView heading = new TextView(context); heading.setText(item.name);
        heading.setMaxLines(2); heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        NativeStyle.text(heading, theme, 17, 600, "onSurface"); addView(heading);
        if (item.expandable()) add("Open", theme, false, () -> { dismiss.run(); actions.open(item); });
        if (item.playable()) {
            String[] options = {"play", "next", "add", "replace", "replace_next"};
            String[] labels = {"Play now (keep queue)", "Play next (keep queue)", "Add to the queue", "Play now (replace queue)", "Play next (replace queue)"};
            for (int i = 0; i < options.length; i++) {
                final String option = options[i];
                add(labels[i], theme, i == 0, () -> { dismiss.run(); actions.playOption(item, option); });
            }
        }
        add("Cancel", theme, false, dismiss);
    }

    private void add(String text, KsTheme theme, boolean primary, Runnable action) {
        Button button = new Button(getContext()); button.setText(text); NativeStyle.button(button, theme, primary);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setIncludeFontPadding(false);
        button.setPadding(theme.px(12), 0, theme.px(12), 0); button.setGravity(Gravity.CENTER);
        button.setOnClickListener(view -> {
            try { action.run(); }
            catch (RuntimeException | LinkageError ignored) { button.setText("Action unavailable"); button.setEnabled(false); }
        });
        addView(button, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.px(TouchLayout.TARGET)));
    }
}
