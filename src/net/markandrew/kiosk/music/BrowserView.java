package net.markandrew.kiosk.music;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import java.util.LinkedHashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/** Native Android views sharing the Echo overview visual style. */
final class BrowserView extends LinearLayout {
    interface Actions {
        void browse(String category, int offset);
        void search(String query);
        void clearSearch();
        void retry();
        void close();
        void open(BrowserItem item);
        void menu(BrowserItem item);
        default void menu(BrowserItem item,View anchor){menu(item);}
        default void playbackMenu(BrowserItem item,View anchor){menu(item,anchor);}
        void back();
        void play(BrowserItem item, boolean enqueue);
        void playOption(BrowserItem item, String option);
        void players();
        void setup();
        void nowPlaying();
        default void createPlaylist() {}
        default void joinGroup() {}
        default void playerSettings() {}
        default void librarySettings() {}
        void filters();
        void more();
        default void interaction() {}
        default void favorite(BrowserItem item,java.util.function.Consumer<Boolean> done,java.util.function.Consumer<String> fail){}
        void artwork(BrowserItem item, java.util.function.BooleanSupplier visible, java.util.function.Consumer<android.graphics.Bitmap> done);
    }
    private KsTheme theme;
    private String playbackPlayer="";
    private AnchoredItemMenu anchoredMenu;
    void playbackPlayer(String name){playbackPlayer=name;}
    void dismissItemMenu(){if(anchoredMenu!=null){anchoredMenu.dismiss();anchoredMenu=null;}}
    boolean itemMenuShowing(){return anchoredMenu!=null&&anchoredMenu.popup.isShowing();}
    void anchoredActions(View anchor,java.util.List<FeatureController.ItemAction> items,java.util.function.BooleanSupplier current,boolean playback){
        dismissItemMenu();anchoredMenu=new AnchoredItemMenu(anchor,theme,items,current,playback?playbackPlayer:null);anchoredMenu.show();
    }
    private Button albumPlay(BrowserItem item){
        Button play=button("",()->{if(!sending)actions.playOption(item,"replace");});NativeStyle.iconButton(play,theme,"play","Play album: "+item.name+" · replace queue",false);if(play.getForeground()==null)play.setText("▶");play.setEnabled(!sending&&item.playable());smallAlbumIcon(play);NativeStyle.button(play,theme,false);return play;
    }
    private void smallAlbumIcon(Button button){
        if(button.getForeground() instanceof android.graphics.drawable.LayerDrawable){android.graphics.drawable.LayerDrawable glyph=(android.graphics.drawable.LayerDrawable)button.getForeground();glyph.setLayerSize(0,theme.px(18),theme.px(18));}
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),null));
        button.setPadding(0,0,0,0);button.setTextSize(16);
    }
    @Override protected void onDetachedFromWindow(){dismissItemMenu();super.onDetachedFromWindow();}
    private final Actions actions;
    private final TextView title, status;
    private final EditText query;
    private final LinearLayout rows;
    private final LinearLayout heading, searchControls;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final android.widget.FrameLayout loading;
    private static final int RAIL_WIDTH=142;
    private final Button search, close, clear;
    void returnLabel(String label){close.setText(label.isEmpty()?"Return":label);close.setContentDescription(label.isEmpty()?"Return · checking player state":label);}
    private final Map<String, Button> tabs = new LinkedHashMap<>();
    private volatile java.util.Set<String> visibleTabs=new java.util.LinkedHashSet<>(LibraryTabs.KEYS);
    private String tabsError="";
    void libraryTabs(java.util.Set<String> keys){visibleTabs=new java.util.LinkedHashSet<>(keys);for(Map.Entry<String,Button> tab:tabs.entrySet())tab.getValue().setVisibility(keys.contains(tab.getKey())?VISIBLE:GONE);}
    String visibleCategory(String key){return LibraryTabs.KEYS.contains(key)&&!visibleTabs.contains(key)?LibraryTabs.KEYS.stream().filter(visibleTabs::contains).findFirst().orElse("albums"):key;}
    private BrowserPage page = BrowserPage.status("Music", "Choose a library category.", false, false);
    private String category = "albums";
    private boolean sending, noticeError;
    private volatile long renderVersion;
    private final Map<BrowserItem,Boolean> favorites=new java.util.WeakHashMap<>();
    private Button favoriteButton(BrowserItem item,long version){
        Button heart=new Button(getContext());boolean active=favorites.containsKey(item)?favorites.get(item):item.favorite;
        styleHeart(heart,active,item.name);heart.setEnabled(item.available&&!item.uri.isEmpty()&&!sending);
        heart.setOnClickListener(v->{
            if(sending)return;heart.setEnabled(false);
            actions.favorite(item,state->heart.post(()->{
                favorites.put(item,state);if(version==renderVersion){styleHeart(heart,state,item.name);heart.setEnabled(true);}
            }),error->heart.post(()->{if(version==renderVersion){heart.setEnabled(true);message(error);}}));
        });return heart;
    }
    private void styleHeart(Button button,boolean active,String name){
        NativeStyle.iconButton(button,theme,active?"heart":"heart-outline",(active?"Remove favourite: ":"Add favourite: ")+name,false);
        android.graphics.drawable.Drawable icon=NativeStyle.icon(theme,"mdi:"+(active?"heart":"heart-outline"),active?"favorite":"onSurfaceVariant");
        if(icon!=null){android.graphics.drawable.LayerDrawable layer=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{icon});layer.setLayerSize(0,theme.px(22),theme.px(22));layer.setLayerGravity(0,Gravity.CENTER);button.setForeground(layer);}
        else{button.setText(active?"♥":"♡");button.setTextColor(NativeStyle.color(active?"favorite":"onSurfaceVariant"));}
        if("album-controls".equals(button.getTag()))smallAlbumIcon(button);
    }
    private int layoutWidth = -1;
    private int layoutHeight = -1;
    private int layoutColumns = 4;
    private final java.util.List<ArtBinding> artBindings = new java.util.ArrayList<>();
    private final class ArtBinding {
        final BrowserItem item; final ImageView image; final long version;
        volatile boolean visible;
        boolean requested, loaded;
        ArtBinding(BrowserItem item, ImageView image, long version) { this.item = item; this.image = image; this.version = version; }
    }
    private void artwork(BrowserItem item, ImageView image, long version) { artBindings.add(new ArtBinding(item, image, version)); }
    private void updateArtwork() {
        android.graphics.Rect rect = new android.graphics.Rect();
        for (ArtBinding binding : artBindings) {
            boolean visible = binding.image.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0;
            binding.visible = visible;
            if (!visible) {
                binding.requested = false;
                if (binding.loaded) { binding.image.setImageDrawable(null); binding.loaded = false; }
            } else if (!binding.requested) {
                binding.requested = true;
                actions.artwork(binding.item, () -> binding.visible && renderVersion == binding.version, bitmap -> binding.image.post(() -> {
                    if (renderVersion == binding.version && binding.visible) { binding.image.setImageBitmap(bitmap); binding.loaded = true; }
                }));
            }
        }
    }

    BrowserView(Context context, KsTheme theme, Actions actions) {
        super(context);
        this.actions = actions; this.theme = theme;
        try{visibleTabs=new LibraryTabs(context).visible;}catch(Exception error){tabsError="Library tab choices could not be read. All tabs are shown; saved choices were not overwritten.";}
        setOrientation(VERTICAL);
        setPadding(theme.px(NativeStyle.EDGE), theme.px(NativeStyle.GAP), theme.px(NativeStyle.EDGE), 0);
        setFocusableInTouchMode(true);
        heading = row(); searchControls = row();
        title = new TextView(context); title.setText("Library");
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout navigation = row();navigation.setOrientation(VERTICAL);
        close=button("Return",()->{if(!sending)actions.close();});compact(close,false);navigation.addView(close);
        Button player=button("Player",()->{if(!sending)actions.players();});compact(player,false);
        player.setContentDescription("Player");navigation.addView(player,spaced(LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));
        navigation.addView(railSection("Library"));
        for (String key : LibraryTabs.KEYS) {
            String label = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            Button tab = button(label, () -> { category = key; actions.browse(key, 0); });
            tabs.put(key, tab); navigation.addView(tab, spaced(LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));
        }
        libraryTabs(visibleTabs);
        Button filters = button("Filter / Sort", () -> actions.filters()); compact(filters, false);
        navigation.addView(filters, spaced(LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));
        navigation.addView(railSection("Settings"));
        Button preferences=button("Plugin",()->{if(!sending)actions.setup();});compact(preferences,false);navigation.addView(preferences);
        Button librarySettings=button("Library",()->{if(!sending)actions.librarySettings();});compact(librarySettings,false);navigation.addView(librarySettings);
        ScrollView chips = new ScrollView(context); chips.setVerticalScrollBarEnabled(true);chips.setScrollbarFadingEnabled(false);chips.setContentDescription("Library navigation · scroll for more");
        chips.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);chips.setScrollBarSize(theme.px(3));chips.setPadding(0,0,theme.px(8),theme.px(4));chips.setClipToPadding(true);chips.setClipChildren(true);chips.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chips.setFillViewport(true);
        chips.addView(navigation, new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for(int i=0;i<navigation.getChildCount();i++){
            View entry=navigation.getChildAt(i);LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,theme.px(entry instanceof Button?TouchLayout.TARGET:32));if(entry instanceof Button)slot.bottomMargin=theme.px(8);entry.setLayoutParams(slot);
            if(entry instanceof Button){((Button)entry).setGravity(Gravity.START|Gravity.CENTER_VERTICAL);((Button)entry).setTextSize(13);}
        }
        query = new EditText(context); query.setSingleLine(true); query.setHint("Search music");
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setMaxLines(1);
        query.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(200)});
        query.setMinimumHeight(0); query.setMinHeight(0);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit(); return true;
        });
        query.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); query.setIncludeFontPadding(false);
        searchControls.addView(query, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        clear=button("×",()->{
            if(sending)return;
            clearSearch();actions.clearSearch();
        });
        NativeStyle.iconButton(clear,theme,"close","Clear search",false);clear.setVisibility(GONE);
        searchControls.addView(clear,spaced(44,TouchLayout.TARGET));
        query.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){actions.interaction();clear.setVisibility(s.length()>0||category.equals("search")?VISIBLE:GONE);}
            public void afterTextChanged(android.text.Editable value){}
        });
        search = button("Search", this::submit);
        LinearLayout.LayoutParams searchButton = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        searchButton.leftMargin = theme.px(NativeStyle.GAP); searchControls.addView(search, searchButton);
        title.setOnClickListener(v -> actions.browse(visibleCategory("albums"), 0)); title.setContentDescription("Library home");
        addView(heading, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.px(TouchLayout.TARGET)));
        arrangeHeader(640);
        android.widget.FrameLayout stage=new android.widget.FrameLayout(context);LinearLayout.LayoutParams stageSlot=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,0,1);stageSlot.topMargin=theme.px(4);addView(stage,stageSlot);
        body=row();body.setGravity(Gravity.TOP);stage.addView(body,new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(theme.px(RAIL_WIDTH),LayoutParams.MATCH_PARENT);
        chipParams.rightMargin=theme.px(NativeStyle.GAP);body.addView(chips,chipParams);
        LinearLayout main=new LinearLayout(context);main.setOrientation(VERTICAL);body.addView(main,new LinearLayout.LayoutParams(0,LayoutParams.MATCH_PARENT,1));
        status = new TextView(context); status.setPadding(0, theme.px(2), 0, theme.px(2));
        status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); main.addView(status);
        scroll = new ScrollView(context); scroll.setFillViewport(true);
        scroll.setContentDescription("Library music grid");scroll.setVerticalScrollBarEnabled(true);scroll.setScrollbarFadingEnabled(false);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);scroll.setScrollBarSize(theme.px(3));scroll.setPadding(0,0,theme.px(8),theme.px(4));
        scroll.setClipToPadding(true);scroll.setClipChildren(true);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateArtwork());
        rows = new LinearLayout(context); rows.setOrientation(VERTICAL);
        rows.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateArtwork());
        scroll.addView(rows); main.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        loading=new android.widget.FrameLayout(context);loading.setBackgroundColor(0x55000000);loading.setClickable(true);loading.setContentDescription("Loading library");
        LinearLayout loadingCard=row();loadingCard.setPadding(theme.px(24),theme.px(16),theme.px(24),theme.px(16));loadingCard.setBackground(NativeStyle.card(theme,false));
        android.widget.ProgressBar spinner=new android.widget.ProgressBar(context);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(NativeStyle.color("primary")));loadingCard.addView(spinner,new LinearLayout.LayoutParams(theme.px(32),theme.px(32)));
        TextView loadingText=new TextView(context);loadingText.setText("Loading…");NativeStyle.text(loadingText,theme,16,500,"onSurface");loadingText.setPadding(theme.px(16),0,0,0);loadingCard.addView(loadingText);
        loading.addView(loadingCard,new android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT,Gravity.CENTER));
        stage.addView(loading,new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));loading.setVisibility(GONE);
        style(theme);
        if(!tabsError.isEmpty())message(tabsError);
        // Opening the browser must not automatically raise the keyboard.
        requestFocus();
    }

    private void submit() {
        String value = query.getText().toString().trim();
        if (value.isEmpty()) return;
        query.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        category = "search"; actions.search(value);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN||event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE)actions.interaction();
        return super.dispatchTouchEvent(event);
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(getContext()); result.setOrientation(HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL); return result;
    }
    private TextView railSection(String title){TextView label=new TextView(getContext());label.setText(title);NativeStyle.text(label,theme,11,600,"onSurfaceVariant");label.setPadding(theme.px(12),theme.px(10),0,0);return label;}
    private Button button(String label, Runnable action) {
        Button button = new Button(getContext()); button.setText(label);
        button.setOnClickListener(view -> {
            try { action.run(); }
            catch (RuntimeException | LinkageError ignored) { message("Kiosk action unavailable. Close and reopen Music Assistant."); }
        });
        return button;
    }

    void style(KsTheme theme) {
        this.theme = theme;
        setBackground(NativeStyle.background());
        NativeStyle.text(title, theme, 18, 600, "onSurface"); title.setGravity(Gravity.CENTER_VERTICAL);
        NativeStyle.text(status, theme, 12, 400, page.error ? "error" : "onSurfaceVariant");
        NativeStyle.input(query, theme);
        compact(search, true);
        compact(close, false);
        close.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);close.setTextSize(13);
        NativeStyle.iconButton(clear,theme,"close","Clear search",false);
        render(page, false);
    }

    void setCategory(String category) { this.category = category; }
    void clearSearch() {
        query.setText(""); query.clearFocus();
        InputMethodManager keyboard=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);
    }
    int scrollPosition() { return scroll.getScrollY(); }
    void restoreScroll(int position) { scroll.post(() -> scroll.scrollTo(0, position)); }

    private static void detach(View view) {
        if (view.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) view.getParent()).removeView(view);
    }
    private LinearLayout.LayoutParams spaced(int width,int height) {
        LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(width<0?width:theme.px(width),theme.px(height));
        slot.rightMargin=theme.px(NativeStyle.GAP);return slot;
    }

    private void arrangeHeader(int width) {
        detach(title); detach(searchControls); heading.removeAllViews();
        boolean stacked = TouchLayout.stackedHeader(width);
        heading.setOrientation(stacked ? VERTICAL : HORIZONTAL);
        if (stacked) {
            LinearLayout top = row();
            top.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
            heading.addView(top, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.px(TouchLayout.TARGET)));
            LinearLayout.LayoutParams secondLine = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.px(TouchLayout.TARGET));
            secondLine.topMargin = theme.px(NativeStyle.GAP); heading.addView(searchControls, secondLine);
        } else {
            heading.addView(title, new LinearLayout.LayoutParams(theme.px(90), LayoutParams.MATCH_PARENT));
            heading.addView(searchControls, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        heading.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
            theme.px(TouchLayout.TARGET * (stacked ? 2 : 1) + (stacked ? NativeStyle.GAP : 0))));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || (width == layoutWidth && height == layoutHeight)) return;
        layoutWidth = width; layoutHeight = height;
        // Wait until the current layout pass has finished before reparenting header children.
        post(() -> {
            try {
                int dp = Math.round((getWidth() - getPaddingLeft() - getPaddingRight()) / getResources().getDisplayMetrics().density);
                int saved = scroll.getScrollY(); layoutColumns = TouchLayout.columns(Math.max(1,dp-RAIL_WIDTH-NativeStyle.GAP),
                    Math.round(getHeight() / getResources().getDisplayMetrics().density));
                arrangeHeader(dp); render(page, false); restoreScroll(saved);
            } catch (RuntimeException | LinkageError ignored) { message("Layout could not resize. Close and reopen the browser."); }
        });
    }
    private void compact(Button button, boolean filled) {
        NativeStyle.button(button, theme, filled);
    }

    void message(String message) { noticeError=!message.isEmpty();status.setText(message);status.setVisibility(message.isEmpty()?GONE:VISIBLE); }
    void activityMessage(String ignored) {
        if(!noticeError&&!page.error&&!page.busy){status.setText("");status.setVisibility(GONE);}
    }
    void sending(boolean value) { sending = value; render(page, false); }

    void render(BrowserPage value, boolean resetScroll) {
        dismissItemMenu();
        this.page = value;
        noticeError=value.error;
        final long version = ++renderVersion;
        title.setText("Library");
        clear.setVisibility(query.length()>0||category.equals("search")?VISIBLE:GONE);clear.setEnabled(!sending);
        status.setText(value.message);
        status.setVisibility(!value.busy&&(value.error || value.rows.isEmpty() || value.message.startsWith("Browse limit")) ? VISIBLE : GONE);
        loading.setVisibility(value.busy?VISIBLE:GONE);
        if(value.busy)return; // Keep existing rows and artwork behind the loading card.
        NativeStyle.text(status, theme, 12, 400, value.error ? "error" : "onSurfaceVariant");
        for (Map.Entry<String, Button> entry : tabs.entrySet()) {
            compact(entry.getValue(), entry.getKey().equals(category));
            entry.getValue().setGravity(Gravity.START|Gravity.CENTER_VERTICAL);entry.getValue().setTextSize(13);
            entry.getValue().setSelected(entry.getKey().equals(category));
        }
        for (ArtBinding binding : artBindings) binding.visible = false;
        artBindings.clear(); rows.removeAllViews();
        if (value.parent != null || category.equals("detail")) {
            if (value.parent != null) {
                LinearLayout hero = row(); hero.setPadding(theme.px(4), theme.px(8), theme.px(4), theme.px(8));
                ImageView cover = new ImageView(getContext()); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                hero.addView(cover, new LinearLayout.LayoutParams(theme.px(80), theme.px(80)));
                artwork(value.parent, cover, version);
                TextView detailTitle = new TextView(getContext()); detailTitle.setText(value.parent.name + "\n" + value.parent.subtitle);
                NativeStyle.text(detailTitle, theme, 17, 600, "onSurface"); detailTitle.setMaxLines(3);
                detailTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detailTitle.setPadding(theme.px(12), 0, 0, 0);
                hero.addView(detailTitle, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                hero.addView(favoriteButton(value.parent,version),new LinearLayout.LayoutParams(theme.px(44),theme.px(44)));rows.addView(hero);
            }
            LinearLayout controls = row();
            Button back = button("‹ Back", () -> actions.back()); compact(back, false); back.setEnabled(!sending);
            controls.addView(back, spaced(LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));
            if (value.parent != null && value.parent.playable()) {
                boolean album=value.parent.type.equals("album");
                Button play = button("Play", () -> actions.play(value.parent, false));
                Button add = button("Add to queue", () -> actions.play(value.parent, true));
                compact(play, true); compact(add, false);
                play.setEnabled(!sending); add.setEnabled(!sending);
                if(album)controls.addView(albumPlay(value.parent),spaced(44,44));else {controls.addView(play, spaced(LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));controls.addView(add, spaced(LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));}
                Button options = button(album?"⋯":"⋯ Actions", () -> {}); options.setContentDescription("Actions for "+value.parent.name);options.setOnClickListener(v->actions.menu(value.parent,v));compact(options, false);
                controls.addView(options, spaced(album?48:LayoutParams.WRAP_CONTENT,TouchLayout.TARGET));
            }
            HorizontalScrollView toolbar = new HorizontalScrollView(getContext());
            toolbar.setHorizontalScrollBarEnabled(false); toolbar.addView(controls); rows.addView(toolbar);
        }
        boolean list = category.equals("tracks") || (category.equals("detail") && (value.parent == null || !value.parent.type.equals("artist")));
        int columns = list ? 1 : gridColumns();
        LinearLayout line = null;
        int index = 0;
        String section = "";
        for (BrowserItem item : value.rows) {
            if (!item.section.equals(section)) {
                if (line != null && index % columns != 0) for (int i = index % columns; i < columns; i++) line.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
                section = item.section; index = 0;
                if (category.equals("search")) { list = item.type.equals("track") || item.type.equals("podcast_episode"); columns = list ? 1 : gridColumns(); }
                TextView label = new TextView(getContext()); label.setText(section);
                NativeStyle.text(label, theme, 17, 600, "onSurface"); label.setPadding(theme.px(4), theme.px(12), 0, theme.px(8)); rows.addView(label);
            }
            if (index % columns == 0) { line = list?row():new CardRow(getContext()); line.setGravity(Gravity.TOP); rows.addView(line); }
            LinearLayout tile = new LinearLayout(getContext()); tile.setOrientation(list ? HORIZONTAL : VERTICAL);
            int tileInset=theme.px(item.type.equals("album")?4:8);tile.setPadding(tileInset,tileInset,tileInset,tileInset);
            tile.setBackground(NativeStyle.card(theme,false));
            ImageView art = list ? new ImageView(getContext()) : new SquareArtwork(getContext());
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackgroundColor(NativeStyle.color("surfaceContainerHigh")); art.setContentDescription(item.name + " artwork");
            int side = theme.px(list ? 48 : 104);
            tile.addView(art, new LinearLayout.LayoutParams(list ? side : LayoutParams.MATCH_PARENT, side));
            if(list&&item.type.equals("track")&&getWidth()>0&&getWidth()-getPaddingLeft()-getPaddingRight()-theme.px(RAIL_WIDTH+NativeStyle.GAP)<theme.px(300))art.setVisibility(GONE);
            artwork(item, art, version);
            LinearLayout caption = row(); caption.setGravity(Gravity.TOP);
            LinearLayout labels = new LinearLayout(getContext()); labels.setOrientation(VERTICAL);
            labels.setPadding(theme.px(8), theme.px(4), theme.px(4), theme.px(4));
            TextView text = new TextView(getContext()); text.setText(item.name);
            text.setMaxLines(2); text.setEllipsize(android.text.TextUtils.TruncateAt.END); NativeStyle.text(text, theme, 14, 600, "onSurface");
            text.setIncludeFontPadding(false);
            TextView subtitle = new TextView(getContext()); subtitle.setText(item.subtitle+(!item.type.equals("track")&&item.duration>0?" · "+trackTime(item.duration):""));
            subtitle.setMaxLines(1); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END); NativeStyle.text(subtitle, theme, 12, 400, "onSurfaceVariant");
            subtitle.setIncludeFontPadding(false);if(item.subtitle.isEmpty())subtitle.setVisibility(GONE);
            labels.addView(text); labels.addView(subtitle);
            if(item.type.equals("track")){TextView duration=new TextView(getContext());duration.setText(trackTime(item.duration));duration.setContentDescription("Duration: "+item.name);NativeStyle.text(duration,theme,12,400,"onSurfaceVariant");duration.setIncludeFontPadding(false);labels.addView(duration);}
            caption.addView(labels, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            LinearLayout itemActions=row();itemActions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            boolean albumCard=item.type.equals("album");int targetSize=theme.px(albumCard?44:48);
            if(albumCard)itemActions.addView(albumPlay(item),new LayoutParams(targetSize,targetSize));
            Button heart=favoriteButton(item,version);if(albumCard){heart.setTag("album-controls");smallAlbumIcon(heart);}itemActions.addView(heart,new LinearLayout.LayoutParams(targetSize,targetSize));
            Button menu = button("⋮", () -> {});menu.setOnClickListener(v->{if(!sending)actions.menu(item,v);});compact(menu, false);
            menu.setContentDescription("Actions for " + item.name);
            if(albumCard)smallAlbumIcon(menu);itemActions.addView(menu, new LinearLayout.LayoutParams(targetSize,targetSize));
            if(list){
                caption.setGravity(Gravity.CENTER_VERTICAL);caption.addView(itemActions);tile.addView(caption,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
            }else{
                tile.addView(caption,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));
                tile.addView(itemActions,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            }
            tile.setFocusable(true); tile.setClickable(true);
            tile.setContentDescription(item.name + ", " + item.subtitle + ", " + TouchLayout.tap(item.type) + "; hold for options");
            tile.setOnClickListener(v -> {
                if (sending) return;
                try {
                    switch (TouchLayout.tap(item.type)) {
                        case "open": actions.open(item); break;
                        case "play": actions.play(item, false); break;
                        default: actions.menu(item,tile);
                    }
                } catch (RuntimeException | LinkageError ignored) { message("Item action unavailable."); }
            });
            tile.setOnLongClickListener(v -> {
                if (!sending) try { actions.menu(item,tile); }
                catch (RuntimeException | LinkageError ignored) { message("Item options unavailable."); }
                // Consume the hold so releasing does not also open or start playback.
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(theme.px(4),line.getParent()==rows&&rows.indexOfChild(line)==0?0:theme.px(4),theme.px(4),theme.px(4)); line.addView(tile, params);
            index++;
        }
        if (line != null && index % columns != 0) for (int i = index % columns; i < columns; i++)
            line.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        if (value.error) {
            Button retry = button("Retry", () -> actions.retry()); compact(retry, false);
            rows.addView(retry, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.px(TouchLayout.TARGET)));
        } else if (value.next) {
            Button more = button(value.busy ? "Loading…" : "Load more", () -> actions.more()); compact(more, false);
            more.setEnabled(!value.busy); rows.addView(more, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.px(TouchLayout.TARGET)));
        }
        if (resetScroll) scroll.scrollTo(0, 0);
        scroll.post(this::updateArtwork);
    }

    static String trackTime(int seconds){return seconds>0?String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60):"—";}
    private int gridColumns(){int available=Math.max(1,getWidth()-getPaddingLeft()-getPaddingRight()-theme.px(RAIL_WIDTH+NativeStyle.GAP));return Math.max(1,Math.min(6,available/theme.px(152)));}
    private static final class SquareArtwork extends ImageView {
        SquareArtwork(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int side = View.MeasureSpec.getSize(widthSpec);
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
        }
    }
    /** Equal card heights within each row; extra space sits below metadata, not between title/artist. */
    private static final class CardRow extends LinearLayout {
        CardRow(Context context){super(context);setOrientation(HORIZONTAL);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            super.onMeasure(widthSpec,heightSpec);int height=getMeasuredHeight();
            for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);if(!(child instanceof LinearLayout)||child.getVisibility()==GONE)continue;
                LinearLayout.LayoutParams slot=(LinearLayout.LayoutParams)child.getLayoutParams();
                child.measure(MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,height-getPaddingTop()-getPaddingBottom()-slot.topMargin-slot.bottomMargin),MeasureSpec.EXACTLY));
            }
        }
    }
}
