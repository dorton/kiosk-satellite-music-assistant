package net.markandrew.kiosk.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.*;
import me.jxl.kiosk.plugins.KsTheme;

/** Plugin-owned playback surface using the shared Echo overview style. */
final class NowPlayingView extends LinearLayout {
    interface Actions { void close(); void control(String command, int value); void navigate(String destination); }
    private final KsTheme theme;
    private final Actions actions;
    private final ImageView artwork;
    private final TextView player, device, title, artist, time, message, volumeValue, volumeLabel;
    private final Button previous, play, next, shuffle, repeat, favorite, mute, players;
    private final SeekBar progress, volume;
    private final LinearLayout volumeBox,transport;
    private final android.view.View volumeSpacer;
    private NowPlayingData data;
    private boolean dragging, volumeDragging, busy = true;
    private Button equaliser;
    void equaliserAvailable(boolean allowed){if(equaliser!=null)equaliser.setVisibility(allowed?VISIBLE:GONE);}
    NowPlayingView(Context context, KsTheme theme, Actions actions) {
        super(context); this.theme = theme; this.actions = actions;
        setOrientation(VERTICAL);
        setBackground(NativeStyle.background());
        setPadding(theme.px(12), theme.px(4), theme.px(12), theme.px(4));
        LinearLayout navigation = new LinearLayout(context);navigation.setGravity(Gravity.CENTER);
        navigation.addView(button("Close", () -> actions.navigate("home")), navigationSlot());
        navigation.addView(button("Library", () -> actions.close()), navigationSlot());
        players = button("Player", () -> actions.navigate("players"));
        navigation.addView(players, navigationSlot());
        addView(navigation, new LayoutParams(LayoutParams.MATCH_PARENT, theme.px(48)));
        artwork = new ImageView(context); artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artwork.setContentDescription("Album artwork"); artwork.setBackground(null); artwork.setPadding(0, 0, 0, 0);
        artwork(null);
        FrameLayout body = new FrameLayout(context) {
            private boolean horizontal;
            private int gap=theme.px(16);
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
                int boundedWidth=Math.min(width,theme.px(1100)),boundedHeight=Math.min(height,theme.px(540));
                horizontal=boundedWidth>=theme.px(640)&&width>height;
                android.view.View controls=getChildAt(1);
                int artSize=horizontal?Math.max(0,Math.min(theme.px(440),Math.min(boundedHeight,(int)(boundedWidth*.42f)))):
                    Math.max(0,Math.min(theme.px(360),Math.min(boundedWidth,boundedHeight-theme.px(320)-gap)));
                int controlWidth=horizontal?boundedWidth-artSize-gap:Math.min(boundedWidth,theme.px(560));
                int controlHeight=horizontal?boundedHeight:Math.max(0,height-artSize-gap);
                boolean compact=controlHeight<theme.px(360);
                transport.getLayoutParams().height=theme.px(compact?56:60);
                volumeBox.setPadding(0,theme.px(compact?2:6),0,theme.px(compact?2:4));
                volumeBox.getLayoutParams().height=theme.px(data!=null&&data.groupVolume?(compact?66:76):54);
                if(message!=null){
                    message.setSingleLine(compact);message.setMaxLines(compact?1:2);message.setEllipsize(compact?android.text.TextUtils.TruncateAt.END:null);
                    message.getLayoutParams().height=compact?(int)Math.ceil(message.getTextSize()*1.35f):LayoutParams.WRAP_CONTENT;
                    message.setTooltipText(message.getText());
                    // On short displays, feedback temporarily occupies the artist's row
                    // budget instead of pushing fixed controls below the screen.
                    artist.setVisibility(compact&&message.getVisibility()!=GONE?GONE:VISIBLE);
                }
                LinearLayout column=(LinearLayout)controls;ScrollView meta=(ScrollView)column.getChildAt(1);
                // Measure the actual fixed rows first, including group volume, timer,
                // feedback and font scaling. Only metadata gives up height or scrolls.
                meta.getLayoutParams().height=0;
                controls.measure(MeasureSpec.makeMeasureSpec(Math.max(0,controlWidth),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                int fixedHeight=controls.getMeasuredHeight();
                int identityHeight=column.getChildAt(0).getMeasuredHeight();
                int metadataLimit=Math.max(theme.px(44),theme.px(144)-identityHeight-(artist==null?0:artist.getMeasuredHeight())-(time==null?0:time.getMeasuredHeight()));
                meta.getLayoutParams().height=Math.max(0,Math.min(metadataLimit,controlHeight-fixedHeight));
                artwork.measure(MeasureSpec.makeMeasureSpec(artSize,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(artSize,MeasureSpec.EXACTLY));
                controls.measure(MeasureSpec.makeMeasureSpec(Math.max(0,controlWidth),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,controlHeight),MeasureSpec.AT_MOST));
                setMeasuredDimension(width,height);
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
                android.view.View controls=getChildAt(1);int aw=artwork.getMeasuredWidth(),ch=controls.getMeasuredHeight(),cw=controls.getMeasuredWidth();
                if(horizontal){
                    int x=(getMeasuredWidth()-aw-gap-cw)/2,y=getMeasuredHeight()/2;
                    artwork.layout(x,y-aw/2,x+aw,y-aw/2+aw);x+=aw+gap;
                    controls.layout(x,y-ch/2,x+cw,y-ch/2+ch);
                }else{
                    int y=Math.max(0,(getMeasuredHeight()-aw-gap-ch)/2),x=(getMeasuredWidth()-aw)/2;
                    artwork.layout(x,y,x+aw,y+aw);y+=aw+gap;x=(getMeasuredWidth()-cw)/2;
                    controls.layout(x,y,x+cw,y+ch);
                }
            }
        };
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(artwork,new FrameLayout.LayoutParams(0,0));
        LinearLayout controls = new LinearLayout(context); controls.setOrientation(VERTICAL);
        body.addView(controls,new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT));
        // Identity, artist and timer always have their own rows; only the title scrolls.
        LinearLayout identity=new LinearLayout(context);identity.setOrientation(VERTICAL);controls.addView(identity,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        player = text(identity, 12, 400, "onSurfaceVariant");player.setSingleLine(true);player.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lineHeight(player);
        device = text(identity, 11, 400, "onSurfaceVariant");device.setSingleLine(true);device.setEllipsize(android.text.TextUtils.TruncateAt.END);device.setVisibility(GONE);
        lineHeight(device);
        ScrollView metadataScroll=new ScrollView(context);metadataScroll.setFillViewport(false);
        controls.addView(metadataScroll,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        LinearLayout metadata=new LinearLayout(context);metadata.setOrientation(VERTICAL);metadataScroll.addView(metadata);
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL); metadata.addView(heading);
        title = new TextView(context); NativeStyle.text(title, theme, 20, 600, "onSurface");title.setIncludeFontPadding(false);
        heading.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        favorite = icon("heart-outline", "Favourite", false, () -> send("favorite", 0)); heading.addView(favorite, new LayoutParams(theme.px(44), theme.px(44)));
        artist = text(controls, 15, 400, "onSurfaceVariant");artist.setSingleLine(true);artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lineHeight(artist);
        time = text(controls, 12, 400, "onSurfaceVariant");time.setSingleLine(true);
        lineHeight(time);
        progress = slider("Track position"); controls.addView(progress,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(32)));
        transport = new LinearLayout(context); transport.setGravity(Gravity.CENTER_VERTICAL); controls.addView(transport,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(60)));
        shuffle = icon("shuffle", "Shuffle", false, () -> send("shuffle", 0));
        previous = icon("skip-previous", "Previous", false, () -> send("previous", 0));
        play = icon("play", "Play", true, () -> { if (data != null) send("playing".equals(data.state) ? "pause" : "play", 0); });
        next = icon("skip-next", "Next", false, () -> send("next", 0));
        repeat = icon("repeat", "Repeat", false, () -> send("repeat", 0));
        transport.setGravity(Gravity.CENTER);
        for (Button b : new Button[]{shuffle, previous, play, next, repeat}) {
            int size=b==play?52:44;addCell(transport,b,size,size);
        }
        volumeBox=new LinearLayout(context);volumeBox.setOrientation(VERTICAL);volumeBox.setPadding(0,theme.px(6),0,theme.px(4));
        controls.addView(volumeBox,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(76)));
        volumeLabel=text(volumeBox,11,400,"onSurfaceVariant");volumeSpacer=new android.view.View(context);volumeBox.addView(volumeSpacer,new LayoutParams(1,theme.px(2)));
        volumeLabel.setGravity(Gravity.CENTER);LayoutParams labelSlot=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);labelSlot.leftMargin=theme.px(44);labelSlot.rightMargin=theme.px(36);volumeLabel.setLayoutParams(labelSlot);
        lineHeight(volumeLabel);
        LinearLayout loudness = new LinearLayout(context); loudness.setGravity(Gravity.CENTER_VERTICAL); volumeBox.addView(loudness,row());
        mute = icon("volume-high", "Mute", false, () -> send("mute", 0)); loudness.addView(mute, new LayoutParams(theme.px(44), theme.px(44)));
        volume = slider("Player volume");NativeStyle.volumeSlider(volume,theme); volume.setMax(100);LayoutParams volumeSlot=new LayoutParams(0,theme.px(44),1);volumeSlot.leftMargin=theme.px(8);volumeSlot.rightMargin=theme.px(4);loudness.addView(volume,volumeSlot);
        volumeValue = text(loudness, 12, 400, "onSurfaceVariant"); volumeValue.setMinWidth(theme.px(36));
        LinearLayout tools = new LinearLayout(context);tools.setGravity(Gravity.CENTER);LayoutParams toolsSlot=new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(48));toolsSlot.topMargin=theme.px(8);controls.addView(tools,toolsSlot);
        String[] destinations={"Queue","Lyrics","Transfer","Group","Equaliser"};
        String[] icons={"playlist-play","text-long","arrow-right","speaker-multiple","equalizer"};
        for (int i=0;i<destinations.length;i++) {
            final String destination=destinations[i];
            Button tool=button(destination,()->actions.navigate(destination.toLowerCase(java.util.Locale.ROOT)));
            if("Equaliser".equals(destination)){equaliser=tool;tool.setVisibility(GONE);}
            android.graphics.drawable.Drawable glyph=NativeStyle.icon(theme,"mdi:"+icons[i],"onSurfaceVariant");
            if(glyph!=null){glyph.setBounds(0,0,theme.px(20),theme.px(20));tool.setCompoundDrawables(null,glyph,null,null);}
            tool.setCompoundDrawablePadding(theme.px(2));tool.setPadding(theme.px(3),theme.px(3),theme.px(3),theme.px(3));
            NativeStyle.text(tool,theme,11,400,"onSurfaceVariant");
            LayoutParams slot=new LayoutParams(0,theme.px(48),1);slot.setMargins(theme.px(4),0,theme.px(4),0);tools.addView(tool,slot);
        }
        message = text(controls, 11, 400, "onSurfaceVariant");message.setMaxLines(2);message.setVisibility(GONE);
        progress.setMax(1000);
        progress.setOnSeekBarChangeListener(listener(true)); volume.setOnSeekBarChangeListener(listener(false));
        enable();
    }
    private SeekBar.OnSeekBarChangeListener listener(boolean position) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {}
            public void onStartTrackingTouch(SeekBar bar) { if (position) dragging = true; else volumeDragging = true; }
            public void onStopTrackingTouch(SeekBar bar) {
                if (data != null) send(position ? "seek" : "volume_set", position ? (int) ((long)bar.getProgress() * data.duration / 1000) : bar.getProgress());
                if (position) dragging = false; else volumeDragging = false;
            }
        };
    }
    private void send(String command, int value) { if (!busy && data != null) actions.control(command, value); }
    private LayoutParams row() { return new LayoutParams(LayoutParams.MATCH_PARENT, theme.px(44)); }
    private void addCell(LinearLayout row,android.view.View button,int width,int height){
        FrameLayout cell=new FrameLayout(getContext());row.addView(cell,new LayoutParams(0,LayoutParams.MATCH_PARENT,1));
        cell.addView(button,new FrameLayout.LayoutParams(theme.px(width),theme.px(height),Gravity.CENTER));
    }
    private LayoutParams navigationSlot() {
        LayoutParams slot=new LayoutParams(LayoutParams.WRAP_CONTENT,theme.px(48));slot.setMargins(theme.px(4),0,theme.px(4),0);return slot;
    }
    @Override protected void onMeasure(int width,int height){
        int dp=Math.round(MeasureSpec.getSize(height)/getResources().getDisplayMetrics().density);
        int top=theme.px(dp<430?8:Math.max(8,Math.min(24,dp/24)));if(getPaddingTop()!=top)setPadding(theme.px(12),top,theme.px(12),theme.px(8));
        super.onMeasure(width,height);
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(getContext()); b.setText(label); NativeStyle.button(b, theme, false); b.setPadding(theme.px(12), 0, theme.px(12), 0);b.setMinimumWidth(theme.px(48));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private Button icon(String name, String label, boolean filled, Runnable action) {
        Button b = new IconButton(getContext()); b.setOnClickListener(v -> action.run());
        NativeStyle.button(b, theme, filled); b.setPadding(theme.px(4), 0, theme.px(4), 0);
        setIcon(b, name, label, filled ? "onPrimary" : "onSurface"); return b;
    }
    private static final class IconButton extends Button {
        android.graphics.drawable.Drawable glyph;
        IconButton(Context context) { super(context); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (glyph == null) return;
            int save = canvas.save();
            canvas.translate((getWidth() - glyph.getBounds().width()) / 2f, (getHeight() - glyph.getBounds().height()) / 2f);
            glyph.setAlpha(isEnabled() ? 255 : 90); glyph.draw(canvas); canvas.restoreToCount(save);
        }
    }
    private void setIcon(Button b, String name, String label, String role) {
        android.graphics.drawable.Drawable image = NativeStyle.icon(theme,"mdi:" + name, role);
        b.setContentDescription(label); b.setTooltipText(label);
        if (image != null) image.setBounds(0, 0, theme.px(24), theme.px(24));
        ((IconButton)b).glyph = image; b.setText(image == null ? label : ""); b.invalidate();
        b.setTextColor(NativeStyle.color(role));
    }
    private TextView text(LinearLayout parent, float size, int weight, String color) {
        TextView t = new TextView(getContext()); NativeStyle.text(t, theme, size, weight, color);t.setIncludeFontPadding(false); parent.addView(t); return t;
    }
    private void lineHeight(TextView text){text.getLayoutParams().height=(int)Math.ceil(text.getTextSize()*1.35f);text.setGravity(Gravity.CENTER_VERTICAL);}
    private SeekBar slider(String label) {
        SeekBar b = new SeekBar(getContext()); b.setContentDescription(label); NativeStyle.slider(b,theme); return b;
    }
    void render(NowPlayingData value) {
        data = value; busy = false; player.setText(value.player); title.setText(value.title); artist.setText(value.artist);
        device.setText(value.device);device.setVisibility(value.device.isEmpty()?GONE:VISIBLE);
        volumeLabel.setText("Group volume");volumeLabel.setVisibility(value.groupVolume?VISIBLE:GONE);volumeSpacer.setVisibility(value.groupVolume?VISIBLE:GONE);
        int volumeHeight=theme.px(value.groupVolume?76:54);if(volumeBox.getLayoutParams().height!=volumeHeight){volumeBox.getLayoutParams().height=volumeHeight;volumeBox.requestLayout();}
        players.setText("Player");
        setIcon(play, "playing".equals(value.state) ? "pause" : "play", "playing".equals(value.state) ? "Pause" : "Play", "onPrimary");
        setIcon(favorite, value.favorite ? "heart" : "heart-outline", !value.favoriteKnown ? "Favourite" : value.favorite ? "Remove favourite" : "Add favourite", value.favorite ? "favorite" : "onSurface");
        setIcon(mute, value.silent ? "volume-off" : "volume-high", value.muted ? "Unmute" : "Mute", value.silent?"muted":"onSurface");
        NativeStyle.button(shuffle,theme,value.shuffle); NativeStyle.button(repeat,theme,!"off".equals(value.repeat));
        setIcon(shuffle, "shuffle", value.shuffle ? "Shuffle on" : "Shuffle off", value.shuffle ? "onPrimary" : "onSurfaceVariant");
        setIcon(repeat, "one".equals(value.repeat) ? "repeat-once" : "repeat", "Repeat " + value.repeat, "off".equals(value.repeat) ? "onSurfaceVariant" : "onPrimary");
        time.setText(value.live ? "● Live" : clock(value.elapsed) + " / " + clock(value.duration));
        progress.setVisibility(value.live ? GONE : VISIBLE);
        message.setText(""); message.setVisibility(GONE);
        if (!dragging) progress.setProgress(value.duration == 0 ? 0 : (int)Math.min(1000, (long)value.elapsed * 1000 / value.duration));
        int displayedVolume=value.muted?0:value.volume;
        if (!volumeDragging) volume.setProgress(displayedVolume);
        volumeValue.setText(displayedVolume + "%"); volume.setContentDescription(value.groupVolume ? "Group volume" : "Player volume");
        enable();
    }
    void artwork(Bitmap bitmap) {
        if(bitmap!=null){artwork.setImageBitmap(bitmap);return;}
        android.graphics.drawable.Drawable glyph=NativeStyle.icon(theme,"mdi:music-note","onSurfaceVariant");
        if(glyph==null){
            android.graphics.Path path=new android.graphics.Path();
            path.addOval(4,14,12,21,android.graphics.Path.Direction.CW);
            path.addRect(10,3,13,18,android.graphics.Path.Direction.CW);path.addRect(12,3,19,7,android.graphics.Path.Direction.CW);
            android.graphics.drawable.ShapeDrawable note=new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.PathShape(path,24,24));
            note.getPaint().setColor(NativeStyle.color("onSurfaceVariant"));glyph=note;
        }
        artwork.setImageDrawable(new android.graphics.drawable.InsetDrawable(glyph,.28f));
    }
    void position(int elapsed){
        if(data==null||data.live)return;
        int seconds=Math.max(0,data.duration>0?Math.min(data.duration,elapsed):elapsed);
        time.setText(clock(seconds)+" / "+clock(data.duration));
        if(!dragging)progress.setProgress(data.duration==0?0:(int)Math.min(1000,(long)seconds*1000/data.duration));
    }
    void message(String value) { message.setText(value); message.setVisibility(value.isEmpty() ? GONE : VISIBLE); }
    void busy(boolean value) { busy = value; enable(); }
    private void enable() {
        boolean ready = !busy && data != null;
        previous.setEnabled(ready && data.skip); next.setEnabled(ready && data.skip);
        play.setEnabled(ready && ("playing".equals(data.state) ? data.pause : data.canPlay));
        progress.setEnabled(ready && data.seek); volume.setEnabled(ready && data.volumeControl);
        mute.setEnabled(ready && data.muteControl); favorite.setEnabled(ready && data.favoriteControl);
        shuffle.setEnabled(ready && data.queueControl); repeat.setEnabled(ready && data.queueControl);
    }
    private static String clock(int seconds) { return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
}
