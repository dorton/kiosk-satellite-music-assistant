package net.markandrew.kiosk.music;
import android.widget.ImageView;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/** Scrollable native chooser/confirmation, also usable on short screens. */
final class TouchPanel extends LinearLayout {
    private final LinearLayout content;
    private final LinearLayout header, toolbar;
    private android.widget.FrameLayout eqPresetSlot;
    private final ScrollView scroll;
    private final Button close;
    private boolean compact,expandedEq;
    void eqPresentation(){
        if(expandedEq)return;expandedEq=true;
        // EQ is a bounded, weighted layout, not a scrollable chooser.
        scroll.removeView(content);int index=indexOfChild(scroll);removeView(scroll);
        content.setPadding(theme.px(8),0,theme.px(8),0);
        addView(content,index,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));requestLayout();
    }
    boolean expandedEq(){return expandedEq;}
    private final KsTheme theme;
    private final TextView status;
    private java.util.function.BooleanSupplier current = () -> true;
    private double lyricPosition;
    private boolean lyricPlaying;
    private Runnable interaction=()->{};
    private int volumeUpdates,volumeScrollX,volumeScrollY;
    private boolean restoreVolumeScroll;
    private boolean touching;
    private long lastTouch=-1000;
    private int liveStart=-1,liveTools;
    private android.widget.PopupWindow popup;
    private String liveError="";
    private Button liveRetry;
    void liveRetry(Runnable retry){
        if(liveRetry==null){liveRetry=new Button(getContext());NativeStyle.button(liveRetry,theme,false);liveRetry.setText("Retry");content.addView(liveRetry,0,buttonParams());}
        liveRetry.setVisibility(VISIBLE);liveRetry.setOnClickListener(v->{if(current.getAsBoolean())retry.run();});
    }
    void liveError(String text){
        if(status.getVisibility()!=VISIBLE||status.getText().length()==0||status.getText().toString().equals(liveError)){message(text);liveError=text;}
    }
    void liveRecovered(){if(liveRetry!=null)liveRetry.setVisibility(GONE);if(!liveError.isEmpty()&&status.getText().toString().equals(liveError))message("");}
    boolean interacting(){return touching||(popup!=null&&popup.isShowing())||android.os.SystemClock.elapsedRealtime()-lastTouch<500;}
    void preserveScroll(Runnable update){
        volumeScrollX=scroll.getScrollX();volumeScrollY=scroll.getScrollY();update.run();restoreVolumeScroll=true;requestLayout();
    }
    void liveContent(Runnable build){
        if(liveStart<0){liveStart=content.getChildCount();liveTools=toolbar.getChildCount();build.run();return;}
        volumeScrollX=scroll.getScrollX();volumeScrollY=scroll.getScrollY();
        if(popup!=null)popup.dismiss();
        while(content.getChildCount()>liveStart)content.removeViewAt(content.getChildCount()-1);
        while(toolbar.getChildCount()>liveTools)toolbar.removeViewAt(toolbar.getChildCount()-1);
        build.run();restoreVolumeScroll=true;requestLayout();
    }
    void contextMenu(android.view.View anchor,String[] labels,Runnable[] actions){
        if(!current.getAsBoolean()||!isAttachedToWindow())return;
        if(popup!=null)popup.dismiss();
        LinearLayout menu=new LinearLayout(getContext());menu.setOrientation(VERTICAL);menu.setPadding(theme.px(6),theme.px(6),theme.px(6),theme.px(6));menu.setBackground(NativeStyle.card(theme,false));
        android.widget.PopupWindow window=new android.widget.PopupWindow(menu,Math.min(theme.px(320),Math.max(theme.px(160),getWidth()-theme.px(24))),LayoutParams.WRAP_CONTENT,true);
        window.setBackgroundDrawable(NativeStyle.card(theme,false));window.setOutsideTouchable(true);window.setElevation(theme.px(12));popup=window;
        touching=false;
        window.setOnDismissListener(()->{touching=false;lastTouch=android.os.SystemClock.elapsedRealtime();});
        for(int i=0;i<labels.length;i++){final Runnable action=actions[i];Button item=new Button(getContext());NativeStyle.button(item,theme,false);item.setText(labels[i]);item.setEnabled(action!=null);
            menu.addView(item,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(48)));item.setOnClickListener(v->{window.dismiss();if(current.getAsBoolean()&&action!=null)action.run();});}
        window.showAsDropDown(anchor);
    }
    @Override protected void onDetachedFromWindow(){if(popup!=null)popup.dismiss();super.onDetachedFromWindow();}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        super.onLayout(changed,l,t,r,b);
        if(volumeUpdates>0||restoreVolumeScroll){scroll.scrollTo(volumeScrollX,volumeScrollY);if(volumeUpdates==0)restoreVolumeScroll=false;}
    }
    void interaction(Runnable handler){interaction=handler;}
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)touching=true;
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP||event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)touching=false;
        lastTouch=android.os.SystemClock.elapsedRealtime();
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN||event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE)interaction.run();
        return super.dispatchTouchEvent(event);
    }
    private long lyricAnchor;
    private long lyricsEpoch;
    private int lyricsStart=-1;
    private Button lyricsFollow;
    void clearLyrics(String note){
        lyricsEpoch++;scroll.setOnTouchListener(null);
        if(lyricsFollow!=null){toolbar.removeView(lyricsFollow);lyricsFollow=null;if(toolbar.getChildCount()==0)toolbar.setVisibility(GONE);}
        if(lyricsStart<0)lyricsStart=content.getChildCount();
        while(content.getChildCount()>lyricsStart)content.removeViewAt(content.getChildCount()-1);
        message("");scroll.scrollTo(0,0);if(!note.isEmpty())note(note);
    }
    void guard(java.util.function.BooleanSupplier current) { this.current = current; }
    TouchPanel(Context context, KsTheme theme, String heading) {
        super(context); this.theme = theme; setOrientation(VERTICAL); setBackground(NativeStyle.background());
        header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(theme.px(12),theme.px(4),theme.px(4),theme.px(4)); addView(header);
        TextView title = new TextView(context); title.setText(heading); NativeStyle.text(title, theme, 18, 600, "onSurface");
        title.setMaxLines(2); header.addView(title,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        close = new Button(context); NativeStyle.iconButton(close,theme,"close","Close panel",false);
        close.setContentDescription("Close panel"); close.setVisibility(GONE); header.addView(close,new LayoutParams(theme.px(44),theme.px(44)));
        toolbar = new LinearLayout(context); toolbar.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        toolbar.setPadding(theme.px(9),0,theme.px(9),0);toolbar.setVisibility(GONE); addView(toolbar);
        scroll = new ScrollView(context); addView(scroll,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.px(NativeStyle.EDGE), theme.px(NativeStyle.GAP), theme.px(NativeStyle.EDGE), theme.px(NativeStyle.GAP)); scroll.addView(content);
        status = new TextView(context); NativeStyle.text(status, theme, 14, 400, "onSurfaceVariant");
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(status);
        status.setVisibility(GONE);
    }
    void compact(Runnable dismiss) { compact=true; close.setVisibility(VISIBLE); close.setOnClickListener(v->dismiss.run()); }
    void tool(String icon, String label, boolean active, Runnable action) {
        toolbar.setVisibility(VISIBLE);
        Button b=new Button(getContext()); NativeStyle.button(b,theme,false); b.setContentDescription(label); b.setTooltipText(label);
        NativeStyle.button(b,theme,active);
        android.graphics.drawable.Drawable image=NativeStyle.icon(theme,"mdi:"+icon,active?"onPrimary":"onSurfaceVariant");
        if(image==null)b.setText(label); else b.setForeground(centeredIcon(image));
        b.setOnClickListener(v->{if(current.getAsBoolean())action.run();});
        LayoutParams slot=new LayoutParams(theme.px(44),theme.px(44));slot.setMargins(theme.px(3),theme.px(3),theme.px(5),theme.px(3));toolbar.addView(b,slot);
    }
    private android.graphics.drawable.Drawable centeredIcon(android.graphics.drawable.Drawable image) {
        // Foregrounds inherit the full button bounds. Fix the child's size independently
        // so weighted toolbar buttons cannot stretch the icon across their width.
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{image});
        layer.setLayerSize(0,theme.px(22),theme.px(22));
        layer.setLayerGravity(0,Gravity.CENTER);
        return layer;
    }
    void menu(String label,String[] labels,Runnable[] actions) {
        toolbar.setVisibility(VISIBLE); Button more=new Button(getContext()); NativeStyle.iconButton(more,theme,"dots-vertical",label,false);
        LayoutParams moreSlot=new LayoutParams(theme.px(44),theme.px(44));moreSlot.setMargins(theme.px(3),theme.px(3),theme.px(5),theme.px(3));toolbar.addView(more,moreSlot);
        // Keep overflow options in the same themed/native view hierarchy as the panel.
        LinearLayout options=new LinearLayout(getContext());options.setOrientation(VERTICAL);options.setVisibility(GONE);
        options.setBackground(NativeStyle.card(theme,false));content.addView(options,0);
        for(int i=0;i<labels.length;i++){
            final Runnable action=actions[i];Button option=new Button(getContext());NativeStyle.button(option,theme,false);option.setText(labels[i]);
            option.setOnClickListener(v->{if(current.getAsBoolean()){options.setVisibility(GONE);action.run();}});
            options.addView(option,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(48)));
        }
        more.setOnClickListener(v->{if(current.getAsBoolean()){options.setVisibility(options.getVisibility()==GONE?VISIBLE:GONE);scroll.scrollTo(0,0);}});
    }
    void section(String label) {
        TextView text=new TextView(getContext());text.setText(label);NativeStyle.text(text,theme,12,600,"onSurfaceVariant");
        text.setTag("section:"+label);text.setPadding(theme.px(4),theme.px(12),theme.px(4),theme.px(6));content.addView(text);
    }
    void disclosure(String label,java.util.function.Consumer<TouchPanel> build){
        Button toggle=new Button(getContext());NativeStyle.button(toggle,theme,false);toggle.setText(label+" ▸");toggle.setContentDescription(label);content.addView(toggle,buttonParams());
        LinearLayout body=new LinearLayout(getContext());body.setOrientation(VERTICAL);body.setVisibility(GONE);content.addView(body);
        final boolean[] built={false};
        toggle.setOnClickListener(v->{if(!current.getAsBoolean())return;
            if(!built[0]){int start=content.getChildCount();build.accept(this);while(content.getChildCount()>start){android.view.View child=content.getChildAt(start);content.removeViewAt(start);body.addView(child);}built[0]=true;}
            boolean expanded=body.getVisibility()!=VISIBLE;body.setVisibility(expanded?VISIBLE:GONE);toggle.setText(label+(expanded?" ▾":" ▸"));toggle.setSelected(expanded);
        });
    }
    void toggle(String label,boolean checked,java.util.function.Consumer<Boolean> change){
        android.widget.Switch value=new android.widget.Switch(getContext());value.setText(label);value.setContentDescription(label);NativeStyle.text(value,theme,14,400,"onSurface");value.setChecked(checked);
        value.setOnCheckedChangeListener((button,state)->{if(current.getAsBoolean())change.accept(state);});content.addView(value,buttonParams());
    }
    void draftInput(String label,String value,java.util.function.Consumer<String> change){
        android.widget.EditText field=input(label,value);field.setContentDescription(label);
        field.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(current.getAsBoolean())change.accept(s.toString());}
            public void afterTextChanged(android.text.Editable value){}
        });
    }
    void numberInput(String label,String value,java.util.function.Consumer<String> change){
        note(label);
        android.widget.EditText field=input(label,value);field.setContentDescription(label);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});
        field.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(current.getAsBoolean())change.accept(s.toString());}
            public void afterTextChanged(android.text.Editable value){}
        });
    }
    void liveNote(java.util.function.Supplier<String> text){
        TextView note=new TextView(getContext());NativeStyle.text(note,theme,14,400,"onSurfaceVariant");content.addView(note);note.setText(text.get());
        note.postDelayed(new Runnable(){public void run(){if(!isAttachedToWindow()||!current.getAsBoolean()||note.getParent()==null)return;note.setText(text.get());note.postDelayed(this,1000);}},1000);
    }
    void row(String title,String subtitle,String symbol,boolean selected,boolean enabled,Runnable action,Runnable options) {
        mediaRow(title,subtitle,symbol,selected,enabled,null,action,options);
    }
    LinearLayout memberCard(String name,String detail,boolean leader,Runnable remove){
        LinearLayout card=new LinearLayout(getContext());card.setOrientation(VERTICAL);
        card.setBackground(NativeStyle.card(theme,false));card.setPadding(theme.px(12),theme.px(8),theme.px(12),theme.px(8));
        card.setContentDescription("Player card: "+name);
        LayoutParams slot=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);slot.topMargin=theme.px(10);slot.bottomMargin=theme.px(4);content.addView(card,slot);
        LinearLayout heading=new LinearLayout(getContext());heading.setGravity(Gravity.CENTER_VERTICAL);card.addView(heading);
        LinearLayout texts=new LinearLayout(getContext());texts.setOrientation(VERTICAL);heading.addView(texts,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        TextView title=new TextView(getContext());title.setText(name);NativeStyle.text(title,theme,14,600,"onSurface");texts.addView(title);
        TextView subtitle=new TextView(getContext());subtitle.setTag("member-detail");subtitle.setText(detail);NativeStyle.text(subtitle,theme,12,400,"onSurfaceVariant");texts.addView(subtitle);
        if(remove!=null){Button button=new Button(getContext());NativeStyle.iconButton(button,theme,"minus","Remove "+name+" from group",false);
            heading.addView(button,new LayoutParams(theme.px(44),theme.px(44)));button.setOnClickListener(v->{if(current.getAsBoolean())remove.run();});}
        return card;
    }
    void memberDetail(LinearLayout card,String text){android.view.View detail=card.findViewWithTag("member-detail");if(detail instanceof TextView)((TextView)detail).setText(text);}
    void mediaRow(String title,String subtitle,String symbol,boolean selected,boolean enabled,
                  java.util.function.Consumer<java.util.function.Consumer<android.graphics.Bitmap>> load,Runnable action,Runnable options) {
        mediaRow(title,subtitle,symbol,selected,enabled,load,action,options,null,false);
    }
    void mediaRow(String title,String subtitle,String symbol,boolean selected,boolean enabled,
                   java.util.function.Consumer<java.util.function.Consumer<android.graphics.Bitmap>> load,Runnable action,Runnable options,Runnable remove,boolean removable) {
        mediaRowInternal(title,subtitle,symbol,selected,enabled,load,action,options==null?null:v->options.run(),remove,removable,false);
    }
    void queueRow(String title,String subtitle,String symbol,boolean selected,boolean enabled,
                   java.util.function.Consumer<java.util.function.Consumer<android.graphics.Bitmap>> load,Runnable action,java.util.function.Consumer<android.view.View> options,Runnable remove,boolean removable){
        mediaRowInternal(title,subtitle,symbol,selected,enabled,load,action,options,remove,removable,true);
    }
    void queueActionRow(String title,String subtitle,String symbol,boolean selected,boolean enabled,
                   java.util.function.Consumer<java.util.function.Consumer<android.graphics.Bitmap>> load,Runnable action,Runnable playlist,Runnable remove,boolean removable){
        mediaRowInternal(title,subtitle,symbol,selected,enabled,load,action,null,remove,removable,false);
        if(playlist!=null){
            LinearLayout row=(LinearLayout)content.getChildAt(content.getChildCount()-1);
            Button add=new Button(getContext());NativeStyle.iconButton(add,theme,"playlist-plus","Add to playlist: "+title,false);
            row.addView(add,1,new LayoutParams(theme.px(44),theme.px(44)));add.setEnabled(enabled);
            add.setOnClickListener(v->{if(current.getAsBoolean()&&enabled)playlist.run();});
        }
    }
    private void mediaRowInternal(String title,String subtitle,String symbol,boolean selected,boolean enabled,
                   java.util.function.Consumer<java.util.function.Consumer<android.graphics.Bitmap>> load,Runnable action,java.util.function.Consumer<android.view.View> options,Runnable remove,boolean removable,boolean hold){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(selected);
        row.setBackground(NativeStyle.card(theme,selected));
        row.setPadding(theme.px(8),theme.px(4),theme.px(4),theme.px(4));row.setMinimumHeight(theme.px(60));
        LayoutParams params=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);params.bottomMargin=theme.px(6);content.addView(row,params);
        LinearLayout main=new LinearLayout(getContext());main.setGravity(Gravity.CENTER_VERTICAL);main.setPadding(0,theme.px(4),theme.px(6),theme.px(4));
        row.addView(main,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        TextView mark=new TextView(getContext());mark.setText(symbol);NativeStyle.text(mark,theme,14,600,selected?"onPrimary":"onSurfaceVariant");
        main.addView(mark,new LayoutParams(theme.px(28),LayoutParams.WRAP_CONTENT));
        if(load!=null){ android.widget.ImageView art=new android.widget.ImageView(getContext());art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);art.setContentDescription("Artwork");
            LayoutParams ap=new LayoutParams(theme.px(40),theme.px(40));ap.rightMargin=theme.px(8);main.addView(art,ap);
            post(()->{if(current.getAsBoolean()&&isAttachedToWindow())load.accept(bitmap->post(()->{if(current.getAsBoolean()&&isAttachedToWindow())art.setImageBitmap(bitmap);}));}); }
        LinearLayout texts=new LinearLayout(getContext());texts.setOrientation(VERTICAL);main.addView(texts,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        TextView name=new TextView(getContext());name.setText(title);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        NativeStyle.text(name,theme,14,selected?600:500,selected?"onPrimaryContainer":"onSurface");texts.addView(name);
        if(!subtitle.isEmpty()){TextView sub=new TextView(getContext());sub.setText(subtitle);sub.setMaxLines(3);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);NativeStyle.text(sub,theme,12,400,selected?"onPrimary":"onSurfaceVariant");texts.addView(sub);}
        main.setMinimumHeight(theme.px(48));main.setEnabled(enabled);main.setAlpha(enabled||selected?1f:.4f);main.setContentDescription(title+(subtitle.isEmpty()?"":" · "+subtitle));
        main.setOnClickListener(v->{if(current.getAsBoolean()&&enabled)action.run();});
        if(hold&&options!=null){main.setLongClickable(true);main.setOnLongClickListener(v->{if(current.getAsBoolean())options.accept(v);return true;});}
        if(remove!=null){
            Button delete=new Button(getContext());NativeStyle.iconButton(delete,theme,"trash-can-outline","Remove from queue: "+title,false);
            delete.setEnabled(removable);delete.setAlpha(removable?1f:.35f);
            if(!removable)delete.setTooltipText("Current or MA committed-buffer item is protected");
            LayoutParams slot=new LayoutParams(theme.px(44),theme.px(44));slot.leftMargin=theme.px(6);row.addView(delete,slot);
            delete.setOnClickListener(v->{if(current.getAsBoolean()&&removable)remove.run();});
        }
        if(options!=null&&!hold){Button more=new Button(getContext());NativeStyle.iconButton(more,theme,"dots-vertical","Options: "+title,false);
            LayoutParams slot=new LayoutParams(theme.px(44),theme.px(44));slot.leftMargin=theme.px(6);row.addView(more,slot);more.setOnClickListener(v->{if(current.getAsBoolean())options.accept(v);});}
    }
    void focusCurrent() { content.post(()->{if(current.getAsBoolean())for(int i=0;i<content.getChildCount();i++)if(Boolean.TRUE.equals(content.getChildAt(i).getTag())){
        android.view.View first=content.getChildAt(i);
        if(i>0&&"section:Now playing".equals(content.getChildAt(i-1).getTag()))first=content.getChildAt(i-1);
        scroll.scrollTo(0,Math.max(0,first.getTop()-theme.px(4)));break;
    }}); }
    void message(String text) { liveError="";status.setText(text); status.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE); }
    void note(String text) {
        TextView note = new TextView(getContext()); note.setText(text);
        NativeStyle.text(note, theme, 14, 400, "onSurfaceVariant");
        note.setPadding(theme.px(8), theme.px(8), theme.px(8), theme.px(8)); content.addView(note);
    }
    void picker(String label, java.util.List<DiscoveryPolicy.Choice> choices, String selected,
                 java.util.function.Consumer<String> choose) {
        picker(label,choices,selected,choose,false);
    }
    void picker(String label, java.util.List<DiscoveryPolicy.Choice> choices, String selected,
                  java.util.function.Consumer<String> choose,boolean separateLabel) {
        picker(label,choices,selected,choose,separateLabel,true);
    }
    void simplePicker(String label,java.util.List<DiscoveryPolicy.Choice> choices,String selected,java.util.function.Consumer<String> choose){
        picker(label,choices,selected,choose,false,false);
    }
    private void picker(String label,java.util.List<DiscoveryPolicy.Choice> choices,String selected,java.util.function.Consumer<String> choose,boolean separateLabel,boolean searchable){
        if(separateLabel)note(label);
        Button toggle = new Button(getContext()); NativeStyle.button(toggle, theme, false);
        toggle.setContentDescription(label);
        String chosen = selected;
        for (DiscoveryPolicy.Choice choice : choices) if (choice.id.equals(selected)) chosen = choice.label;
        toggle.setText((separateLabel?"":label+": ") + (chosen.isEmpty() ? "Choose…" : chosen) + " ▾");
        content.addView(toggle, buttonParams());
        LinearLayout dropdown = new LinearLayout(getContext()); dropdown.setOrientation(LinearLayout.VERTICAL);
        dropdown.setVisibility(GONE); content.addView(dropdown);
        android.widget.EditText filter = searchable?new android.widget.EditText(getContext()):null;
        if(filter!=null){filter.setSingleLine(true);
        filter.setHint("Search " + label.toLowerCase(java.util.Locale.ROOT)); NativeStyle.input(filter, theme);
        dropdown.addView(filter, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, theme.px(48)));}
        LinearLayout options = new LinearLayout(getContext()); options.setOrientation(LinearLayout.VERTICAL); dropdown.addView(options);
        Runnable render = () -> {
            options.removeAllViews(); String query = filter==null?"":filter.getText().toString().toLowerCase(java.util.Locale.ROOT);
            int count = 0;
            for (DiscoveryPolicy.Choice choice : choices) {
                if (!choice.label.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                if (count++ >= 100) break;
                Button option = new Button(getContext()); option.setText(choice.label); NativeStyle.button(option, theme, false);
                option.setOnClickListener(v -> {
                    if (!current.getAsBoolean()) return;
                    choose.accept(choice.id); toggle.setText((separateLabel?"":label+": ") + choice.label + " ▾");
                    dropdown.setVisibility(GONE);if(filter!=null){filter.clearFocus();
                    android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) keyboard.hideSoftInputFromWindow(filter.getWindowToken(), 0);}
                });
                options.addView(option, buttonParams());
            }
            if (count == 0 || count > 100) {
                TextView note = new TextView(getContext()); note.setText(count == 0 ? "No matching choices" : "Search to narrow these results");
                NativeStyle.text(note, theme, 14, 400, "onSurfaceVariant"); options.addView(note);
            }
        };
        if(filter!=null)filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            public void afterTextChanged(android.text.Editable value) {}
        });
        toggle.setOnClickListener(v -> { if (current.getAsBoolean()) { dropdown.setVisibility(dropdown.getVisibility() == GONE ? VISIBLE : GONE); render.run(); } });
    }
    void add(String label, Runnable action) {
        Button button = new Button(getContext()); button.setText(label); NativeStyle.button(button, theme, false);
        if(compact)button.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        button.setOnClickListener(v -> { if (!current.getAsBoolean()) return; button.setEnabled(false); try { action.run(); } finally { button.postDelayed(() -> button.setEnabled(true), 800); } });
        content.addView(button, buttonParams());
    }
    void navigation(String icon,String label,Runnable action){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image=new ImageView(getContext());image.setImageDrawable(NativeStyle.icon(theme,"mdi:"+icon,"onSurfaceVariant"));
        row.addView(image,new LayoutParams(theme.px(24),theme.px(24)));
        TextView text=new TextView(getContext());text.setText(label);NativeStyle.text(text,theme,14,500,"onSurface");text.setPadding(theme.px(12),0,0,0);
        row.addView(text,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        TextView arrow=new TextView(getContext());arrow.setText("›");NativeStyle.text(arrow,theme,22,500,"onSurfaceVariant");row.addView(arrow);
        row.setPadding(theme.px(12),0,theme.px(12),0);row.setBackground(NativeStyle.card(theme,false));row.setContentDescription(label);row.setFocusable(true);row.setClickable(true);
        row.setOnClickListener(v->{if(current.getAsBoolean())action.run();});content.addView(row,buttonParams());
    }
    void closeAction(String label, Runnable action) {
        if(compact){NativeStyle.iconButton(close,theme,label.startsWith("‹ Back")?"arrow-left":"close",label,false);close.setContentDescription(label);close.setTooltipText(label);close.setOnClickListener(v->{if(current.getAsBoolean())action.run();});return;}
        Button button = new Button(getContext()); button.setText(label); NativeStyle.button(button, theme, false);
        button.setOnClickListener(v -> action.run()); content.addView(button, buttonParams());
    }
    void formActions(String label,Runnable save,Runnable discard){
        formActions(label,save,"Close",discard);
    }
    void formActions(String label,Runnable save,String closeLabel,Runnable discard){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.END);
        content.addView(row,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        for(int i=0;i<2;i++){
            final Runnable action=i==0?save:discard;Button button=new Button(getContext());
            NativeStyle.button(button,theme,i==0);button.setText(i==0?label:closeLabel);
            LayoutParams slot=new LayoutParams(0,theme.px(NativeStyle.TARGET),1);slot.setMargins(theme.px(4),theme.px(8),theme.px(4),0);row.addView(button,slot);
            button.setOnClickListener(v->{if(current.getAsBoolean())action.run();});
        }
    }
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, theme.px(NativeStyle.TARGET));
        p.topMargin = theme.px(NativeStyle.SMALL); return p;
    }
    void menuSpacing(){
        // Apply after population, including player rows, without affecting EQ.
        post(()->{for(int i=0;i<content.getChildCount();i++){
            android.view.View child=content.getChildAt(i);
            if(child.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();p.topMargin=theme.px(8);child.setLayoutParams(p);}
        }});
    }
    android.widget.EditText input(String hint, String value) {
        android.widget.EditText field = new android.widget.EditText(getContext()); field.setSingleLine(true);
        field.setHint(hint); field.setText(value); NativeStyle.input(field, theme);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        field.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){interaction.run();}
            public void afterTextChanged(android.text.Editable value){}
        });
        content.addView(field, buttonParams()); return field;
    }
    void slider(String label, int value, java.util.function.IntConsumer change) {
        TextView caption=new TextView(getContext());caption.setText(label+" · "+value+"%");NativeStyle.text(caption,theme,12,400,"onSurfaceVariant");content.addView(caption);
        android.widget.SeekBar bar = new android.widget.SeekBar(getContext());NativeStyle.slider(bar,theme);
        bar.setMax(100); bar.setProgress(Math.max(0, Math.min(100, value))); content.addView(bar,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(40)));
        bar.setContentDescription(label);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {if(user)caption.setText(label+" · "+p+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar b) {}
            public void onStopTrackingTouch(android.widget.SeekBar b) { if (current.getAsBoolean()) change.accept(b.getProgress()); }
        });
    }
    void boundedSlider(String label,int minimum,int maximum,int value,java.util.function.IntConsumer change){
        int initial=Math.max(minimum,Math.min(maximum,value));
        TextView caption=new TextView(getContext());caption.setText(label+" · "+initial+"%");NativeStyle.text(caption,theme,14,400,"onSurfaceVariant");content.addView(caption);
        android.widget.SeekBar bar=new android.widget.SeekBar(getContext());NativeStyle.slider(bar,theme);bar.setMax(maximum-minimum);bar.setProgress(initial-minimum);bar.setContentDescription(label);
        content.addView(bar,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(TouchLayout.TARGET)));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar b,int progress,boolean user){caption.setText(label+" · "+(minimum+progress)+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar b){}
            public void onStopTrackingTouch(android.widget.SeekBar b){if(current.getAsBoolean())change.accept(minimum+b.getProgress());}
        });
    }
    void eqHeader(boolean enabled,boolean advanced,java.util.function.Consumer<Boolean> toggle,Runnable switchView){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);content.addView(row);
        android.widget.Switch on=new android.widget.Switch(getContext());on.setText("Enabled");on.setContentDescription("EQ / DSP enabled");NativeStyle.text(on,theme,14,400,"onSurface");on.setChecked(enabled);
        on.setOnCheckedChangeListener((b,value)->{if(current.getAsBoolean())toggle.accept(value);});row.addView(on,new LayoutParams(LayoutParams.WRAP_CONTENT,theme.px(48)));
        eqPresetSlot=new android.widget.FrameLayout(getContext());LayoutParams presetSlot=new LayoutParams(0,theme.px(48),1);presetSlot.leftMargin=theme.px(12);presetSlot.rightMargin=theme.px(12);row.addView(eqPresetSlot,presetSlot);
        Button mode=new Button(getContext());NativeStyle.button(mode,theme,false);mode.setText(advanced?"Advanced ▾":"Basic ▾");mode.setContentDescription(advanced?"Switch to Basic":"Switch to Advanced");
        mode.setOnClickListener(v->{if(current.getAsBoolean())switchView.run();});row.addView(mode,new LayoutParams(LayoutParams.WRAP_CONTENT,theme.px(48)));
    }
    void eqActions(Runnable apply,Runnable cancel){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.END);row.setPadding(theme.px(8),0,theme.px(8),theme.px(4));addView(row,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        for(int i=0;i<2;i++){final Runnable action=i==0?apply:cancel;Button b=new Button(getContext());NativeStyle.button(b,theme,i==0);b.setText(i==0?"Apply":"Close");LayoutParams slot=new LayoutParams(LayoutParams.WRAP_CONTENT,theme.px(48));slot.setMargins(theme.px(4),0,0,0);row.addView(b,slot);b.setOnClickListener(v->{if(current.getAsBoolean())action.run();});}
    }
    void eqSliders(String[] labels,double[] values,java.util.function.BiConsumer<Integer,Double> change){
        content.addView(new GraphicEqView(getContext(),theme,labels,values,change,current),new LayoutParams(LayoutParams.MATCH_PARENT,0,1));
    }
    Button eqChoice(String selected,String[] names,java.util.function.Consumer<String> choose){
        Button toggle=new Button(getContext());NativeStyle.button(toggle,theme,false);toggle.setText(selected+" ▾");toggle.setContentDescription("Preset selector");toggle.setSingleLine(true);toggle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(eqPresetSlot!=null)eqPresetSlot.addView(toggle,new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        else content.addView(toggle,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(48)));
        toggle.setOnClickListener(v->{if(!current.getAsBoolean()||!isAttachedToWindow())return;if(popup!=null)popup.dismiss();
            ScrollView list=new ScrollView(getContext());LinearLayout choices=new LinearLayout(getContext());choices.setOrientation(VERTICAL);list.addView(choices);
            android.widget.PopupWindow menu=new android.widget.PopupWindow(list,Math.min(theme.px(320),Math.max(theme.px(180),getWidth()-theme.px(24))),Math.min(theme.px(360),Math.max(theme.px(120),getResources().getDisplayMetrics().heightPixels-theme.px(100))),true);
            menu.setBackgroundDrawable(NativeStyle.card(theme,false));menu.setOutsideTouchable(true);menu.setElevation(theme.px(12));popup=menu;
            for(String name:names){Button option=new Button(getContext());NativeStyle.button(option,theme,false);option.setText(name);choices.addView(option,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(48)));
                option.setOnClickListener(w->{menu.dismiss();if(current.getAsBoolean())choose.accept(name);});}
            menu.showAsDropDown(toggle);
        });return toggle;
    }
    void eqSlider(String label,double value,java.util.function.DoubleConsumer change){
        TextView caption=new TextView(getContext());NativeStyle.text(caption,theme,14,400,"onSurfaceVariant");content.addView(caption);
        caption.setText(label+" · "+value+" dB");
        android.widget.SeekBar bar=new android.widget.SeekBar(getContext());NativeStyle.slider(bar,theme);
        bar.setMax(240);bar.setProgress((int)Math.round((Math.max(-12,Math.min(12,value))+12)*10));bar.setContentDescription(label+" · dB");
        content.addView(bar,new LayoutParams(LayoutParams.MATCH_PARENT,theme.px(TouchLayout.TARGET)));
        if(value < -12 || value > 12)note("Existing "+label+" is outside the quick slider range (−12 to +12 dB); preserved until you move it.");
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar b,int p,boolean user){if(user)caption.setText(label+" · "+String.format(java.util.Locale.ROOT,"%.1f",p/10.0-12)+" dB");}
            public void onStartTrackingTouch(android.widget.SeekBar b){}
            public void onStopTrackingTouch(android.widget.SeekBar b){if(current.getAsBoolean())change.accept(Math.round((b.getProgress()/10.0-12)*10)/10.0);}
        });
    }
    interface VolumeControl { void update(int level,boolean muted); default void update(int level,boolean muted,boolean silent){update(level,muted);} void busy(boolean value); boolean muted(); }
    VolumeControl volume(String label,int value,boolean muted,boolean adjustable,boolean canMute,java.util.function.IntConsumer change,Runnable toggleMute) {
        return volumeIn(null,label,value,muted,adjustable,canMute,change,toggleMute);
    }
    VolumeControl volumeIn(LinearLayout card,String label,int value,boolean muted,boolean adjustable,boolean canMute,java.util.function.IntConsumer change,Runnable toggleMute) {
        LinearLayout parent=card==null?content:card;
        if(card==null){TextView caption=new TextView(getContext());caption.setText(label);NativeStyle.text(caption,theme,12,400,"onSurfaceVariant");parent.addView(caption);}
        LinearLayout line=new LinearLayout(getContext());line.setGravity(Gravity.CENTER_VERTICAL);parent.addView(line);
        Button mute=new Button(getContext());NativeStyle.button(mute,theme,false);
        boolean silent=muted||value==0;
        android.graphics.drawable.Drawable glyph=NativeStyle.icon(theme,silent?"mdi:volume-off":"mdi:volume-high",silent?"muted":"onSurface");mute.setTextColor(NativeStyle.color(silent?"muted":"onSurface"));
        if(glyph==null)mute.setText(muted?"Unmute":"Mute");else mute.setForeground(centeredIcon(glyph));
        mute.setContentDescription((muted?"Unmute ":"Mute ")+label);
        mute.setEnabled(canMute);mute.setOnClickListener(v->{if(current.getAsBoolean())toggleMute.run();});line.addView(mute,new LayoutParams(theme.px(44),theme.px(44)));
        TextView percent=new TextView(getContext());percent.setText((muted?0:value)+"%");NativeStyle.text(percent,theme,12,400,"onSurfaceVariant");
        android.widget.SeekBar bar=new android.widget.SeekBar(getContext());NativeStyle.volumeSlider(bar,theme);bar.setMax(100);bar.setProgress(muted?0:value);bar.setEnabled(adjustable);bar.setContentDescription(label);
        LayoutParams volumeSlot=new LayoutParams(0,theme.px(44),1);volumeSlot.leftMargin=theme.px(8);volumeSlot.rightMargin=theme.px(4);line.addView(bar,volumeSlot);line.addView(percent,new LayoutParams(theme.px(40),LayoutParams.WRAP_CONTENT));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar b,int p,boolean user){if(user)percent.setText(p+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar b){}
            public void onStopTrackingTouch(android.widget.SeekBar b){if(current.getAsBoolean()&&adjustable)change.accept(b.getProgress());}
        });
        return new VolumeControl(){
            private boolean silenced=muted;
            private boolean pending;
            public boolean muted(){return silenced;}
            public void busy(boolean value){
                if(value&&!pending){if(volumeUpdates++==0){volumeScrollX=scroll.getScrollX();volumeScrollY=scroll.getScrollY();}pending=true;}
                bar.setEnabled(!value&&adjustable);mute.setEnabled(!value&&canMute);
                if(!value&&pending){pending=false;if(--volumeUpdates==0){restoreVolumeScroll=true;scroll.scrollTo(volumeScrollX,volumeScrollY);requestLayout();}}
            }
            public void update(int level,boolean state){
                update(level,state,state||level==0);
            }
            public void update(int level,boolean state,boolean silent){
                if(bar.isPressed())return;
                silenced=state;bar.setProgress(state?0:Math.max(0,Math.min(100,level)));percent.setText(bar.getProgress()+"%");
                android.graphics.drawable.Drawable icon=NativeStyle.icon(theme,silent?"mdi:volume-off":"mdi:volume-high",silent?"muted":"onSurface");mute.setTextColor(NativeStyle.color(silent?"muted":"onSurface"));
                if(icon==null)mute.setText(state?"Unmute":"Mute");else mute.setForeground(centeredIcon(icon));
                mute.setContentDescription((state?"Unmute ":"Mute ")+label);
            }
        };
    }
    void every(long interval, Runnable action) {
        postDelayed(new Runnable() { public void run() {
            if (!isAttachedToWindow() || !current.getAsBoolean()) return;
            action.run(); postDelayed(this, interval);
        } }, interval);
    }
    void lyricPosition(double seconds, boolean playing) { lyricPosition = seconds; lyricPlaying = playing; lyricAnchor = android.os.SystemClock.elapsedRealtime(); }
    void lyrics(LyricsData lyrics, double seconds, boolean playing) {
        if(lyricsStart<0)lyricsStart=content.getChildCount();
        final long epoch=++lyricsEpoch;
        lyricPosition(seconds, playing);
        if (lyrics.lines.isEmpty()) { note(lyrics.plain.isEmpty() ? "Lyrics unavailable from your MA providers." : lyrics.plain); return; }
        final boolean[] follow = {true}; final int[] active = {-2};
        tool("crosshairs-gps","Follow playback",true, () -> { follow[0] = true; active[0] = -2; });
        lyricsFollow=(Button)toolbar.getChildAt(toolbar.getChildCount()-1);
        java.util.List<TextView> views = new java.util.ArrayList<>();
        for (LyricsData.Line line : lyrics.lines) {
            TextView text = new TextView(getContext()); text.setText(line.text.isEmpty() ? "♪" : line.text);
            NativeStyle.text(text, theme, 17, 400, "onSurfaceVariant"); text.setPadding(theme.px(8), theme.px(8), theme.px(8), theme.px(8));
            content.addView(text); views.add(text);
        }
        scroll.setOnTouchListener((v, event) -> { if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN || event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) follow[0] = false; return false; });
        postDelayed(new Runnable(){public void run(){
            if(epoch!=lyricsEpoch||!isAttachedToWindow()||!current.getAsBoolean())return;
            postDelayed(this,500);
            int index = lyrics.index(lyricPosition + (lyricPlaying ? (android.os.SystemClock.elapsedRealtime() - lyricAnchor) / 1000.0 : 0));
            if (index == active[0]) return; active[0] = index;
            for (int i = 0; i < views.size(); i++) NativeStyle.text(views.get(i), theme, 17, i == index ? 600 : 400, i == index ? "primary" : "onSurfaceVariant");
            if (follow[0] && index >= 0) scroll.smoothScrollTo(0, Math.max(0, views.get(index).getTop() - scroll.getHeight() / 2));
        }},500);
    }
}
