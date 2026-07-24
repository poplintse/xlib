package com.xlib.txtreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_TXT_REQUEST = 1001;
    private static final String PREFS = "xlib_reader";
    private static final int THEME_LIGHT = ReaderSettingsOptions.THEME_LIGHT;
    private static final int THEME_DARK = ReaderSettingsOptions.THEME_DARK;
    private static final int SENSITIVITY_HIGH = ReaderSettingsOptions.SENSITIVITY_HIGH;
    private static final int SENSITIVITY_STANDARD = ReaderSettingsOptions.SENSITIVITY_STANDARD;
    private static final int SENSITIVITY_LOW = ReaderSettingsOptions.SENSITIVITY_LOW;
    // 128 KiB keeps local reads fast while leaving several rendered pages for prefetching.
    private static final int CACHE_SEGMENT_BYTES = 128 * 1024;
    private static final float CACHE_REFILL_RATIO = 0.10f;
    private static final int INDEX_STEP_BYTES = 64 * 1024;
    private static final int SEGMENT_CACHE_LIMIT = 6;
    // XLI2: cache text is guaranteed to end on a complete encoded character.
    private static final int READER_CACHE_MAGIC = 0x584C4932;
    private static final int MAX_READER_CACHE_TEXT_BYTES = CACHE_SEGMENT_BYTES * 8;
    private static final long READER_CACHE_WRITE_DELAY_MS = 800L;
    private static final long BOOK_PROGRESS_SAVE_DELAY_MS = 600L;
    private static final int SEARCH_READ_BYTES = 64 * 1024;
    private static final int SEARCH_RESULT_LIMIT = 200;
    private static final int SEARCH_CONTEXT_CHARS = 45;
    private static final int SEARCH_SNIPPET_MAX_READ_BYTES = 64 * 1024;
    private static final int PAGE_WINDOW_MAX_PAGES = 17;
    private static final int PAGE_WINDOW_PREFETCH_EACH_SIDE =
            ReaderPageRefillPolicy.TARGET_READY_PAGES;
    private static final int PAGE_DIRECTION_BACKWARD = ReaderPageRefillPolicy.BACKWARD;
    private static final int PAGE_DIRECTION_NONE = ReaderPageRefillPolicy.NONE;
    private static final int PAGE_DIRECTION_FORWARD = ReaderPageRefillPolicy.FORWARD;
    private static final long SEEK_PREVIEW_DELAY_MS = 220L;
    private static final String KEY_AUTO_TOC = "auto_toc";
    private static final String KEY_APP_THEME = "app_theme";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_AUTO_PAGE_INTERVAL = "auto_page_interval";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_LINE_SPACING = "line_spacing";
    private static final int SETTINGS_GENERAL = 0;
    private static final int SETTINGS_READING = 1;
    private static final int SETTINGS_SYNC = 2;

    private final List<Book> books = new ArrayList<>();
    private final Set<Long> selectedBookIds = new HashSet<>();
    private final Set<Long> pendingProgressPublications = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService pageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService readerCacheExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService tocExecutor = Executors.newSingleThreadExecutor();
    private final ReaderPagePaginator readerPagePaginator = new ReaderPagePaginator();
    private final Map<String, CacheSegment> segmentCache =
            new LinkedHashMap<String, CacheSegment>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheSegment> eldest) {
            return size() > SEGMENT_CACHE_LIMIT;
        }
    };
    private BookStore bookStore;
    private BookmarkStore bookmarkStore;
    private SharedPreferences preferences;
    private TocStore tocStore;
    private LocalProgressStore localProgressStore;
    private SyncTokenStore syncTokenStore;
    private SyncServerConfig syncServerConfig;
    private BookHashCache bookHashCache;
    private ProgressSyncCoordinator syncCoordinator;
    private volatile SyncUiState syncUiState;
    private Book currentBook;
    private boolean managingBooks;
    private boolean loadingChunk;
    private boolean loadingBackwardSegment;
    private boolean loadingForwardSegment;
    private boolean pageAnimating;
    private boolean edgeBackCandidate;
    private boolean edgeBackTriggered;
    private boolean touchMoved;
    private boolean touchStartedWithMenusOpen;
    private boolean menuDismissTouch;
    private boolean readerMenusOpen;
    private boolean seekTracking;
    private boolean searchOpen;
    private boolean settingsOpen;
    private boolean settingsOpenedFromLibrary;
    private int currentSettingsTab = SETTINGS_GENERAL;
    private boolean catalogOpen;
    private boolean temporarySearchReading;
    private boolean suppressProgressSave;
    private boolean activityResumed;
    private volatile boolean activityDestroyed;
    private boolean autoPageDispatching;
    private boolean booksSavePending;
    private int autoPageSeconds = AutoPageOptions.OFF;
    private ReaderCacheWrite pendingReaderCache;
    private SearchSession searchSession;
    private float touchStartX;
    private float touchStartY;
    private float pendingSeekProgress;
    private volatile long loadRequestId;
    private volatile long searchRequestId;
    private long cacheStackGeneration;
    private volatile long pageBuildRequestId;
    private long pageLayoutGeneration;
    private CombinedCacheSnapshot currentCache;
    private CacheCombineStack currentCombineStack;
    private final ReaderPageWindow readerPageWindow =
            new ReaderPageWindow(PAGE_WINDOW_MAX_PAGES);
    private boolean loadingReaderPages;
    private boolean resettingReaderPages;
    private int preferredPageRefillDirection = PAGE_DIRECTION_NONE;
    private Bitmap currentPageSnapshot;
    private long snapshotPageOffset = -1L;
    private int snapshotScrollY = -1;
    private int snapshotWidth = -1;
    private int snapshotHeight = -1;
    private float snapshotFontSize = -1f;
    private int snapshotTheme = -1;
    private long pageStateGeneration;
    private long snapshotGeneration = -1L;
    private int fittedReaderBottomInset = -1;
    private float fittedReaderFontSize = -1f;
    private int fittedReaderVisibleHeight = -1;
    private int readerContainerHeight = -1;
    private int readerContainerWidth = -1;
    private FrameLayout readerFrame;
    private LinearLayout readerRoot;
    private AccessibleScrollView readerScroll;
    private ReaderPageTextView readerText;
    private LinearLayout readerTopBar;
    private LinearLayout readerBottomBar;
    private Button progressButton;
    private final List<View> progressStepButtons = new ArrayList<>();
    private ImageButton keepScreenOnButton;
    private ImageButton autoPageButton;
    private LinearLayout seekPanel;
    private SeekBar seekBar;
    private ScrollView settingsScroll;
    private LinearLayout settingsContent;

    private final Runnable seekPreviewRunnable = () -> {
        if (seekTracking) {
            loadCacheWindowAtProgress(pendingSeekProgress);
        }
    };
    private final Runnable readerCacheWriteRunnable = () -> {
        ReaderCacheWrite cache = pendingReaderCache;
        pendingReaderCache = null;
        if (cache != null) writeReaderCache(cache);
    };
    private final Runnable booksSaveRunnable = () -> {
        booksSavePending = false;
        saveBooks();
    };
    private final Runnable autoPageRunnable = () -> {
        Book book = currentBook;
        long delay = ReaderRuntimePolicy.autoPageDelayMillis(autoPageSeconds,
                activityResumed, book != null, readerScroll != null,
                searchOpen, temporarySearchReading);
        if (delay == ReaderRuntimePolicy.NO_AUTO_PAGE_DELAY) {
            return;
        }
        if (ReaderRuntimePolicy.canDispatchAutoPage(pageAnimating, loadingChunk,
                suppressProgressSave, readerScroll != null)) {
            autoPageDispatching = true;
            try {
                pageForward();
            } finally {
                autoPageDispatching = false;
            }
        }
        scheduleAutoPage();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateLegacySystemTheme();
        bookStore = new BookStore(preferences);
        bookmarkStore = new BookmarkStore(preferences);
        tocStore = new TocStore(this);
        localProgressStore = new LocalProgressStore();
        syncTokenStore = new SyncTokenStore(preferences);
        syncServerConfig = new SyncServerConfig(preferences);
        bookHashCache = new BookHashCache(preferences);
        SyncApiClient syncApiClient = new SyncApiClient(syncServerConfig.url());
        syncCoordinator = new ProgressSyncCoordinator(this, mainHandler,
                new ProgressSyncCoordinator.Listener() {
                    @Override public void onSyncStateChanged(SyncUiState state) {
                        syncUiState = state;
                        if (!activityDestroyed && settingsOpen
                                && currentSettingsTab == SETTINGS_SYNC
                                && settingsContent != null) {
                            renderSyncSettingsForCurrentTheme();
                        }
                    }

                    @Override public void onRemoteJumpAvailable(String sessionId,
                                                                 long localBookId,
                                                                 RemoteProgressSnapshot remote) {
                        showRemoteJumpDialog(sessionId, localBookId, remote);
                    }
                }, localProgressStore, new RemoteProgressStore(preferences),
                bookHashCache, syncTokenStore, syncServerConfig,
                syncApiClient, getVersionName());
        syncCoordinator.start();
        loadBooks();
        showLibrary();
        if (isAutoTocEnabled()) scheduleMissingTocGeneration();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        disableAutoPage();
        applyKeepScreenOn(false);
        saveCurrentProgress();
        if (syncCoordinator != null) syncCoordinator.onBackground();
        flushReaderCacheWrite();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (syncCoordinator != null) syncCoordinator.onForeground();
        boolean readerAttached = readerFrame != null && readerFrame.isAttachedToWindow();
        applyKeepScreenOn(ReaderRuntimePolicy.shouldKeepScreenOn(
                readingKeepScreenOn(), readerAttached));
        scheduleAutoPage();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        flushScheduledBooksSave();
        if (syncCoordinator != null) syncCoordinator.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdownNow();
        libraryExecutor.shutdownNow();
        pageExecutor.shutdownNow();
        indexExecutor.shutdownNow();
        readerCacheExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        tocExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (catalogOpen && currentBook != null) {
            catalogOpen = false;
            showReader(currentBook);
            return;
        }
        if (temporarySearchReading && searchSession != null && currentBook != null) {
            returnToSearchPage();
            return;
        }
        if (searchOpen && currentBook != null) {
            searchRequestId++;
            searchOpen = false;
            showReader(currentBook);
            return;
        }
        if (settingsOpen) {
            settingsOpen = false;
            if (settingsOpenedFromLibrary || currentBook == null) showLibrary();
            else showReader(currentBook);
            return;
        }
        if (currentBook != null) {
            saveCurrentProgress();
            cancelReaderPipelineWork();
            releasePageSnapshot();
            currentBook = null;
            if (syncCoordinator != null) syncCoordinator.closeBook();
            readerMenusOpen = false;
            showLibrary();
            return;
        }
        if (managingBooks) {
            managingBooks = false;
            selectedBookIds.clear();
            showLibrary();
            return;
        }
        super.onBackPressed();
    }

    private void showLibrary() {
        disableAutoPage();
        applyKeepScreenOn(false);
        int theme = appTheme();
        applyWindowColors(theme);

        boolean dark = isDarkTheme(theme);
        int background = backgroundColor(theme);
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int text = textColor(theme);
        int muted = dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
        int accentContainer = dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(0, statusBarHeight() + dp(18), 0, navigationBarHeight() + dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), 0, dp(20), 0);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("我的书架");
        UiKit.styleTitle(title, text, 30);
        heading.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(books.isEmpty() ? "安静地读一本好书" : books.size() + " 本本地书籍");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(muted);
        subtitle.setPadding(0, dp(5), 0, 0);
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(68), 1));

        ImageButton add = makeIconButton();
        add.setImageResource(R.drawable.ic_add_book);
        add.setContentDescription("添加 TXT");
        UiKit.styleIconButton(this, add, accent, accentContainer, 16);
        add.setOnClickListener(v -> pickTxtFile());
        header.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageButton manage = makeIconButton();
        manage.setImageResource(managingBooks ? R.drawable.ic_done : R.drawable.ic_manage_books);
        manage.setContentDescription(managingBooks ? "完成管理" : "管理书籍");
        UiKit.styleIconButton(this, manage, accent, accentContainer, 16);
        manage.setOnClickListener(v -> {
            managingBooks = !managingBooks;
            selectedBookIds.clear();
            showLibrary();
        });
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        manageLp.leftMargin = dp(8);
        header.addView(manage, manageLp);

        ImageButton settings = makeIconButton();
        settings.setImageResource(R.drawable.ic_settings);
        settings.setContentDescription("常规设置");
        UiKit.styleIconButton(this, settings, accent, accentContainer, 16);
        settings.setOnClickListener(v -> openSettingsPage(SETTINGS_GENERAL));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsLp.leftMargin = dp(8);
        header.addView(settings, settingsLp);
        root.addView(header);

        if (books.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(30), dp(36), dp(30), dp(36));
            UiKit.styleCard(this, empty, surface, 28, 1);

            TextView monogram = new TextView(this);
            monogram.setText(R.string.txt_badge);
            monogram.setGravity(Gravity.CENTER);
            monogram.setTextColor(accent);
            monogram.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            monogram.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            monogram.setBackground(UiKit.rounded(this, accentContainer, 22));
            empty.addView(monogram, new LinearLayout.LayoutParams(dp(72), dp(72)));

            TextView emptyTitle = new TextView(this);
            emptyTitle.setText("书架还是空的");
            emptyTitle.setGravity(Gravity.CENTER);
            emptyTitle.setPadding(0, dp(24), 0, 0);
            UiKit.styleTitle(emptyTitle, text, 21);
            empty.addView(emptyTitle);

            TextView emptyBody = new TextView(this);
            emptyBody.setText(R.string.empty_library_body);
            emptyBody.setTextColor(muted);
            emptyBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            emptyBody.setGravity(Gravity.CENTER);
            emptyBody.setPadding(0, dp(10), 0, dp(24));
            empty.addView(emptyBody);

            Button importButton = makeButton("导入第一本书");
            UiKit.styleButton(this, importButton, accent, dark ? UiKit.DARK_BACKGROUND : Color.WHITE, 18);
            importButton.setOnClickListener(v -> pickTxtFile());
            empty.addView(importButton, new LinearLayout.LayoutParams(dp(168), dp(52)));

            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            emptyLp.topMargin = dp(24);
            emptyLp.bottomMargin = dp(16);
            emptyLp.leftMargin = dp(20);
            emptyLp.rightMargin = dp(20);
            root.addView(empty, emptyLp);
        } else {
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, dp(18), 0, dp(8));
            for (Book book : books) {
                list.addView(makeBookRow(book));
            }
            ScrollView scroll = new ScrollView(this);
            scroll.addView(list);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }

        if (managingBooks) {
            Button deleteSelected = makeButton("删除所选 " + selectedBookIds.size());
            UiKit.styleButton(this, deleteSelected,
                    dark ? Color.rgb(86, 37, 38) : Color.rgb(255, 226, 225),
                    dark ? Color.rgb(255, 180, 178) : Color.rgb(150, 24, 27), 18);
            deleteSelected.setEnabled(!selectedBookIds.isEmpty());
            deleteSelected.setOnClickListener(v -> deleteSelectedBooks());
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            deleteLp.leftMargin = dp(20);
            deleteLp.rightMargin = dp(20);
            root.addView(deleteSelected, deleteLp);
        }

        TextView version = new TextView(this);
        version.setText(getVersionText());
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        version.setTextColor(muted);
        version.setGravity(Gravity.START);
        version.setPadding(dp(20), dp(8), dp(20), 0);
        root.addView(version, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        setContentView(root);
    }

    private View makeBookRow(Book book) {
        int cardCornerRadius = 22;
        boolean dark = isDarkTheme(appTheme());
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int textColor = dark ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT;
        int muted = dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
        int accentContainer = dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(13), dp(2), dp(13));
        row.setBackground(UiKit.interactive(this, surface, cardCornerRadius,
                UiKit.withAlpha(accent, 28)));
        row.setElevation(dp(1));
        row.setClipToOutline(true);

        if (managingBooks) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setChecked(selectedBookIds.contains(book.id));
            checkBox.setOnClickListener(v -> toggleBookSelection(book));
            row.addView(checkBox, new LinearLayout.LayoutParams(dp(44), dp(52)));
        }

        TextView badge = new TextView(this);
        badge.setText(R.string.txt_badge);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badge.setTextColor(accent);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setBackground(UiKit.rounded(this, accentContainer, 12));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(58), dp(82));
        badgeLp.rightMargin = dp(14);
        row.addView(badge, badgeLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(shortBookTitle(book.title));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        texts.addView(title);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, dp(5), 0, 0);

        TextView meta = new TextView(this);
        meta.setText(bookAuthor(book));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setTextColor(muted);
        metaRow.addView(meta, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        texts.addView(metaRow);

        TextView updated = new TextView(this);
        updated.setText(String.format(Locale.getDefault(), "%s · %.2f%%",
                relativeReadTime(book.updatedAt), book.progress * 100f));
        updated.setSingleLine(true);
        updated.setEllipsize(TextUtils.TruncateAt.END);
        updated.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        updated.setTextColor(muted);
        updated.setPadding(0, dp(7), 0, 0);
        texts.addView(updated);

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> {
            if (managingBooks) {
                toggleBookSelection(book);
            } else {
                openBook(book);
            }
        });
        if (managingBooks) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            row.setLayoutParams(lp);
            return row;
        }

        int actionWidth = dp(64);
        int actionMenuWidth = actionWidth * 2;
        int actionText = Color.WHITE;
        int moreBackground = dark ? Color.rgb(78, 91, 105) : Color.rgb(112, 130, 149);
        int deleteBackground = dark ? Color.rgb(164, 58, 62) : Color.rgb(222, 72, 79);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        ImageButton more = makeIconButton();
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription("编辑书籍信息");
        UiKit.styleIconButton(this, more, actionText, moreBackground, cardCornerRadius);
        more.setOnClickListener(v -> showEditBookDialog(book));
        actions.addView(more, new LinearLayout.LayoutParams(actionWidth,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton delete = makeIconButton();
        delete.setImageResource(R.drawable.ic_delete);
        delete.setContentDescription("删除书籍");
        UiKit.styleIconButton(this, delete, actionText, deleteBackground, cardCornerRadius);
        delete.setOnClickListener(v -> showDeleteBookDialog(book));
        actions.addView(delete, new LinearLayout.LayoutParams(actionWidth,
                ViewGroup.LayoutParams.MATCH_PARENT));
        actions.setVisibility(View.INVISIBLE);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(
                actionMenuWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        container.addView(actions, actionsLp);
        container.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final float[] touchDown = new float[1];
        final float[] touchStartTranslation = new float[1];
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    touchDown[0] = event.getRawX();
                    touchStartTranslation[0] = view.getTranslationX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float translation = Math.min(0, Math.max(-actionMenuWidth,
                            touchStartTranslation[0] + event.getRawX() - touchDown[0]));
                    actions.setVisibility(translation < 0 ? View.VISIBLE : View.INVISIBLE);
                    view.setTranslationX(translation);
                    return true;
                case MotionEvent.ACTION_UP:
                    float distance = event.getRawX() - touchDown[0];
                    if (Math.abs(distance) < dp(8)) {
                        if (view.getTranslationX() < 0) {
                            view.animate().translationX(0).setDuration(160)
                                    .withEndAction(() -> actions.setVisibility(View.INVISIBLE))
                                    .start();
                        } else {
                            view.performClick();
                        }
                    } else {
                        float target = view.getTranslationX() <= -actionMenuWidth / 2f
                                ? -actionMenuWidth : 0;
                        if (target == 0) {
                            view.animate().translationX(0).setDuration(160)
                                    .withEndAction(() -> actions.setVisibility(View.INVISIBLE))
                                    .start();
                        } else {
                            view.animate().translationX(target).setDuration(160).start();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0).setDuration(160)
                            .withEndAction(() -> actions.setVisibility(View.INVISIBLE))
                            .start();
                    return true;
                default:
                    return true;
            }
        });

        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        containerLp.bottomMargin = dp(12);
        container.setLayoutParams(containerLp);
        return container;
    }

    private void toggleBookSelection(Book book) {
        if (selectedBookIds.contains(book.id)) {
            selectedBookIds.remove(book.id);
        } else {
            selectedBookIds.add(book.id);
        }
        showLibrary();
    }

    private String shortBookTitle(String title) {
        if (title == null) return "";
        int count = title.codePointCount(0, title.length());
        if (count <= 10) return title;
        return title.substring(0, title.offsetByCodePoints(0, 10)) + "…";
    }

    private String defaultBookName(Book book) {
        return originalTxtFileName(book);
    }

    private String originalTxtFileName(Book book) {
        if (!TextUtils.isEmpty(book.sourceName)) return book.sourceName;
        String storedName = new File(book.path).getName();
        int separator = storedName.indexOf('-');
        if (separator > 0 && separator + 1 < storedName.length()) {
            storedName = storedName.substring(separator + 1);
        }
        return TextUtils.isEmpty(storedName) ? "未命名.txt" : storedName;
    }

    private String bookAuthor(Book book) {
        return TextUtils.isEmpty(book.author) ? "佚名" : book.author;
    }

    private String editableBookAuthor(Book book) {
        return TextUtils.isEmpty(book.author) ? originalTxtFileName(book) : book.author;
    }

    private String relativeReadTime(long timestamp) {
        long hours = Math.max(1L, (System.currentTimeMillis() - timestamp) / (60L * 60L * 1000L));
        return hours < 24L ? hours + "小时前" : (hours / 24L) + "天前";
    }

    private void showRemoteJumpDialog(String sessionId, long localBookId,
                                      RemoteProgressSnapshot remote) {
        Book book = currentBook;
        if (book == null || book.id != localBookId || temporarySearchReading
                || activityDestroyed) {
            if (syncCoordinator != null) syncCoordinator.onJumpDeclined(sessionId);
            return;
        }
        String message = "是否跳转到在“" + remote.sourceDeviceName + "”阅读的最新进度？\n"
                + "位置：" + String.format(Locale.getDefault(), "%,d", remote.offset)
                + "（" + String.format(Locale.getDefault(), "%.2f%%", remote.progress * 100d)
                + "）\n进度于" + remoteProgressRelativeTime(remote.readAtMs) + "保存。";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现更新的阅读进度")
                .setMessage(message)
                .setNegativeButton("暂不跳转", (ignored, which) ->
                        syncCoordinator.onJumpDeclined(sessionId))
                .setPositiveButton("跳转", (ignored, which) -> {
                    if (currentBook == null || currentBook.id != localBookId) {
                        syncCoordinator.onJumpDeclined(sessionId);
                        return;
                    }
                    applyFormalProgress(currentBook, remote.offset, remote.readAtMs);
                    saveBooks();
                    syncCoordinator.onRemoteJumpApplied(sessionId, remote.fileSize,
                            remote.offset, remote.readAtMs);
                    showReader(currentBook);
                })
                .create();
        dialog.setOnCancelListener(ignored -> syncCoordinator.onJumpDeclined(sessionId));
        dialog.show();
    }

    private String remoteProgressRelativeTime(long timestamp) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - timestamp);
        long minutes = elapsed / 60_000L;
        if (minutes < 1L) return "刚刚";
        long days = minutes / (24L * 60L);
        long hours = (minutes / 60L) % 24L;
        long remainingMinutes = minutes % 60L;
        StringBuilder result = new StringBuilder();
        if (days > 0L) result.append(days).append("天");
        if (hours > 0L) result.append(hours).append("小时");
        if (remainingMinutes > 0L || result.length() == 0) {
            result.append(remainingMinutes).append("分钟");
        }
        return result.append("前").toString();
    }

    private void showEditBookDialog(Book book) {
        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint("书名");
        titleInput.setText(TextUtils.isEmpty(book.title) ? defaultBookName(book) : book.title);
        titleInput.setSelection(titleInput.length());
        EditText authorInput = new EditText(this);
        authorInput.setSingleLine(true);
        authorInput.setHint("作者");
        authorInput.setText(editableBookAuthor(book));
        authorInput.setSelection(authorInput.length());
        int padding = dp(20);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, dp(8), padding, 0);
        container.addView(titleInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(authorInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑书籍信息")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String title = titleInput.getText().toString().trim();
                    if (title.isEmpty()) {
                        titleInput.setError("书籍名称不能为空");
                        return;
                    }
                    String author = authorInput.getText().toString().trim();
                    book.title = title;
                    book.author = author.equals(originalTxtFileName(book)) ? "" : author;
                    saveBooks();
                    hideKeyboard(titleInput);
                    dialog.dismiss();
                    mainHandler.postDelayed(this::showLibrary, 200L);
                }));
        dialog.show();
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void showDeleteBookDialog(Book book) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除书籍？")
                .setMessage("将删除《" + book.title + "》及本地 TXT 文件，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (ignored, which) -> deleteBook(book))
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private void deleteBook(Book book) {
        removeBookData(book);
        books.remove(book);
        selectedBookIds.remove(book.id);
        saveBooks();
        showLibrary();
    }

    private void removeBookData(Book book) {
        cancelReaderCacheWrite(book);
        deleteReaderCache(book);
        tocStore.delete(book);
        bookmarkStore.deleteForBook(book.id);
        if (bookHashCache != null) bookHashCache.remove(book.id);
        File file = new File(book.path);
        if (file.exists()) {
            boolean ignored = file.delete();
        }
    }

    private void deleteSelectedBooks() {
        List<Book> remaining = new ArrayList<>();
        for (Book book : books) {
            if (selectedBookIds.contains(book.id)) {
                removeBookData(book);
            } else {
                remaining.add(book);
            }
        }
        books.clear();
        books.addAll(remaining);
        selectedBookIds.clear();
        managingBooks = false;
        saveBooks();
        showLibrary();
    }

    private void pickTxtFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, PICK_TXT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_TXT_REQUEST && resultCode == RESULT_OK && data != null) {
            addBookFromUri(data.getData());
        }
    }

    private void addBookFromUri(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "正在导入 TXT…", Toast.LENGTH_SHORT).show();
        libraryExecutor.execute(() -> {
            Book imported = null;
            Exception error = null;
            try {
                imported = prepareBookImport(uri);
            } catch (Exception exception) {
                error = exception;
            }
            Book readyBook = imported;
            Exception finalError = error;
            if (activityDestroyed) {
                discardPreparedBook(readyBook);
                return;
            }
            mainHandler.post(() -> {
                if (activityDestroyed) {
                    discardPreparedBook(readyBook);
                    return;
                }
                finishBookImport(readyBook, finalError);
            });
        });
    }

    private void discardPreparedBook(Book book) {
        if (book == null || book.path == null) return;
        boolean ignored = new File(book.path).delete();
    }

    private Book prepareBookImport(Uri uri) throws Exception {
        String title = queryDisplayName(uri);
        if (title == null || title.trim().isEmpty()) {
            title = "book-" + System.currentTimeMillis() + ".txt";
        }
        long id = System.currentTimeMillis();
        File directory = new File(getFilesDir(), "books");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create book directory");
        }
        String safeName = title.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(directory, id + "-" + safeName);
        File temporary = new File(directory, target.getName() + ".tmp");
        try {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IllegalStateException("Cannot open selected file");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("Cannot publish imported TXT file");
            }
        } catch (Exception error) {
            boolean ignored = temporary.delete();
            throw error;
        }

        Book book = new Book();
        book.id = id;
        book.title = title;
        book.sourceName = title;
        book.author = "";
        book.path = target.getAbsolutePath();
        book.fileSize = target.length();
        book.encoding = detectEncoding(target);
        book.offset = 0L;
        book.progress = 0f;
        book.pageMode = true;
        book.updatedAt = System.currentTimeMillis();
        return book;
    }

    private void finishBookImport(Book book, Exception error) {
        if (error != null || book == null) {
            String message = error == null ? "未知错误" : error.getMessage();
            Toast.makeText(this, "添加失败：" + message, Toast.LENGTH_LONG).show();
            return;
        }
        books.add(0, book);
        saveBooks();
        if (isAutoTocEnabled()) generateTocInBackground(book, false);
        if (currentBook == null && !settingsOpen && !searchOpen && !catalogOpen) {
            showLibrary();
        }
        Toast.makeText(this, "已添加：" + book.title, Toast.LENGTH_SHORT).show();
    }

    private void openBook(Book book) {
        disableAutoPage();
        readerMenusOpen = false;
        currentBook = book;
        if (syncCoordinator != null) {
            syncCoordinator.openBook(book.id, new File(book.path), book.fileSize,
                    book.offset, book.updatedAt);
        }
        showReader(book);
    }

    private void showReader(Book book) {
        settingsOpen = false;
        catalogOpen = false;
        if (syncCoordinator != null) {
            syncCoordinator.setReaderActive(!temporarySearchReading, temporarySearchReading);
        }
        cancelReaderPipelineWork();
        pageLayoutGeneration++;
        currentCombineStack = null;
        currentCache = null;
        preferredPageRefillDirection = PAGE_DIRECTION_NONE;
        readerPageWindow.clear();
        releasePageSnapshot();
        fittedReaderBottomInset = -1;
        fittedReaderFontSize = -1f;
        fittedReaderVisibleHeight = -1;
        readerContainerWidth = -1;
        progressStepButtons.clear();
        int readerTheme = appTheme();
        applyWindowColors(readerTheme);
        applyKeepScreenOn(readingKeepScreenOn());
        int bg = backgroundColor(readerTheme);
        int fg = textColor(readerTheme);
        boolean darkTheme = isDarkTheme(readerTheme);
        int menuBg = darkTheme ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int menuFg = darkTheme ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT;
        int menuMuted = darkTheme ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(bg);
        readerFrame = frame;

        readerRoot = new LinearLayout(this);
        readerRoot.setOrientation(LinearLayout.VERTICAL);
        readerRoot.setBackgroundColor(bg);
        readerRoot.setClipChildren(true);
        readerRoot.setClipToPadding(true);

        readerTopBar = new LinearLayout(this);
        readerTopBar.setGravity(Gravity.CENTER_VERTICAL);
        readerTopBar.setPadding(dp(8), dp(7), dp(8), dp(7));
        UiKit.styleCard(this, readerTopBar, menuBg, 22, 8);
        readerTopBar.setClickable(true);
        readerTopBar.setVisibility(View.GONE);

        LinearLayout fontControls = new LinearLayout(this);
        fontControls.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        Button smaller = makeButton("A−");
        smaller.setContentDescription("减小字号");
        UiKit.styleButton(this, smaller,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT,
                menuFg, 14);
        smaller.setPadding(0, 0, 0, 0);
        smaller.setOnClickListener(v -> updateFontSize(-2f));
        fontControls.addView(smaller, new LinearLayout.LayoutParams(dp(44), dp(42)));

        Button larger = makeButton("A+");
        larger.setContentDescription("增大字号");
        UiKit.styleButton(this, larger,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT,
                menuFg, 14);
        larger.setPadding(0, 0, 0, 0);
        larger.setOnClickListener(v -> updateFontSize(2f));
        LinearLayout.LayoutParams largerLp = new LinearLayout.LayoutParams(dp(44), dp(42));
        fontControls.addView(larger, largerLp);
        readerTopBar.addView(fontControls, new LinearLayout.LayoutParams(0, dp(42), 1));

        ImageButton search = makeIconButton();
        search.setImageResource(R.drawable.ic_search);
        search.setContentDescription("搜索当前书籍");
        UiKit.styleIconButton(this, search, menuFg,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 14);
        search.setOnClickListener(v -> openSearchPage());
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        searchLp.leftMargin = dp(4);
        readerTopBar.addView(search, searchLp);

        ImageButton theme = makeIconButton();
        theme.setImageResource(darkTheme ? R.drawable.ic_sun : R.drawable.ic_moon);
        UiKit.styleIconButton(this, theme, menuFg,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 14);
        theme.setContentDescription(darkTheme ? "切换到浅色主题" : "切换到深色主题");
        theme.setOnClickListener(v -> {
            saveCurrentProgress();
            setAppTheme(isDarkTheme(appTheme()) ? THEME_LIGHT : THEME_DARK);
            readerMenusOpen = true;
            showReader(book);
        });
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        themeLp.leftMargin = dp(4);
        readerTopBar.addView(theme, themeLp);

        keepScreenOnButton = makeIconButton();
        keepScreenOnButton.setImageResource(R.drawable.ic_screen_lock);
        keepScreenOnButton.setOnClickListener(v -> {
            boolean enabled = !readingKeepScreenOn();
            setReadingKeepScreenOn(enabled);
            applyKeepScreenOn(enabled);
            refreshKeepScreenOnButton();
        });
        refreshKeepScreenOnButton();
        LinearLayout.LayoutParams keepScreenOnLp =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        keepScreenOnLp.leftMargin = dp(4);
        readerTopBar.addView(keepScreenOnButton, keepScreenOnLp);

        autoPageButton = makeIconButton();
        autoPageButton.setImageResource(R.drawable.ic_auto_page);
        autoPageButton.setContentDescription(autoPageDescription(autoPageSeconds));
        autoPageButton.setOnClickListener(v -> {
            autoPageSeconds = autoPageSeconds == AutoPageOptions.OFF
                    ? readingAutoPageInterval()
                    : AutoPageOptions.OFF;
            refreshAutoPageButton();
            scheduleAutoPage();
        });
        refreshAutoPageButton();
        LinearLayout.LayoutParams autoPageLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        autoPageLp.leftMargin = dp(4);
        readerTopBar.addView(autoPageButton, autoPageLp);

        ImageButton settings = makeIconButton();
        settings.setImageResource(R.drawable.ic_settings);
        UiKit.styleIconButton(this, settings, menuFg,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 14);
        settings.setContentDescription("阅读设置");
        settings.setOnClickListener(v -> openSettingsPage(SETTINGS_READING));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        settingsLp.leftMargin = dp(4);
        readerTopBar.addView(settings, settingsLp);

        readerScroll = new AccessibleScrollView(this);
        readerScroll.setFillViewport(true);
        readerScroll.setClipToPadding(true);
        readerScroll.setClipChildren(true);
        applyReaderSafePadding(readingFontSize());

        readerText = new ReaderPageTextView(this);
        readerText.setTextColor(fg);
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, readingFontSize());
        readerText.setTypeface(readerTypeface(readingFontFamily()));
        // The viewport is fitted to whole rendered rows, so font padding must not add
        // an extra partial row above or below the text layout.
        readerText.setIncludeFontPadding(false);
        readerText.setPadding(dp(22), 0, dp(22), 0);
        applyReaderLineSpacing(readingFontSize());
        readerScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (pageAnimating) return true;
            if (action == MotionEvent.ACTION_DOWN && areReaderMenusVisible()) {
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchMoved = false;
                edgeBackCandidate = false;
                edgeBackTriggered = false;
                touchStartedWithMenusOpen = false;
                menuDismissTouch = true;
                hideReaderMenus();
                return true;
            }
            if (menuDismissTouch) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    menuDismissTouch = false;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getX();
                touchStartY = event.getY();
                edgeBackCandidate = touchStartX <= dp(tapToleranceDp());
                edgeBackTriggered = false;
                touchMoved = false;
                touchStartedWithMenusOpen = areReaderMenusVisible();
                if (edgeBackCandidate) {
                    return true;
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - touchStartX;
                float dy = Math.abs(event.getY() - touchStartY);
                if (Math.abs(dx) > dp(tapToleranceDp()) || dy > dp(tapToleranceDp())) {
                    touchMoved = true;
                }
                if (edgeBackCandidate && !edgeBackTriggered
                        && dx >= dp(swipeThresholdDp()) && dy <= dp(96)) {
                    edgeBackTriggered = true;
                    saveCurrentProgress();
                    onBackPressed();
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (edgeBackCandidate) {
                    float dx = event.getX() - touchStartX;
                    float dy = Math.abs(event.getY() - touchStartY);
                    boolean isTap = action == MotionEvent.ACTION_UP
                            && Math.abs(dx) <= dp(tapToleranceDp())
                            && dy <= dp(tapToleranceDp());
                    edgeBackCandidate = false;
                    if (isTap && touchStartedWithMenusOpen) {
                        hideReaderMenus();
                        return true;
                    }
                    if (isTap) {
                        v.performClick();
                        handleReaderTap(event.getX());
                    } else if (action == MotionEvent.ACTION_UP && currentBook != null && currentBook.pageMode) {
                        handlePageSwipe(dx, dy);
                    }
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_UP && touchStartedWithMenusOpen) {
                boolean isTap = !touchMoved
                        && Math.abs(event.getX() - touchStartX) <= dp(tapToleranceDp())
                        && Math.abs(event.getY() - touchStartY) <= dp(tapToleranceDp());
                touchStartedWithMenusOpen = false;
                if (isTap) {
                    hideReaderMenus();
                    return true;
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                touchStartedWithMenusOpen = false;
            }
            if (action == MotionEvent.ACTION_UP) {
                float dx = event.getX() - touchStartX;
                float dy = Math.abs(event.getY() - touchStartY);
                if (currentBook != null && currentBook.pageMode && handlePageSwipe(dx, dy)) {
                    return true;
                }
                boolean isTap = !touchMoved
                        && Math.abs(event.getX() - touchStartX) <= dp(tapToleranceDp())
                        && Math.abs(event.getY() - touchStartY) <= dp(tapToleranceDp());
                if (isTap) {
                    v.performClick();
                    if (handleReaderTap(event.getX())) return true;
                }
            }
            return currentBook != null && currentBook.pageMode;
        });
        readerScroll.addView(readerText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        readerRoot.addView(readerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams readerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        readerLp.topMargin = readerTopInset();
        readerLp.bottomMargin = readerBottomInset(readingFontSize());
        frame.addView(readerRoot, readerLp);
        readerRoot.addOnLayoutChangeListener((v, left, top, right, bottom,
                                              oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            int width = right - left;
            boolean widthChanged = width > 0 && width != readerContainerWidth;
            if (height > 0 && (height != readerContainerHeight || widthChanged)) {
                readerContainerHeight = height;
                readerContainerWidth = width;
                if (widthChanged) pageLayoutGeneration++;
                if (readerText != null && currentBook != null) {
                    readerText.post(() -> fitReaderViewportToWholeLines(readingFontSize()));
                    if (widthChanged && !readerPageWindow.isEmpty() && !loadingChunk) {
                        readerText.post(() -> readerText.postOnAnimation(
                                this::rebuildReaderPageWindow));
                    }
                }
            }
        });

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.topMargin = readerTopInset() + dp(8);
        topLp.leftMargin = dp(12);
        topLp.rightMargin = dp(12);
        frame.addView(readerTopBar, topLp);

        readerBottomBar = new LinearLayout(this);
        readerBottomBar.setOrientation(LinearLayout.VERTICAL);
        readerBottomBar.setPadding(dp(10), dp(8), dp(10), dp(10));
        UiKit.styleCard(this, readerBottomBar, menuBg, 24, 10);
        readerBottomBar.setClickable(true);
        readerBottomBar.setVisibility(View.GONE);

        seekPanel = new LinearLayout(this);
        seekPanel.setOrientation(LinearLayout.VERTICAL);
        seekPanel.setVisibility(View.GONE);
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView start = new TextView(this);
        start.setText("0%");
        start.setTextColor(menuMuted);
        seekRow.addView(start, new LinearLayout.LayoutParams(dp(42), dp(44)));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgress((int) (book.progress * 1000f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                pendingSeekProgress = progress / 1000f;
                updateProgressText(pendingSeekProgress);
                mainHandler.removeCallbacks(seekPreviewRunnable);
                mainHandler.postDelayed(seekPreviewRunnable, SEEK_PREVIEW_DELAY_MS);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                seekTracking = true;
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                seekTracking = false;
                mainHandler.removeCallbacks(seekPreviewRunnable);
                loadCacheWindowAtProgress(seekBar.getProgress() / 1000f);
            }
        });
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView end = new TextView(this);
        end.setText(R.string.percent_end);
        end.setTextColor(menuMuted);
        end.setGravity(Gravity.END);
        seekRow.addView(end, new LinearLayout.LayoutParams(dp(52), dp(44)));
        seekPanel.addView(seekRow);

        readerBottomBar.addView(seekPanel);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton catalog = makeIconButton();
        catalog.setImageResource(R.drawable.ic_toc);
        catalog.setContentDescription("目录");
        UiKit.styleIconButton(this, catalog, menuFg,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 14);
        catalog.setOnClickListener(v -> openCatalogPage());
        nav.addView(catalog, new LinearLayout.LayoutParams(dp(44), dp(46)));

        ImageButton bookmark = makeIconButton();
        bookmark.setImageResource(R.drawable.ic_bookmark);
        bookmark.setContentDescription("添加书签");
        UiKit.styleIconButton(this, bookmark, menuFg,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 14);
        bookmark.setOnClickListener(v -> saveCurrentBookmark());
        LinearLayout.LayoutParams bookmarkLp = new LinearLayout.LayoutParams(dp(44), dp(46));
        bookmarkLp.leftMargin = dp(4);
        nav.addView(bookmark, bookmarkLp);

        LinearLayout progressControls = new LinearLayout(this);
        progressControls.setGravity(Gravity.CENTER_VERTICAL);
        progressControls.setPadding(dp(4), dp(4), dp(4), dp(4));
        progressControls.setBackground(UiKit.rounded(this,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 15));

        progressButton = makeButton("");
        progressButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        progressButton.setGravity(Gravity.CENTER);
        progressButton.setIncludeFontPadding(false);
        progressButton.setSingleLine(true);
        progressButton.setPadding(0, 0, 0, 0);
        UiKit.styleButton(this, progressButton, Color.TRANSPARENT, menuFg, 11);
        progressButton.setOnClickListener(v -> toggleSeekPanel());

        int[] progressIcons = {R.drawable.ic_progress_back, R.drawable.ic_progress_back_fine,
                R.drawable.ic_progress_forward_fine, R.drawable.ic_progress_forward};
        String[] progressDescriptions = {"后退 1%", "后退 0.01%", "前进 0.01%", "前进 1%"};
        float[] progressSteps = {-1f, -0.01f, 0.01f, 1f};
        for (int i = 0; i < progressIcons.length; i++) {
            if (i == 2) {
                progressControls.addView(progressButton,
                        new LinearLayout.LayoutParams(0, dp(38), 1));
            }
            ImageButton step = makeIconButton();
            step.setImageResource(progressIcons[i]);
            step.setContentDescription(progressDescriptions[i]);
            UiKit.styleIconButton(this, step, menuFg, Color.TRANSPARENT, 11);
            step.setPadding(dp(6), dp(6), dp(6), dp(6));
            configureRepeatingProgressStep(step, progressSteps[i]);
            step.setVisibility(View.GONE);
            progressStepButtons.add(step);
            progressControls.addView(step, new LinearLayout.LayoutParams(dp(36), dp(38)));
        }
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        progressLp.leftMargin = dp(6);
        nav.addView(progressControls, progressLp);
        readerBottomBar.addView(nav);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bottomLp.bottomMargin = readerBottomInset(readingFontSize());
        bottomLp.leftMargin = dp(12);
        bottomLp.rightMargin = dp(12);
        frame.addView(readerBottomBar, bottomLp);

        if (temporarySearchReading && searchSession != null) {
            ImageButton returnToSearch = makeIconButton();
            returnToSearch.setImageResource(R.drawable.ic_arrow_back);
            UiKit.styleIconButton(this, returnToSearch, menuFg, menuBg, 16);
            returnToSearch.setContentDescription("返回搜索结果");
            returnToSearch.setOnClickListener(v -> returnToSearchPage());
            FrameLayout.LayoutParams returnLp = new FrameLayout.LayoutParams(
                    dp(48), dp(48), Gravity.TOP | Gravity.START);
            returnLp.leftMargin = dp(12);
            returnLp.topMargin = readerTopInset();
            frame.addView(returnToSearch, returnLp);
        }

        setContentView(frame);
        long requestedOffset = book.offset;
        frame.post(this::alignMenusToReaderViewport);
        loadCacheWindowAtOffset(requestedOffset, true);
        if (readerMenusOpen) {
            showReaderMenusImmediately();
        }
        scheduleAutoPage();
    }

    private void openSearchPage() {
        if (currentBook == null) return;
        if (syncCoordinator != null) syncCoordinator.setReaderActive(false, false);
        saveCurrentProgress();
        flushReaderCacheWrite();
        cancelReaderPipelineWork();
        releasePageSnapshot();
        searchOpen = true;
        temporarySearchReading = false;
        searchSession = null;
        showSearchPage();
    }

    private boolean isAutoTocEnabled() {
        return preferences != null && preferences.getBoolean(KEY_AUTO_TOC, false);
    }

    private void scheduleMissingTocGeneration() {
        for (Book book : new ArrayList<>(books)) {
            tocExecutor.execute(() -> {
                if (!isAutoTocEnabled()) return;
                if (tocStore.read(book) != null) return;
                try {
                    TocDocument document = TocGenerator.generate(
                            new File(book.path), book.encoding);
                    if (!isAutoTocEnabled()) return;
                    tocStore.write(book, document);
                } catch (Exception ignored) {
                    // A failed book must not stop indexing the rest of the shelf.
                }
            });
        }
    }

    private void generateTocInBackground(Book book, boolean notify) {
        tocExecutor.execute(() -> {
            try {
                TocDocument document = TocGenerator.generate(new File(book.path), book.encoding);
                tocStore.write(book, document);
                if (notify) mainHandler.post(() -> {
                    Toast.makeText(this,
                            document.entries.isEmpty() ? "未识别到目录" : "目录已生成",
                            Toast.LENGTH_SHORT).show();
                    if (catalogOpen && currentBook == book && !document.entries.isEmpty()) {
                        showCatalogPage(book, document);
                    }
                });
            } catch (Exception error) {
                if (notify) mainHandler.post(() -> Toast.makeText(this,
                        "目录生成失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openCatalogPage() {
        if (currentBook == null) return;
        if (syncCoordinator != null) syncCoordinator.setReaderActive(false, false);
        TocDocument document = tocStore.read(currentBook);
        saveCurrentProgress();
        disableAutoPage();
        cancelReaderPipelineWork();
        releasePageSnapshot();
        catalogOpen = true;
        showCatalogPage(currentBook, document);
    }

    private void saveCurrentBookmark() {
        if (currentBook == null) return;
        saveCurrentProgress();
        boolean added = bookmarkStore.add(currentBook.id, currentBook.offset);
        Toast.makeText(this, added ? "书签已保存" : "当前位置已有书签",
                Toast.LENGTH_SHORT).show();
    }

    private void showCatalogPage(Book book, TocDocument document) {
        int theme = appTheme();
        applyWindowColors(theme);
        boolean dark = isDarkTheme(theme);
        int background = backgroundColor(theme);
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int variant = dark ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT;
        int text = textColor(theme);
        int muted = dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(12), statusBarHeight() + dp(10), dp(12), navigationBarHeight() + dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = makeIconButton();
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setContentDescription("返回阅读");
        UiKit.styleIconButton(this, back, text, variant, 14);
        back.setOnClickListener(v -> onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText(shortBookTitle(book.title));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        UiKit.styleTitle(title, text, 22);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(UiKit.rounded(this, surface, 18));
        Button directoryTab = makeButton("目录");
        Button bookmarkTab = makeButton("书签");
        UiKit.styleButton(this, directoryTab, variant, accent, 14);
        UiKit.styleButton(this, bookmarkTab, Color.TRANSPARENT, text, 14);
        tabs.addView(directoryTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams bookmarkTabLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        bookmarkTabLp.leftMargin = dp(4);
        tabs.addView(bookmarkTab, bookmarkTabLp);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        tabsLp.topMargin = dp(8);
        root.addView(tabs, tabsLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        directoryTab.setOnClickListener(v -> {
            UiKit.styleButton(this, directoryTab, variant, accent, 14);
            UiKit.styleButton(this, bookmarkTab, Color.TRANSPARENT, text, 14);
            renderDirectoryTab(content, book, document, surface, variant, text, muted, accent);
        });
        bookmarkTab.setOnClickListener(v -> {
            UiKit.styleButton(this, directoryTab, Color.TRANSPARENT, text, 14);
            UiKit.styleButton(this, bookmarkTab, variant, accent, 14);
            renderBookmarkTab(content, book, surface, text, muted, accent);
        });

        setContentView(root);
        renderDirectoryTab(content, book, document, surface, variant, text, muted, accent);
    }

    private void renderDirectoryTab(LinearLayout list, Book book, TocDocument document,
                                    int surface, int variant, int text, int muted, int accent) {
        list.removeAllViews();
        if (document == null || document.entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("目录不存在，请手工生成目录");
            empty.setTextColor(muted);
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setPadding(dp(16), dp(38), dp(16), dp(20));
            list.addView(empty);
            Button generate = makeButton("生成目录");
            UiKit.styleButton(this, generate, variant, accent, 16);
            generate.setOnClickListener(v -> {
                generateTocInBackground(book, true);
                Toast.makeText(this, "正在后台生成目录", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams generateLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            generateLp.leftMargin = dp(30);
            generateLp.rightMargin = dp(30);
            list.addView(generate, generateLp);
            return;
        }
        for (TocEntry entry : document.entries) {
            TextView item = new TextView(this);
            item.setText(entry.title);
            item.setTextColor(entry.level == 1 ? text : muted);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, entry.level == 1 ? 17 : 15);
            item.setTypeface(entry.level == 1
                    ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16 + (entry.level - 1) * 18), dp(12), dp(14), dp(12));
            item.setBackground(UiKit.interactive(this,
                    entry.level == 1 ? variant : surface, entry.level == 1 ? 14 : 8,
                    UiKit.withAlpha(accent, 28)));
            item.setOnClickListener(v -> jumpToCatalogOffset(book, entry.offset));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = entry.level == 1 ? dp(10) : dp(2);
            list.addView(item, lp);
        }
    }

    private void renderBookmarkTab(LinearLayout list, Book book, int surface,
                                   int text, int muted, int accent) {
        list.removeAllViews();
        List<Bookmark> bookmarks = bookmarkStore.load(book.id);
        if (bookmarks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有书签\n在阅读页点击书签按钮即可保存当前位置");
            empty.setTextColor(muted);
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setLineSpacing(dp(6), 1f);
            empty.setPadding(dp(16), dp(48), dp(16), dp(20));
            list.addView(empty);
            return;
        }
        for (int index = 0; index < bookmarks.size(); index++) {
            Bookmark bookmark = bookmarks.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(UiKit.interactive(this, surface, 14,
                    UiKit.withAlpha(accent, 28)));
            TextView name = new TextView(this);
            name.setText("书签 " + (index + 1));
            UiKit.styleTitle(name, text, 16);
            row.addView(name);
            float percent = book.fileSize <= 0 ? 0f
                    : bookmark.offset * 100f / book.fileSize;
            TextView detail = new TextView(this);
            detail.setText(String.format(Locale.getDefault(), "%.2f%% · %s",
                    percent, bookmarkDisplayTime(bookmark.createdAt)));
            detail.setTextColor(muted);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            detail.setPadding(0, dp(6), 0, 0);
            row.addView(detail);
            row.setOnClickListener(v -> jumpToCatalogOffset(book, bookmark.offset));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
    }

    private String bookmarkDisplayTime(long timestamp) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - timestamp);
        if (elapsed < 60L * 60L * 1000L) return "刚刚";
        return relativeReadTime(timestamp);
    }

    private void jumpToCatalogOffset(Book book, long offset) {
        temporarySearchReading = false;
        searchOpen = false;
        searchSession = null;
        applyFormalProgress(book, offset, null);
        saveBooks();
        catalogOpen = false;
        showReader(book);
    }

    private void openSettingsPage(int initialTab) {
        settingsOpenedFromLibrary = currentBook == null;
        if (currentBook != null) saveCurrentProgress();
        if (syncCoordinator != null) syncCoordinator.setReaderActive(false, false);
        disableAutoPage();
        cancelReaderPipelineWork();
        releasePageSnapshot();
        settingsOpen = true;
        applyKeepScreenOn(false);
        showSettingsPage(currentBook, initialTab);
    }

    private void showSettingsPage(Book book, int initialTab) {
        currentSettingsTab = initialTab;
        int theme = appTheme();
        applyWindowColors(theme);
        boolean dark = isDarkTheme(theme);
        int background = backgroundColor(theme);
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int text = textColor(theme);
        int muted = dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
        int accentContainer = dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;
        int surfaceVariant = dark ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(8), statusBarHeight() + dp(12), dp(8), navigationBarHeight() + dp(12));

        FrameLayout navigation = new FrameLayout(this);
        navigation.setPadding(dp(6), dp(6), dp(6), dp(6));
        UiKit.styleCard(this, navigation, surfaceVariant, 22, 2);

        ImageButton back = makeIconButton();
        back.setImageResource(R.drawable.ic_arrow_back);
        UiKit.styleIconButton(this, back, text, surface, 14);
        back.setContentDescription(settingsOpenedFromLibrary ? "返回书架" : "返回阅读");
        back.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.CENTER_VERTICAL | Gravity.START);
        navigation.addView(back, backLp);

        boolean showGeneral = initialTab == SETTINGS_GENERAL;
        boolean showReading = initialTab == SETTINGS_READING;
        boolean showSync = initialTab == SETTINGS_SYNC;
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(UiKit.rounded(this, surface, 18));
        Button general = makeSettingsNavButton("常规", showGeneral, text, accent,
                accentContainer);
        tabs.addView(general, new LinearLayout.LayoutParams(dp(72), dp(36)));

        Button reading = makeSettingsNavButton("阅读", showReading, text, accent,
                accentContainer);
        LinearLayout.LayoutParams readingLp = new LinearLayout.LayoutParams(dp(72), dp(36));
        tabs.addView(reading, readingLp);
        Button sync = makeSettingsNavButton("同步", showSync, text, accent,
                accentContainer);
        tabs.addView(sync, new LinearLayout.LayoutParams(dp(72), dp(36)));
        FrameLayout.LayoutParams tabsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
                Gravity.CENTER_VERTICAL | Gravity.END);
        navigation.addView(tabs, tabsLp);
        general.setOnClickListener(v -> {
            currentSettingsTab = SETTINGS_GENERAL;
            styleSettingsNavButton(general, true, text, accent, accentContainer);
            styleSettingsNavButton(reading, false, text, accent, accentContainer);
            styleSettingsNavButton(sync, false, text, accent, accentContainer);
            renderGeneralSettings(surface, text, muted, accent);
        });
        reading.setOnClickListener(v -> {
            currentSettingsTab = SETTINGS_READING;
            styleSettingsNavButton(general, false, text, accent, accentContainer);
            styleSettingsNavButton(reading, true, text, accent, accentContainer);
            styleSettingsNavButton(sync, false, text, accent, accentContainer);
            renderReadingSettings(surface, text, muted, accent, accentContainer);
        });
        sync.setOnClickListener(v -> {
            currentSettingsTab = SETTINGS_SYNC;
            styleSettingsNavButton(general, false, text, accent, accentContainer);
            styleSettingsNavButton(reading, false, text, accent, accentContainer);
            styleSettingsNavButton(sync, true, text, accent, accentContainer);
            renderSyncSettings(surface, text, muted, accent, accentContainer);
        });

        root.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setPadding(0, dp(4), 0, dp(16));
        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.addView(settingsContent);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        contentLp.topMargin = dp(8);
        root.addView(settingsScroll, contentLp);

        setContentView(root);
        if (showGeneral) renderGeneralSettings(surface, text, muted, accent);
        else if (showReading) renderReadingSettings(surface, text, muted, accent, accentContainer);
        else renderSyncSettings(surface, text, muted, accent, accentContainer);
    }

    private Button makeSettingsNavButton(String label, boolean selected, int text, int accent,
                                         int accentContainer) {
        Button button = makeButton(label);
        styleSettingsNavButton(button, selected, text, accent, accentContainer);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void styleSettingsNavButton(Button button, boolean selected, int text, int accent,
                                        int accentContainer) {
        UiKit.styleButton(this, button, selected ? accentContainer : Color.TRANSPARENT,
                selected ? accent : text, 14);
    }


    private void renderGeneralSettings(int surface, int text, int muted, int accent) {
        if (settingsContent == null) return;
        settingsContent.removeAllViews();
        int accentContainer = isDarkTheme(appTheme())
                ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;
        LinearLayout theme = createSettingsSection("应用主题",
                "应用内的书架、阅读、搜索和设置页面都会使用此主题。",
                surface, text, muted);
        addChoiceButtons(settingsControl(theme), new String[]{"浅色", "深色"},
                new int[]{THEME_LIGHT, THEME_DARK}, appTheme(), value -> {
                    setAppTheme(value);
                    showSettingsPage(currentBook, SETTINGS_GENERAL);
                }, text, accent,
                accentContainer,
                surface);
        settingsContent.addView(theme, settingsSectionLayoutParams());
        LinearLayout toc = createSettingsSection("TXT 自动生成目录",
                "开启后在后台识别卷、章、节并保存对应的精确字节位置。",
                surface, text, muted);
        Button tocToggle = makeSettingsToggleButton(isAutoTocEnabled(), text, accent,
                accentContainer, surface);
        tocToggle.setContentDescription("TXT 自动生成目录");
        tocToggle.setOnClickListener(v -> {
            boolean enabled = !isAutoTocEnabled();
            preferences.edit().putBoolean(KEY_AUTO_TOC, enabled).apply();
            styleSettingsToggleButton(tocToggle, enabled, text, accent, accentContainer, surface);
            if (enabled) scheduleMissingTocGeneration();
        });
        settingsControl(toc).addView(tocToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        settingsContent.addView(toc, settingsSectionLayoutParams());
        settingsScroll.scrollTo(0, 0);
    }

    private void renderSyncSettingsForCurrentTheme() {
        int theme = appTheme();
        boolean dark = isDarkTheme(theme);
        renderSyncSettings(dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE,
                textColor(theme), dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED,
                dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT,
                dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER);
    }

    private void renderSyncSettings(int surface, int text, int muted, int accent,
                                    int accentContainer) {
        if (settingsContent == null || settingsScroll == null || syncCoordinator == null) return;
        settingsContent.removeAllViews();
        int variant = isDarkTheme(appTheme())
                ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT;

        LinearLayout serverCard = makeSyncCard("同步服务器",
                "仅支持 HTTPS。修改后会先从新服务器拉取云端状态。", surface, text, muted);
        EditText serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setText(syncCoordinator.serverUrl());
        serverInput.setTextColor(text);
        serverInput.setHintTextColor(muted);
        serverInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        serverInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setBackground(UiKit.rounded(this, variant, 12));
        serverInput.setPadding(dp(12), 0, dp(12), 0);
        serverCard.addView(serverInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        Button saveServer = makeButton("保存服务器地址");
        UiKit.styleButton(this, saveServer, accentContainer, accent, 14);
        saveServer.setOnClickListener(v -> {
            String previousUrl = syncCoordinator.serverUrl();
            String requestedUrl = serverInput.getText().toString();
            syncCoordinator.saveServerUrl(requestedUrl, result -> {
                    if (!result.isSuccess()) {
                        serverInput.setError("请输入不含查询参数的 HTTPS 地址");
                    } else {
                        boolean changed = !previousUrl.equals(
                                SyncServerConfig.normalize(requestedUrl));
                        Toast.makeText(this, changed
                                        ? "地址已保存，请在新服务器上重新开启同步"
                                        : "服务器地址已保存",
                                Toast.LENGTH_LONG).show();
                    }
                });
        });
        LinearLayout.LayoutParams saveServerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        saveServerLp.topMargin = dp(8);
        serverCard.addView(saveServer, saveServerLp);
        settingsContent.addView(serverCard, syncCardLayoutParams());

        SyncUiState state = syncUiState == null ? syncCoordinator.state() : syncUiState;
        boolean tokenRequired = state != null
                && state.availability == SyncAvailability.TOKEN_REQUIRED;
        if (state == null || !state.enabled || tokenRequired) {
            LinearLayout enableCard = makeSyncCard("阅读进度同步",
                    tokenRequired
                            ? "同步凭据已失效，请重新输入账户信息开启。TXT 内容和文件名不会上传。"
                            : "输入账户信息后可在不同设备间同步阅读进度。TXT 内容和文件名不会上传。",
                    surface, text, muted);
            TextView accountLabel = makeSyncFieldLabel("账户信息", text);
            enableCard.addView(accountLabel);
            EditText emailInput = new EditText(this);
            emailInput.setSingleLine(true);
            emailInput.setHint("邮箱");
            if (tokenRequired) emailInput.setText(state.email);
            styleSyncInput(emailInput, text, muted, variant,
                    android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            enableCard.addView(emailInput, syncFieldLayoutParams());
            TextView deviceLabel = makeSyncFieldLabel("设备信息", text);
            LinearLayout.LayoutParams deviceLabelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            deviceLabelLp.topMargin = dp(10);
            enableCard.addView(deviceLabel, deviceLabelLp);
            EditText deviceInput = new EditText(this);
            deviceInput.setSingleLine(true);
            deviceInput.setHint("设备名称");
            deviceInput.setText(syncCoordinator.deviceName());
            styleSyncInput(deviceInput, text, muted, variant,
                    android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
            enableCard.addView(deviceInput, syncFieldLayoutParams());
            Button enable = makeButton(state != null && state.busy ? "正在保存…" : "保存");
            UiKit.styleButton(this, enable, accentContainer, accent, 14);
            enable.setEnabled(state == null || !state.busy);
            enable.setOnClickListener(v -> syncCoordinator.startSync(
                    emailInput.getText().toString(), deviceInput.getText().toString(), result -> {
                        if (!result.isSuccess()) {
                            if ("INVALID_EMAIL".equals(result.errorCode)) {
                                emailInput.setError("请输入有效邮箱");
                            } else if ("INVALID_DEVICE_NAME".equals(result.errorCode)) {
                                deviceInput.setError("请输入设备名称（最多 80 个字符）");
                            } else {
                                showSyncActionError(result.errorCode);
                            }
                        } else {
                            Toast.makeText(this, "同步已开启", Toast.LENGTH_SHORT).show();
                        }
                    }));
            LinearLayout.LayoutParams enableLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            enableLp.topMargin = dp(8);
            enableCard.addView(enable, enableLp);
            TextView syncNote = new TextView(this);
            syncNote.setText("服务端会为邮箱创建或返回已有同步 Token，设备名称用于区分不同设备。");
            syncNote.setTextColor(muted);
            syncNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            syncNote.setPadding(0, dp(10), 0, 0);
            enableCard.addView(syncNote);
            if (tokenRequired) {
                Button disable = makeButton("关闭本机同步");
                UiKit.styleButton(this, disable, variant, text, 14);
                disable.setOnClickListener(v -> confirmDisableSync());
                LinearLayout.LayoutParams disableLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                disableLp.topMargin = dp(6);
                enableCard.addView(disable, disableLp);
            }
            settingsContent.addView(enableCard, syncCardLayoutParams());
            settingsScroll.scrollTo(0, 0);
            return;
        }

        LinearLayout statusCard = makeSyncCard("同步状态",
                syncAvailabilityText(state.availability), surface, text, muted);
        addSyncDetail(statusCard, "邮箱", state.email, text, muted);
        addSyncDetail(statusCard, "当前设备", state.deviceName, text, muted);
        addSyncDetail(statusCard, "最后成功同步", formatSyncTime(state.lastSuccessAtMs),
                text, muted);
        settingsContent.addView(statusCard, syncCardLayoutParams());

        LinearLayout actions = makeSyncCard("管理",
                "刷新只拉取云端状态，不会上传本机进度。", surface, text, muted);
        addSyncActionButton(actions, "刷新云端状态", false, text, accent, accentContainer,
                variant, () -> syncCoordinator.refreshRemote(result -> {
                    if (result.isSuccess()) Toast.makeText(this, "云端状态已刷新",
                            Toast.LENGTH_SHORT).show();
                    else showSyncActionError(result.errorCode);
                }));
        addSyncActionButton(actions, "设备管理", false, text, accent, accentContainer,
                variant, this::showSyncDevices);
        addSyncActionButton(actions, "关闭本机同步", false, text, accent, accentContainer,
                variant, this::confirmDisableSync);
        addSyncActionButton(actions, "删除云端阅读进度", true, text, accent, accentContainer,
                variant, this::confirmDeleteRemoteProgress);
        settingsContent.addView(actions, syncCardLayoutParams());
        settingsScroll.scrollTo(0, 0);
    }

    private TextView makeSyncFieldLabel(String label, int text) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return view;
    }

    private void styleSyncInput(EditText input, int text, int muted, int variant, int inputType) {
        input.setTextColor(text);
        input.setHintTextColor(muted);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setInputType(inputType);
        input.setBackground(UiKit.rounded(this, variant, 12));
        input.setPadding(dp(12), 0, dp(12), 0);
    }

    private LinearLayout.LayoutParams syncFieldLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        params.topMargin = dp(5);
        return params;
    }

    private LinearLayout makeSyncCard(String titleText, String description, int surface,
                                      int text, int muted) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        UiKit.styleCard(this, card, surface, 20, 1);
        TextView title = new TextView(this);
        UiKit.styleTitle(title, text, 15);
        title.setText(titleText);
        card.addView(title);
        TextView body = new TextView(this);
        body.setText(description);
        body.setTextColor(muted);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setPadding(0, dp(5), 0, dp(10));
        card.addView(body);
        return card;
    }

    private LinearLayout.LayoutParams syncCardLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
    }

    private void addSyncDetail(LinearLayout parent, String label, String value,
                               int text, int muted) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(muted);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView detail = new TextView(this);
        detail.setText(value);
        detail.setTextColor(text);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(detail, new LinearLayout.LayoutParams(0, dp(34), 2));
        parent.addView(row);
    }

    private void addSyncActionButton(LinearLayout parent, String label, boolean destructive,
                                     int text, int accent, int accentContainer, int variant,
                                     Runnable action) {
        Button button = makeButton(label);
        int background = destructive
                ? (isDarkTheme(appTheme()) ? Color.rgb(86, 37, 38) : Color.rgb(255, 226, 225))
                : variant;
        int foreground = destructive
                ? (isDarkTheme(appTheme()) ? Color.rgb(255, 180, 178) : Color.rgb(150, 24, 27))
                : text;
        UiKit.styleButton(this, button, background, foreground, 14);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        params.topMargin = dp(6);
        parent.addView(button, params);
    }

    private String syncAvailabilityText(SyncAvailability availability) {
        if (availability == SyncAvailability.OFFLINE) return "离线";
        if (availability == SyncAvailability.SERVICE_UNAVAILABLE) return "服务暂不可用";
        if (availability == SyncAvailability.TOKEN_REQUIRED) return "需要重新开启";
        return "已连接";
    }

    private String formatSyncTime(long timestamp) {
        if (timestamp <= 0L) return "尚未成功同步";
        long minutes = Math.max(0L, (System.currentTimeMillis() - timestamp) / 60_000L);
        if (minutes < 1L) return "刚刚";
        if (minutes < 60L) return minutes + " 分钟前";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + " 小时前";
        return hours / 24L + " 天前";
    }

    private void showSyncActionError(String code) {
        String message;
        if ("OFFLINE".equals(code) || "CONNECTION_FAILED".equals(code)) {
            message = "网络不可用，请稍后重试";
        } else if ("TOKEN_REQUIRED".equals(code)) {
            message = "同步凭据已失效，请重新开启";
        } else if ("INVALID_SERVER_URL".equals(code)) {
            message = "服务器地址必须是有效的 HTTPS 地址";
        } else {
            message = "同步服务暂不可用，请稍后重试";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void confirmDisableSync() {
        new AlertDialog.Builder(this)
                .setTitle("关闭本机同步？")
                .setMessage("只会清除本机同步凭据，书籍和本地阅读进度会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("关闭", (dialog, which) -> syncCoordinator.disableSync(
                        result -> Toast.makeText(this, "本机同步已关闭",
                                Toast.LENGTH_SHORT).show()))
                .show();
    }

    private void confirmDeleteRemoteProgress() {
        new AlertDialog.Builder(this)
                .setTitle("删除云端阅读进度？")
                .setMessage("此操作无法撤销，但不会删除本机书籍和本地阅读进度。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) ->
                        syncCoordinator.deleteRemoteProgress(result -> {
                            if (result.isSuccess()) Toast.makeText(this, "云端阅读进度已删除",
                                    Toast.LENGTH_SHORT).show();
                            else showSyncActionError(result.errorCode);
                        }))
                .show();
    }

    private void showSyncDevices() {
        syncCoordinator.loadDevices(result -> {
            if (!result.isSuccess()) {
                showSyncActionError(result.errorCode);
                return;
            }
            List<SyncDevice> devices = result.value;
            if (devices.isEmpty()) {
                Toast.makeText(this, "没有可管理的设备", Toast.LENGTH_SHORT).show();
                return;
            }
            String currentId = syncUiState == null ? "" : syncUiState.deviceId;
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                SyncDevice device = devices.get(i);
                labels[i] = device.deviceName + (device.deviceId.equals(currentId) ? "（本机）" : "")
                        + (device.revoked ? " · 已移除" : "");
            }
            new AlertDialog.Builder(this)
                    .setTitle("设备管理")
                    .setItems(labels, (dialog, which) -> {
                        SyncDevice device = devices.get(which);
                        if (device.deviceId.equals(currentId) || device.revoked) return;
                        confirmRevokeDevice(device);
                    })
                    .setNegativeButton("完成", null)
                    .show();
        });
    }

    private void confirmRevokeDevice(SyncDevice device) {
        new AlertDialog.Builder(this)
                .setTitle("移除“" + device.deviceName + "”？")
                .setMessage("该设备需要重新输入邮箱才能再次同步。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> syncCoordinator.revokeDevice(
                        device.deviceId, result -> {
                            if (result.isSuccess()) Toast.makeText(this, "设备已移除",
                                    Toast.LENGTH_SHORT).show();
                            else showSyncActionError(result.errorCode);
                        }))
                .show();
    }

    private void renderReadingSettings(int surface, int text,
                                       int muted, int accent, int accentContainer) {
        if (settingsContent == null || settingsScroll == null) return;
        boolean dark = isDarkTheme(appTheme());
        settingsContent.removeAllViews();
        LinearLayout keepAwake = createSettingsSection("阅读时锁屏",
                "开启后阻止系统在阅读期间自动锁屏，离开阅读页后恢复系统行为。", surface, text, muted);
        // Match the dark-mode thumb to the inner rounded surface used by the
        // font selector, rather than the lighter control-container background.
        int switchThumbColor = dark ? surface : Color.WHITE;
        int switchEnabledTrackColor = dark
                ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;
        int switchDisabledTrackColor = dark
                ? UiKit.DARK_SWITCH_DISABLED_TRACK : UiKit.LIGHT_SWITCH_DISABLED_TRACK;
        FrameLayout keepToggle = makeSettingsSwitchControl(readingKeepScreenOn(),
                switchThumbColor, switchEnabledTrackColor, switchDisabledTrackColor);
        keepToggle.setContentDescription("阅读时锁屏");
        keepToggle.setOnClickListener(v -> {
            boolean enabled = !readingKeepScreenOn();
            setReadingKeepScreenOn(enabled);
            styleSettingsSwitchControl(keepToggle, enabled, switchEnabledTrackColor,
                    switchDisabledTrackColor);
        });
        settingsControl(keepAwake).addView(keepToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        settingsContent.addView(keepAwake, settingsSectionLayoutParams());

        LinearLayout autoPage = createSettingsSection("自动翻页（秒）",
                "设置顶部自动翻页按钮开启后的翻页间隔；开启状态不会保存。", surface, text, muted);
        TextView autoPageValue = new TextView(this);
        autoPageValue.setText(String.valueOf(readingAutoPageInterval()));
        addStepper(settingsControl(autoPage), "−", "+", autoPageValue, false,
                () -> {
                    int interval = Math.max(AutoPageOptions.MIN_SECONDS,
                            readingAutoPageInterval() - 1);
                    setReadingAutoPageInterval(interval);
                    autoPageValue.setText(String.valueOf(interval));
                }, () -> {
                    int interval = Math.min(AutoPageOptions.MAX_SECONDS,
                            readingAutoPageInterval() + 1);
                    setReadingAutoPageInterval(interval);
                    autoPageValue.setText(String.valueOf(interval));
                }, text, accent, accentContainer, surface);
        settingsContent.addView(autoPage, settingsSectionLayoutParams());

        LinearLayout sensitivity = createSettingsSection("触摸灵敏度",
                "调整点击区域和滑动翻页的触发阈值。", surface, text, muted);
        addChoiceButtons(settingsControl(sensitivity), new String[]{"高", "中", "低"},
                new int[]{SENSITIVITY_HIGH, SENSITIVITY_STANDARD, SENSITIVITY_LOW},
                readingSensitivity(), this::setReadingSensitivity,
                text, accent, accentContainer, surface);
        settingsContent.addView(sensitivity, settingsSectionLayoutParams());

        LinearLayout font = createSettingsSection("字体",
                "选择本机系统可用的正文视觉字体。", surface, text, muted);
        addChoiceDropdown(settingsControl(font), new String[]{"系统", "黑体", "宋体", "仿宋", "等宽"},
                new int[]{0, 3, 1, 4, 2}, readingFontFamily(), this::setReadingFontFamily,
                text, surface);
        settingsContent.addView(font, settingsSectionLayoutParams());

        LinearLayout fontSize = createSettingsSection("字号",
                "与阅读页的 A− / A+ 使用相同的两级字号调整。", surface, text, muted);
        TextView fontSizeValue = new TextView(this);
        fontSizeValue.setText(getString(R.string.font_size_value, Math.round(readingFontSize())));
        addStepper(settingsControl(fontSize), "A−", "A+", fontSizeValue, true,
                () -> {
                    float size = ReaderSettingsOptions.normalizeFontSize(
                            readingFontSize() - 2f);
                    setReadingFontSize(size);
                    fontSizeValue.setText(getString(
                            R.string.font_size_value, Math.round(size)));
                }, () -> {
                    float size = ReaderSettingsOptions.normalizeFontSize(
                            readingFontSize() + 2f);
                    setReadingFontSize(size);
                    fontSizeValue.setText(getString(
                            R.string.font_size_value, Math.round(size)));
                }, text, accent, accentContainer, surface);
        settingsContent.addView(fontSize, settingsSectionLayoutParams());

        LinearLayout lineSpacing = createSettingsSection("行间距",
                "调整正文行与行之间的留白。", surface, text, muted);
        TextView spacingValue = new TextView(this);
        spacingValue.setText(getString(R.string.percent_value,
                Math.round(readingLineSpacingRatio() * 100f)));
        spacingValue.setTextColor(accent);
        spacingValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        spacingValue.setGravity(Gravity.CENTER);
        spacingValue.setIncludeFontPadding(false);
        LinearLayout spacingControl = new LinearLayout(this);
        spacingControl.setOrientation(LinearLayout.HORIZONTAL);
        spacingControl.setGravity(Gravity.CENTER_VERTICAL);
        spacingControl.setBackground(UiKit.rounded(this, surface, 14));
        LinearLayout.LayoutParams spacingValueLp = new LinearLayout.LayoutParams(
                dp(44), ViewGroup.LayoutParams.MATCH_PARENT);
        spacingControl.addView(spacingValue, spacingValueLp);
        SeekBar spacingSeek = new SeekBar(this);
        int minSpacingPercent = Math.round(ReaderSettingsOptions.MIN_LINE_SPACING * 100f);
        int maxSpacingPercent = Math.round(ReaderSettingsOptions.MAX_LINE_SPACING * 100f);
        spacingSeek.setMax(maxSpacingPercent - minSpacingPercent);
        spacingSeek.setProgress(Math.round(readingLineSpacingRatio() * 100f)
                - minSpacingPercent);
        spacingSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int spacingPercent = minSpacingPercent + progress;
                setReadingLineSpacingRatio(spacingPercent / 100f);
                spacingValue.setText(getString(R.string.percent_value, spacingPercent));
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        spacingControl.addView(spacingSeek, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        settingsControl(lineSpacing).addView(spacingControl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        settingsContent.addView(lineSpacing, settingsSectionLayoutParams());
        settingsScroll.scrollTo(0, 0);
    }

    private LinearLayout createSettingsSection(String titleText, String description,
                                               int surface, int text, int muted) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(dp(8), dp(8), dp(8), dp(8));
        UiKit.styleCard(this, section, surface, 20, 1);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        UiKit.styleTitle(title, text, 13);
        details.addView(title);
        TextView body = new TextView(this);
        body.setText(description);
        body.setTextColor(muted);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        body.setMinLines(2);
        body.setMaxLines(2);
        body.setPadding(0, dp(3), dp(6), 0);
        details.addView(body);
        section.addView(details, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        control.setPadding(dp(4), dp(4), dp(4), dp(4));
        UiKit.styleCard(this, control,
                isDarkTheme(appTheme()) ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT,
                14, 0);
        section.addView(control, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        section.setTag(control);
        return section;
    }

    private LinearLayout settingsControl(LinearLayout section) {
        return (LinearLayout) section.getTag();
    }

    private LinearLayout.LayoutParams settingsSectionLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        lp.topMargin = dp(8);
        return lp;
    }

    private Button makeSettingsToggleButton(boolean enabled, int text, int accent,
                                            int accentContainer, int surface) {
        Button button = makeButton("");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        styleSettingsToggleButton(button, enabled, text, accent, accentContainer, surface);
        return button;
    }

    private void styleSettingsToggleButton(Button button, boolean enabled, int text, int accent,
                                           int accentContainer, int surface) {
        button.setText(enabled ? "开启" : "关闭");
        UiKit.styleButton(this, button, enabled ? accentContainer : surface,
                enabled ? accent : text, 14);
    }

    private FrameLayout makeSettingsSwitchControl(boolean enabled, int thumbColor,
                                                  int enabledTrackColor,
                                                  int disabledTrackColor) {
        FrameLayout toggle = new FrameLayout(this);
        toggle.setClickable(true);
        View thumb = new View(this);
        thumb.setBackground(UiKit.rounded(this, thumbColor, 15));
        toggle.setTag(thumb);
        toggle.addView(thumb);
        styleSettingsSwitchControl(toggle, enabled, enabledTrackColor, disabledTrackColor);
        toggle.post(() -> styleSettingsSwitchControl(toggle, enabled,
                enabledTrackColor, disabledTrackColor));
        return toggle;
    }

    private void styleSettingsSwitchControl(FrameLayout toggle, boolean enabled,
                                            int enabledTrackColor,
                                            int disabledTrackColor) {
        toggle.setBackground(UiKit.rounded(this,
                enabled ? enabledTrackColor : disabledTrackColor, 18));
        View thumb = (View) toggle.getTag();
        int thumbWidth = toggle.getWidth() > 0
                ? Math.max(dp(30), (toggle.getWidth() - dp(6)) / 2) : dp(56);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(thumbWidth, dp(30),
                Gravity.CENTER_VERTICAL | (enabled ? Gravity.END : Gravity.START));
        thumbLp.leftMargin = dp(3);
        thumbLp.rightMargin = dp(3);
        thumb.setLayoutParams(thumbLp);
    }

    private void addChoiceButtons(LinearLayout parent, String[] labels, int[] values,
                                  int selectedValue, ChoiceListener onSelected, int text,
                                  int accent, int accentContainer, int surface) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        List<Button> buttons = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(2), dp(2), dp(2));
                row.setBackground(UiKit.rounded(this, surface, 14));
                if (i > 0) {
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
                    rowLp.topMargin = dp(8);
                    group.addView(row, rowLp);
                } else {
                    group.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
                }
            }
            int value = values[i];
            boolean selected = value == selectedValue;
            Button button = makeButton(labels[i]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            UiKit.styleButton(this, button, selected ? accentContainer : Color.TRANSPARENT,
                    selected ? accent : text, 14);
            buttons.add(button);
            button.setOnClickListener(v -> {
                onSelected.accept(value);
                for (int index = 0; index < buttons.size(); index++) {
                    boolean active = values[index] == value;
                    UiKit.styleButton(this, buttons.get(index),
                            active ? accentContainer : Color.TRANSPARENT,
                            active ? accent : text, 14);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            if (i % 3 > 0) lp.leftMargin = dp(3);
            row.addView(button, lp);
        }
        parent.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addChoiceDropdown(LinearLayout parent, String[] labels, int[] values,
                                   int selectedValue, ChoiceListener onSelected,
                                   int text, int surface) {
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == selectedValue) {
                selectedIndex = i;
                break;
            }
        }
        final int[] selectedPosition = {selectedIndex};
        Button selector = makeButton(labels[selectedIndex]);
        selector.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        selector.setGravity(Gravity.CENTER);
        selector.setIncludeFontPadding(false);
        selector.setPadding(0, 0, 0, 0);
        UiKit.styleButton(this, selector, surface, text, 14);
        selector.setOnClickListener(v -> {
            LinearLayout menu = new LinearLayout(this);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setPadding(dp(2), dp(2), dp(2), dp(2));
            int popupWidth = selector.getWidth() / 2;
            PopupWindow popup = new PopupWindow(menu, popupWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(UiKit.rounded(this, surface, 14));
            popup.setOutsideTouchable(true);
            popup.setElevation(dp(8));
            int accent = isDarkTheme(appTheme()) ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
            for (int i = 0; i < labels.length; i++) {
                int position = i;
                LinearLayout option = new LinearLayout(this);
                option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                option.setPadding(dp(12), 0, dp(8), 0);
                option.setBackground(UiKit.interactive(this, surface, 12,
                        UiKit.withAlpha(accent, 24)));

                TextView label = new TextView(this);
                label.setText(labels[i]);
                label.setTextColor(text);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                label.setGravity(Gravity.CENTER);
                label.setIncludeFontPadding(false);
                option.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                if (i == selectedPosition[0]) {
                    TextView check = new TextView(this);
                    check.setText("✓");
                    check.setTextColor(accent);
                    check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    check.setGravity(Gravity.CENTER);
                    check.setIncludeFontPadding(false);
                    LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    checkLp.leftMargin = dp(4);
                    option.addView(check, checkLp);
                }
                option.setOnClickListener(ignored -> {
                    selectedPosition[0] = position;
                    selector.setText(labels[position]);
                    onSelected.accept(values[position]);
                    popup.dismiss();
                });
                menu.addView(option, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
            }
            popup.showAsDropDown(selector, (selector.getWidth() - popupWidth) / 2, 0);
        });
        parent.addView(selector, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void addStepper(LinearLayout parent, String minusLabel, String plusLabel,
                            TextView valueView, boolean fontSizeControl,
                            Runnable onMinus, Runnable onPlus,
                            int text, int accent, int accentContainer, int surface) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View minus;
        if (fontSizeControl) {
            Button button = makeButton(minusLabel);
            UiKit.styleButton(this, button, surface, text, 10);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setContentDescription("减小字号");
            button.setGravity(Gravity.CENTER);
            button.setIncludeFontPadding(false);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(v -> onMinus.run());
            minus = button;
        } else {
            ImageButton button = makeIconButton();
            button.setImageResource(R.drawable.ic_step_minus);
            button.setContentDescription("减少自动翻页间隔");
            UiKit.styleIconButton(this, button, text, surface, 10);
            button.setPadding(dp(6), dp(6), dp(6), dp(6));
            button.setOnClickListener(v -> onMinus.run());
            minus = button;
        }
        row.addView(minus, new LinearLayout.LayoutParams(0, dp(40), 1));

        valueView.setGravity(Gravity.CENTER);
        valueView.setTextColor(accent);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        valueView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        valueView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        valueView.setBackground(UiKit.rounded(this, accentContainer, 14));
        row.addView(valueView, valueLp);

        View plus;
        if (fontSizeControl) {
            Button button = makeButton(plusLabel);
            UiKit.styleButton(this, button, surface, text, 10);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setContentDescription("增大字号");
            button.setGravity(Gravity.CENTER);
            button.setIncludeFontPadding(false);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(v -> onPlus.run());
            plus = button;
        } else {
            ImageButton button = makeIconButton();
            button.setImageResource(R.drawable.ic_step_plus);
            button.setContentDescription("增加自动翻页间隔");
            UiKit.styleIconButton(this, button, text, surface, 10);
            button.setPadding(dp(6), dp(6), dp(6), dp(6));
            button.setOnClickListener(v -> onPlus.run());
            plus = button;
        }
        row.addView(plus, new LinearLayout.LayoutParams(0, dp(40), 1));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
    }

    private interface ChoiceListener {
        void accept(int value);
    }

    private void returnToSearchPage() {
        if (currentBook == null || searchSession == null) return;
        saveCurrentProgress();
        flushReaderCacheWrite();
        cancelReaderPipelineWork();
        releasePageSnapshot();
        restorePrimaryReadingPosition();
        temporarySearchReading = false;
        searchOpen = true;
        showSearchPage();
    }

    private void restorePrimaryReadingPosition() {
        if (currentBook == null || searchSession == null) return;
        currentBook.offset = searchSession.returnOffset;
        currentBook.progress = searchSession.returnProgress;
        currentBook.updatedAt = searchSession.returnUpdatedAt;
        saveBooks();
    }

    private void showSearchPage() {
        disableAutoPage();
        applyKeepScreenOn(false);
        if (currentBook == null) return;
        Book book = currentBook;
        int theme = appTheme();
        applyWindowColors(theme);

        int bg = backgroundColor(theme);
        int fg = textColor(theme);
        boolean dark = isDarkTheme(theme);
        int muted = dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int surfaceVariant = dark ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
        int accentContainer = dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), statusBarHeight() + dp(16), dp(20), navigationBarHeight() + dp(12));

        TextView pageTitle = new TextView(this);
        pageTitle.setText("书内搜索");
        UiKit.styleTitle(pageTitle, fg, 28);
        root.addView(pageTitle);

        TextView pageSubtitle = new TextView(this);
        pageSubtitle.setText(book.title);
        pageSubtitle.setSingleLine(true);
        pageSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        pageSubtitle.setTextColor(muted);
        pageSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pageSubtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(pageSubtitle);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = makeIconButton();
        back.setImageResource(R.drawable.ic_arrow_back);
        UiKit.styleIconButton(this, back, fg, surfaceVariant, 16);
        back.setContentDescription("返回阅读");
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setTextColor(fg);
        input.setHintTextColor(muted);
        input.setHint("搜索当前书籍");
        input.setBackground(UiKit.roundedStroke(this, surface,
                UiKit.withAlpha(muted, 80), 17, 1));
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        if (searchSession != null && searchSession.book == book) {
            input.setText(searchSession.query);
            input.setSelection(input.length());
        }
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        inputLp.leftMargin = dp(10);
        top.addView(input, inputLp);

        ImageButton submit = makeIconButton();
        submit.setImageResource(R.drawable.ic_search);
        UiKit.styleIconButton(this, submit,
                dark ? UiKit.DARK_BACKGROUND : Color.WHITE, accent, 16);
        submit.setContentDescription("开始搜索");
        LinearLayout.LayoutParams submitLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        submitLp.leftMargin = dp(10);
        top.addView(submit, submitLp);
        root.addView(top);

        TextView status = new TextView(this);
        status.setText("输入关键词后，从当前阅读位置开始向后搜索");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(muted);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        Button continueFromStart = makeButton("从开头继续搜索");
        UiKit.styleButton(this, continueFromStart, accentContainer, accent, 16);
        continueFromStart.setVisibility(View.GONE);
        continueFromStart.setOnClickListener(v -> continueSearchFromBeginning(
                results, status, continueFromStart));
        root.addView(continueFromStart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.addView(results);
        root.addView(resultScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View.OnClickListener runSearch = v -> startBookSearch(
                input.getText().toString(), results, status, continueFromStart, book);
        submit.setOnClickListener(runSearch);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch.onClick(v);
                return true;
            }
            return false;
        });
        setContentView(root);
        resultScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View content = resultScroll.getChildAt(0);
            if (content == null) return;
            int remaining = content.getBottom() - (resultScroll.getScrollY() + resultScroll.getHeight());
            if (remaining <= dp(24)) {
                loadNextSearchBatch(results, status, continueFromStart);
            }
        });
        renderSearchSession(results, status, continueFromStart, true);
    }

    private void startBookSearch(String query, LinearLayout results, TextView status,
                                 Button continueFromStart, Book book) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            status.setText("请输入至少 2 个字符");
            results.removeAllViews();
            continueFromStart.setVisibility(View.GONE);
            return;
        }
        if (keyword.length() < 2) {
            status.setText("关键词至少需要 2 个字符");
            results.removeAllViews();
            continueFromStart.setVisibility(View.GONE);
            return;
        }
        if (keyword.length() > 32) {
            status.setText(R.string.search_keyword_too_long);
            results.removeAllViews();
            continueFromStart.setVisibility(View.GONE);
            return;
        }
        long startOffset = Math.max(0L, Math.min(book.offset, book.fileSize));
        searchSession = new SearchSession(book, keyword, startOffset, book.fileSize);
        results.removeAllViews();
        loadNextSearchBatch(results, status, continueFromStart);
    }

    private void continueSearchFromBeginning(LinearLayout results, TextView status,
                                            Button continueFromStart) {
        if (searchSession == null || searchSession.loading || !searchSession.needsWrapConfirmation) {
            return;
        }
        searchSession.wrappedToStart = true;
        searchSession.needsWrapConfirmation = false;
        searchSession.nextOffset = 0L;
        loadNextSearchBatch(results, status, continueFromStart);
    }

    private void loadNextSearchBatch(LinearLayout results, TextView status,
                                     Button continueFromStart) {
        SearchSession session = searchSession;
        if (session == null || session.loading || session.complete
                || session.needsWrapConfirmation || !searchOpen) {
            return;
        }
        session.loading = true;
        renderSearchSession(results, status, continueFromStart, false);
        long requestId = ++searchRequestId;
        long endOffset = session.wrappedToStart ? session.originOffset : session.fileSize;
        searchExecutor.execute(() -> {
            SearchBatch batch;
            Exception error = null;
            try {
                batch = searchBookBatch(session.book, session.query, session.nextOffset, endOffset);
            } catch (Exception e) {
                batch = new SearchBatch(new ArrayList<>(), session.nextOffset, true);
                error = e;
            }
            SearchBatch finalBatch = batch;
            Exception finalError = error;
            mainHandler.post(() -> {
                if (!searchOpen || requestId != searchRequestId || searchSession != session
                        || currentBook != session.book) return;
                session.loading = false;
                if (finalError != null) {
                    status.setText(getString(R.string.search_failed, finalError.getMessage()));
                    return;
                }
                session.results.addAll(finalBatch.results);
                for (SearchResult result : finalBatch.results) {
                    results.addView(makeSearchResultRow(result, session.book));
                }
                session.nextOffset = finalBatch.nextOffset;
                if (finalBatch.reachedBoundary) {
                    if (session.wrappedToStart || session.originOffset == 0L) {
                        session.complete = true;
                    } else {
                        session.needsWrapConfirmation = true;
                    }
                }
                renderSearchSession(results, status, continueFromStart, false);
            });
        });
    }

    private void renderSearchSession(LinearLayout results, TextView status,
                                     Button continueFromStart, boolean rebuildResults) {
        SearchSession session = searchSession;
        continueFromStart.setVisibility(View.GONE);
        if (session == null) return;
        if (rebuildResults) {
            results.removeAllViews();
            for (SearchResult result : session.results) {
                results.addView(makeSearchResultRow(result, session.book));
            }
        }
        if (session.loading) {
            status.setText("正在搜索...");
        } else if (session.needsWrapConfirmation) {
            status.setText(getString(R.string.search_wrap_prompt, session.results.size()));
            continueFromStart.setVisibility(View.VISIBLE);
        } else if (session.complete) {
            status.setText(getString(R.string.search_complete, session.results.size()));
        } else if (session.results.isEmpty()) {
            status.setText("继续下滑可搜索下一批结果");
        } else {
            status.setText(getString(R.string.search_loaded, session.results.size()));
        }
    }

    private View makeSearchResultRow(SearchResult result, Book book) {
        boolean dark = isDarkTheme(appTheme());
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
        TextView row = new TextView(this);
        String query = searchSession != null && searchSession.book == book ? searchSession.query : "";
        row.setText(highlightSearchKeyword(result.snippet, query, appTheme()));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        row.setTextColor(textColor(appTheme()));
        row.setLineSpacing(dp(4), 1f);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setBackground(UiKit.interactive(this, surface, 18, UiKit.withAlpha(accent, 26)));
        row.setClipToOutline(true);
        row.setOnClickListener(v -> openSearchResult(book, result.offset));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private CharSequence readerDisplayText(String text, Book book) {
        if (temporarySearchReading && searchSession != null && searchSession.book == book) {
            return highlightSearchKeyword(text, searchSession.query, appTheme());
        }
        return text;
    }

    private CharSequence highlightSearchKeyword(String text, String query, int theme) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(query)) return text;
        SpannableString highlighted = new SpannableString(text);
        int color = isDarkTheme(theme)
                ? Color.rgb(126, 92, 0) : Color.rgb(255, 224, 130);
        int start = 0;
        while (start < text.length()) {
            int index = text.indexOf(query, start);
            if (index < 0) break;
            int end = index + query.length();
            highlighted.setSpan(new BackgroundColorSpan(color), index, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end;
        }
        return highlighted;
    }

    private void openSearchResult(Book book, long offset) {
        searchRequestId++;
        searchOpen = false;
        temporarySearchReading = true;
        book.offset = Math.max(0L, Math.min(offset, Math.max(0L, book.fileSize)));
        book.progress = book.fileSize <= 0 ? 0f : book.offset / (float) book.fileSize;
        showReader(book);
    }

    private SearchBatch searchBookBatch(Book book, String query, long startOffset, long endOffset)
            throws Exception {
        File file = new File(book.path);
        Charset charset = charsetFor(book.encoding);
        ReaderTextSearch.Batch batch = ReaderTextSearch.find(
                file, book.encoding, query, startOffset, endOffset,
                SEARCH_READ_BYTES, SEARCH_RESULT_LIMIT);
        int queryByteLength = query.getBytes(charset).length;
        List<SearchResult> results = new ArrayList<>();
        for (long offset : batch.offsets) {
            results.add(new SearchResult(offset, makeSearchSnippet(
                    file, offset, query, book.encoding, queryByteLength)));
        }
        return new SearchBatch(
                results, batch.nextOffset, batch.reachedBoundary);
    }

    private String makeSearchSnippet(File file, long matchOffset, String query, String encoding,
                                     int queryByteLength) throws Exception {
        long desiredStart = Math.max(0L, matchOffset - SEARCH_CONTEXT_CHARS * 4L);
        long contextStart = findReadableOffset(file, desiredStart, encoding);
        long prefixBytes = Math.max(0L, matchOffset - contextStart);
        if (prefixBytes > SEARCH_SNIPPET_MAX_READ_BYTES / 2L) return query;
        int readLength = (int) Math.min(SEARCH_SNIPPET_MAX_READ_BYTES,
                Math.max(2048L, prefixBytes
                        + SEARCH_CONTEXT_CHARS * 4L + queryByteLength + 1024L));
        CacheSegment context = readSegment(file, contextStart, readLength, encoding);
        int index = context.text.indexOf(query);
        if (index < 0) return query;
        int start = Math.max(0, index - SEARCH_CONTEXT_CHARS);
        int end = Math.min(context.text.length(), index + query.length() + SEARCH_CONTEXT_CHARS);
        return context.text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
    }

    private boolean handlePageSwipe(float dx, float dy) {
        boolean isHorizontalSwipe = Math.abs(dx) >= dp(swipeThresholdDp())
                && Math.abs(dx) > dy * 1.2f;
        if (!isHorizontalSwipe) return false;
        if (dx > 0) {
            pageBackward();
        } else {
            pageForward();
        }
        return true;
    }

    private int tapToleranceDp() {
        if (currentBook == null) return 16;
        return ReaderDisplayPolicy.tapToleranceDp(readingSensitivity());
    }

    private int swipeThresholdDp() {
        if (currentBook == null) return 72;
        return ReaderDisplayPolicy.swipeThresholdDp(readingSensitivity());
    }

    private boolean handleReaderTap(float x) {
        if (readerScroll == null || readerScroll.getWidth() <= 0) return false;
        float ratio = x / readerScroll.getWidth();
        if (ratio >= 0.30f && ratio < 0.60f) {
            showReaderMenus();
            return true;
        }
        if (currentBook == null || !currentBook.pageMode) return false;
        if (ratio < 0.30f) {
            pageBackward();
        } else {
            pageForward();
        }
        return true;
    }

    private void loadCacheWindowAtProgress(float progress) {
        if (currentBook == null) return;
        long size = Math.max(1L, currentBook.fileSize);
        loadCacheWindowAtOffset((long) (Math.max(0f, Math.min(1f, progress)) * size));
    }

    private void loadCacheWindowAtOffset(long targetOffset) {
        loadCacheWindowAtOffset(targetOffset, false);
    }

    private void loadCacheWindowAtOffset(long targetOffset, boolean tryPersistentCache) {
        if (currentBook == null) return;
        long requestId = cancelReaderPipelineWork();
        preferredPageRefillDirection = PAGE_DIRECTION_NONE;
        readerPageWindow.clear();
        Book book = currentBook;
        loadingChunk = true;
        // Freeze progress only for an explicit seek/open until its initial page window is ready.
        suppressProgressSave = true;
        ioExecutor.execute(() -> {
            if (requestId != loadRequestId) return;
            CacheWindowLoad load = null;
            Exception error = null;
            try {
                if (tryPersistentCache) {
                    load = prepareReaderCacheRestore(book, targetOffset);
                }
                if (load == null) load = prepareCacheWindowLoad(book, targetOffset);
            } catch (Exception e) {
                error = e;
            }
            if (requestId != loadRequestId) return;
            CacheWindowLoad finalLoad = load;
            Exception finalError = error;
            mainHandler.post(() -> {
                if (requestId != loadRequestId || currentBook != book) return;
                if (finalError != null) {
                    loadingChunk = false;
                    suppressProgressSave = false;
                    Toast.makeText(this, "打开失败：" + finalError.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                applyCacheWindowLoad(book, finalLoad, !tryPersistentCache);
            });
        });
    }

    /** Invalidates both asynchronous reader pipelines as one lifecycle operation. */
    private long cancelReaderPipelineWork() {
        long requestId = ++loadRequestId;
        cacheStackGeneration++;
        pageBuildRequestId++;
        loadingBackwardSegment = false;
        loadingForwardSegment = false;
        loadingReaderPages = false;
        resettingReaderPages = false;
        loadingChunk = false;
        suppressProgressSave = false;
        return requestId;
    }

    private CacheWindowLoad prepareCacheWindowLoad(Book book, long targetOffset) throws Exception {
        File file = new File(book.path);
        long fileSize = file.length();
        long clampedTarget = fileSize <= 0
                ? 0L
                : Math.max(0L, Math.min(targetOffset, fileSize - 1L));
        requestBookIndex(book, fileSize);
        long windowStart = hasBookIndex(book, fileSize)
                ? indexedReadableOffset(book, file,
                        Math.max(0L, clampedTarget - CACHE_SEGMENT_BYTES))
                : findReadableOffset(file,
                        Math.max(0L, clampedTarget - CACHE_SEGMENT_BYTES),
                        book.encoding);
        ArrayList<CacheSegment> initialSegments = new ArrayList<>(2);
        CacheSegment first = readCachedSegment(
                file, windowStart, CACHE_SEGMENT_BYTES, book.encoding);
        if (first.bytesRead > 0) initialSegments.add(first);
        long secondStart = first.endOffset();
        if (secondStart < fileSize) {
            CacheSegment second = readCachedSegment(
                    file, secondStart, CACHE_SEGMENT_BYTES, book.encoding);
            if (second.bytesRead > 0) initialSegments.add(second);
        }
        CacheCombineStack combineStack =
                new CacheCombineStack(CACHE_SEGMENT_BYTES, CACHE_REFILL_RATIO,
                        charsetFor(book.encoding));
        combineStack.reset(initialSegments);
        CombinedCacheSnapshot combined = combineStack.snapshot();
        if (!combined.isWithinFile(fileSize)
                || (fileSize > 0L && !ReaderPosition.cacheCoversOffset(
                        fileSize, combined.offset, combined.bytesRead, clampedTarget))) {
            throw new IOException("读取窗口未覆盖目标位置或字节映射不一致");
        }
        return new CacheWindowLoad(fileSize, clampedTarget, combineStack, combined);
    }

    private void applyCacheWindowLoad(Book book, CacheWindowLoad load,
                                      boolean formalPositionChange) {
        if (load == null || readerText == null || readerScroll == null) return;
        book.fileSize = load.fileSize;
        currentCombineStack = load.combineStack;
        currentCache = load.cache;
        if (formalPositionChange && !temporarySearchReading) {
            applyFormalProgress(book, load.targetOffset, null);
        } else {
            book.offset = load.targetOffset;
            book.progress = load.fileSize <= 0 ? 0f
                    : load.targetOffset / (float) load.fileSize;
        }
        pageStateGeneration++;
        requestReaderPageWindow(book, load.cache, load.targetOffset);
        saveBooks();
        if (!temporarySearchReading) {
            saveReaderCache(book, load);
        }
        updateProgressText();
        if (seekBar != null && !seekTracking) {
            seekBar.setProgress((int) (book.progress * 1000f));
        }
    }

    private CacheWindowLoad prepareReaderCacheRestore(Book book, long requestedOffset) {
        ReaderCache cache = readReaderCache(book);
        if (cache == null) return null;
        Charset charset = charsetFor(book.encoding);
        CombinedCacheSnapshot cachedWindow = new CombinedCacheSnapshot(
                cache.windowStart, cache.text, cache.bytesRead,
                ByteOffsetMap.create(cache.text, charset));
        if (!cachedWindow.isWithinFile(cache.fileSize)) return null;
        CacheCombineStack combineStack =
                new CacheCombineStack(CACHE_SEGMENT_BYTES, CACHE_REFILL_RATIO, charset);
        combineStack.resetFromCombined(cachedWindow);
        CombinedCacheSnapshot restoredWindow = combineStack.snapshot();
        boolean completeFile = restoredWindow.offset == 0L
                && restoredWindow.endOffset() >= cache.fileSize;
        if (combineStack.segmentCount() < 2 && !completeFile) {
            return null;
        }
        boolean coversOffset = ReaderPosition.cacheCoversOffset(cache.fileSize,
                restoredWindow.offset, restoredWindow.bytesRead, requestedOffset);
        if (!coversOffset) return null;
        long clampedTarget = cache.fileSize <= 0L ? 0L
                : Math.max(0L, Math.min(requestedOffset, cache.fileSize - 1L));
        return new CacheWindowLoad(
                cache.fileSize, clampedTarget, combineStack, restoredWindow);
    }

    private void requestReaderPageWindow(Book book, CombinedCacheSnapshot cache,
                                         long anchorOffset) {
        if (book == null || cache == null || readerText == null || readerScroll == null) return;
        readerScroll.post(() -> {
            if (currentBook != book || currentCache != cache) return;
            ReaderPagePaginator.LayoutSpec spec = currentPageLayoutSpec();
            if (spec == null) {
                readerScroll.post(() -> requestReaderPageWindow(
                        book, cache, anchorOffset));
                return;
            }
            long requestId = ++pageBuildRequestId;
            loadingReaderPages = true;
            resettingReaderPages = true;
            loadingChunk = true;
            suppressProgressSave = true;
            pageExecutor.execute(() -> {
                List<ReaderPage> pages = readerPagePaginator.paginateAround(
                        cache, anchorOffset, spec, PAGE_WINDOW_PREFETCH_EACH_SIDE);
                mainHandler.post(() -> finishReaderPageWindow(
                        book, cache, spec.key, anchorOffset, requestId, pages));
            });
        });
    }

    private ReaderPagePaginator.LayoutSpec currentPageLayoutSpec() {
        if (readerText == null || readerScroll == null) return null;
        int width = readerText.getWidth()
                - readerText.getPaddingLeft() - readerText.getPaddingRight();
        int height = fittedReaderVisibleHeight
                - readerText.getPaddingTop() - readerText.getPaddingBottom();
        if (width <= 0 || height <= 0) return null;
        TextPaint paint = new TextPaint(readerText.getPaint());
        String highlightQuery = temporarySearchReading && searchSession != null
                && searchSession.book == currentBook ? searchSession.query : null;
        int highlightColor = isDarkTheme(appTheme())
                ? Color.rgb(126, 92, 0) : Color.rgb(255, 224, 130);
        return new ReaderPagePaginator.LayoutSpec(
                paint, pageLayoutGeneration, width, height,
                readerText.getLineSpacingExtra(), readerText.getLineSpacingMultiplier(),
                readerText.getBreakStrategy(), readerText.getHyphenationFrequency(),
                highlightQuery, highlightColor);
    }

    private void finishReaderPageWindow(Book book, CombinedCacheSnapshot sourceCache,
                                        long layoutKey, long anchorOffset, long requestId,
                                        List<ReaderPage> pages) {
        if (requestId != pageBuildRequestId || currentBook != book) return;
        loadingReaderPages = false;
        resettingReaderPages = false;
        if (currentCache != sourceCache || pageLayoutGeneration != layoutKey) {
            requestReaderPageWindow(book, currentCache, book.offset);
            return;
        }
        if (pages == null || pages.isEmpty()) {
            loadingChunk = false;
            suppressProgressSave = false;
            return;
        }
        if (!readerPageWindow.reset(pages, anchorOffset, book.fileSize)) {
            loadingChunk = false;
            suppressProgressSave = false;
            Toast.makeText(this, "分页结果未覆盖目标位置", Toast.LENGTH_LONG).show();
            return;
        }
        ReaderPage currentPage = readerPageWindow.current();
        if (currentPage == null) {
            loadingChunk = false;
            suppressProgressSave = false;
            return;
        }
        showCurrentReaderPage();
        loadingChunk = false;
        suppressProgressSave = false;
        readerText.post(this::cacheCurrentPageSnapshot);
        maybeRefillCombineStack();
        maybeRefillReaderPageWindow();
    }

    private ReaderCache readReaderCache(Book book) {
        File source = new File(book.path);
        File cacheFile = readerCacheFile(book);
        if (!source.exists() || !cacheFile.exists()) return null;
        try (DataInputStream input = new DataInputStream(new FileInputStream(cacheFile))) {
            if (input.readInt() != READER_CACHE_MAGIC) return null;
            long fileSize = input.readLong();
            long modifiedAt = input.readLong();
            long windowStart = input.readLong();
            int bytesRead = input.readInt();
            int textLength = input.readInt();
            if (fileSize != source.length() || modifiedAt != source.lastModified()
                    || windowStart < 0 || windowStart > fileSize || bytesRead < 0
                    || bytesRead > fileSize - windowStart
                    || textLength < 0 || textLength > MAX_READER_CACHE_TEXT_BYTES) {
                return null;
            }
            byte[] textBytes = new byte[textLength];
            input.readFully(textBytes);
            return new ReaderCache(fileSize, windowStart, bytesRead,
                    new String(textBytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveReaderCache(Book book, CacheWindowLoad load) {
        if (book == null || load == null || load.cache == null) return;
        final File source = new File(book.path);
        pendingReaderCache = new ReaderCacheWrite(book, load.fileSize, source.lastModified(),
                load.cache.offset, load.cache.bytesRead, load.cache.text);
        mainHandler.removeCallbacks(readerCacheWriteRunnable);
        mainHandler.postDelayed(readerCacheWriteRunnable, READER_CACHE_WRITE_DELAY_MS);
    }

    private void flushReaderCacheWrite() {
        mainHandler.removeCallbacks(readerCacheWriteRunnable);
        ReaderCacheWrite cache = pendingReaderCache;
        pendingReaderCache = null;
        if (cache != null) writeReaderCache(cache);
    }

    private void cancelReaderCacheWrite(Book book) {
        if (pendingReaderCache != null && pendingReaderCache.book == book) {
            pendingReaderCache = null;
            mainHandler.removeCallbacks(readerCacheWriteRunnable);
        }
    }

    private void writeReaderCache(ReaderCacheWrite cache) {
        readerCacheExecutor.execute(() -> {
            byte[] textBytes = cache.text.getBytes(StandardCharsets.UTF_8);
            if (textBytes.length > MAX_READER_CACHE_TEXT_BYTES) return;
            File directory = new File(getFilesDir(), "reader-cache");
            if (!directory.exists() && !directory.mkdirs()) return;
            File target = readerCacheFile(cache.book);
            File temporary = new File(directory, target.getName() + ".tmp");
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
                output.writeInt(READER_CACHE_MAGIC);
                output.writeLong(cache.fileSize);
                output.writeLong(cache.modifiedAt);
                output.writeLong(cache.windowStart);
                output.writeInt(cache.bytesRead);
                output.writeInt(textBytes.length);
                output.write(textBytes);
                output.flush();
                if (target.exists() && !target.delete()) return;
                if (!temporary.renameTo(target)) {
                    boolean ignored = temporary.delete();
                }
            } catch (Exception ignored) {
                boolean deleted = temporary.delete();
            }
        });
    }

    private File readerCacheFile(Book book) {
        return new File(new File(getFilesDir(), "reader-cache"), book.id + ".window");
    }

    private void deleteReaderCache(Book book) {
        File cacheFile = readerCacheFile(book);
        if (cacheFile.exists()) {
            boolean ignored = cacheFile.delete();
        }
    }

    private boolean hasBookIndex(Book book, long fileSize) {
        synchronized (book) {
            return book.indexOffsets != null && book.indexFileSize == fileSize;
        }
    }

    private void requestBookIndex(Book book, long sourceFileSize) {
        synchronized (book) {
            if (book.indexBuilding
                    || (book.indexOffsets != null && book.indexFileSize == sourceFileSize)) {
                return;
            }
            book.indexBuilding = true;
        }
        indexExecutor.execute(() -> {
            try {
                ensureBookIndex(book, new File(book.path));
            } catch (Exception ignored) {
            } finally {
                synchronized (book) {
                    book.indexBuilding = false;
                }
            }
        });
    }

    private void maybeRefillCombineStack() {
        if (suppressProgressSave || loadingChunk || currentBook == null
                || currentCombineStack == null || currentCache == null) return;
        long anchorOffset = currentBook.offset;
        if (currentCombineStack.needsBackwardRefill(anchorOffset)) {
            prefetchBackwardSegment(currentBook, currentCombineStack);
        }
        if (currentCombineStack.needsForwardRefill(anchorOffset, currentBook.fileSize)) {
            prefetchForwardSegment(currentBook, currentCombineStack);
        }
    }

    private void prefetchBackwardSegment(Book book, CacheCombineStack combineStack) {
        if (loadingBackwardSegment || combineStack.startOffset() <= 0L) return;
        loadingBackwardSegment = true;
        long generation = cacheStackGeneration;
        long requestId = loadRequestId;
        long segmentEnd = combineStack.startOffset();
        ioExecutor.execute(() -> {
            CacheSegment segment = null;
            CacheCombineStack preparedStack = null;
            CombinedCacheSnapshot preparedCache = null;
            try {
                File file = new File(book.path);
                long desiredStart = Math.max(0L, segmentEnd - CACHE_SEGMENT_BYTES);
                long segmentStart = hasBookIndex(book, file.length())
                        ? indexedReadableOffset(book, file, desiredStart)
                        : findReadableOffset(file, desiredStart, book.encoding);
                int bytes = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0L, segmentEnd - segmentStart));
                if (bytes > 0) {
                    segment = readCachedSegment(file, segmentStart, bytes, book.encoding);
                }
                if (segment != null && segment.bytesRead > 0) {
                    preparedStack = combineStack.copy();
                    preparedStack.appendBackward(segment);
                    preparedCache = preparedStack.snapshot();
                }
            } catch (Exception ignored) {
            }
            CacheSegment loaded = segment;
            CacheCombineStack readyStack = preparedStack;
            CombinedCacheSnapshot readyCache = preparedCache;
            mainHandler.post(() -> finishBackwardPrefetch(
                    book, combineStack, generation, requestId,
                    loaded, readyStack, readyCache));
        });
    }

    private void finishBackwardPrefetch(Book book, CacheCombineStack combineStack,
                                        long generation, long requestId,
                                        CacheSegment loaded,
                                        CacheCombineStack preparedStack,
                                        CombinedCacheSnapshot preparedCache) {
        if (generation != cacheStackGeneration || requestId != loadRequestId
                || currentBook != book || currentCombineStack != combineStack) {
            return;
        }
        loadingBackwardSegment = false;
        if (loaded == null || loaded.bytesRead <= 0
                || preparedStack == null || preparedCache == null
                || loaded.endOffset() != combineStack.startOffset()
                || !combineStack.needsBackwardRefill(book.offset)) {
            return;
        }
        publishPreparedCombinedStack(combineStack, preparedStack, preparedCache);
    }

    private void prefetchForwardSegment(Book book, CacheCombineStack combineStack) {
        if (loadingForwardSegment || combineStack.endOffset() >= book.fileSize) return;
        loadingForwardSegment = true;
        long generation = cacheStackGeneration;
        long requestId = loadRequestId;
        long segmentStart = combineStack.endOffset();
        ioExecutor.execute(() -> {
            CacheSegment segment = null;
            CacheCombineStack preparedStack = null;
            CombinedCacheSnapshot preparedCache = null;
            try {
                segment = readCachedSegment(new File(book.path), segmentStart,
                        CACHE_SEGMENT_BYTES, book.encoding);
                if (segment != null && segment.bytesRead > 0) {
                    preparedStack = combineStack.copy();
                    preparedStack.appendForward(segment);
                    preparedCache = preparedStack.snapshot();
                }
            } catch (Exception ignored) {
            }
            CacheSegment loaded = segment;
            CacheCombineStack readyStack = preparedStack;
            CombinedCacheSnapshot readyCache = preparedCache;
            mainHandler.post(() -> finishForwardPrefetch(
                    book, combineStack, generation, requestId,
                    loaded, readyStack, readyCache));
        });
    }

    private void finishForwardPrefetch(Book book, CacheCombineStack combineStack,
                                       long generation, long requestId,
                                       CacheSegment loaded,
                                       CacheCombineStack preparedStack,
                                       CombinedCacheSnapshot preparedCache) {
        if (generation != cacheStackGeneration || requestId != loadRequestId
                || currentBook != book || currentCombineStack != combineStack) {
            return;
        }
        loadingForwardSegment = false;
        if (loaded == null || loaded.bytesRead <= 0
                || preparedStack == null || preparedCache == null
                || loaded.offset != combineStack.endOffset()
                || !combineStack.needsForwardRefill(book.offset, book.fileSize)) {
            return;
        }
        publishPreparedCombinedStack(combineStack, preparedStack, preparedCache);
    }

    private void publishPreparedCombinedStack(CacheCombineStack previousStack,
                                              CacheCombineStack preparedStack,
                                              CombinedCacheSnapshot preparedCache) {
        if (currentCombineStack != previousStack || preparedCache.bytesRead <= 0) return;
        cacheStackGeneration++;
        loadingBackwardSegment = false;
        loadingForwardSegment = false;
        currentCombineStack = preparedStack;
        currentCache = preparedCache;
        boolean restartReset = loadingReaderPages && resettingReaderPages;
        if (loadingReaderPages) {
            pageBuildRequestId++;
            loadingReaderPages = false;
            resettingReaderPages = false;
        }
        if (!temporarySearchReading && currentBook != null) {
            saveReaderCache(currentBook, new CacheWindowLoad(currentBook.fileSize,
                    currentBook.offset, preparedStack, preparedCache));
        }
        if (restartReset && currentBook != null) {
            ReaderPage page = readerPageWindow.current();
            long anchorOffset = page == null ? currentBook.offset : page.startOffset;
            requestReaderPageWindow(currentBook, preparedCache, anchorOffset);
        } else {
            maybeRefillReaderPageWindow();
        }
    }

    private void pageBackward() {
        if (!autoPageDispatching) scheduleAutoPage();
        if (pageAnimating || loadingChunk || suppressProgressSave) return;
        if (readerPageWindow.pagesBefore() <= 0) {
            maybeRefillReaderPageWindow();
            return;
        }
        animatePageTurn(-1, this::performPageBackward);
    }

    private void pageForward() {
        if (!autoPageDispatching) scheduleAutoPage();
        if (pageAnimating || loadingChunk || suppressProgressSave) return;
        if (readerPageWindow.pagesAfter() <= 0) {
            maybeRefillReaderPageWindow();
            return;
        }
        animatePageTurn(1, this::performPageForward);
    }

    private void performPageBackward() {
        if (currentBook == null || !readerPageWindow.moveBackward()) return;
        preferredPageRefillDirection = PAGE_DIRECTION_BACKWARD;
        showCurrentReaderPage();
        maybeRefillCombineStack();
        maybeRefillReaderPageWindow();
    }

    private void performPageForward() {
        if (currentBook == null || !readerPageWindow.moveForward()) return;
        preferredPageRefillDirection = PAGE_DIRECTION_FORWARD;
        showCurrentReaderPage();
        maybeRefillCombineStack();
        maybeRefillReaderPageWindow();
    }

    private void showCurrentReaderPage() {
        ReaderPage page = readerPageWindow.current();
        if (page == null || currentBook == null || readerText == null || readerScroll == null) return;
        pageStateGeneration++;
        releasePageSnapshot();
        readerText.setReaderPage(page, readerDisplayText(page.text, currentBook));
        readerScroll.scrollTo(0, 0);
        if (suppressProgressSave || temporarySearchReading) {
            currentBook.offset = Math.min(page.startOffset, currentBook.fileSize);
            currentBook.progress = currentBook.fileSize <= 0
                    ? 0f : currentBook.offset / (float) currentBook.fileSize;
        } else {
            applyFormalProgress(currentBook, page.startOffset, null);
        }
        scheduleBooksSave();
        updateProgressText();
        if (seekBar != null && !seekTracking) {
            seekBar.setProgress((int) (currentBook.progress * 1000f));
        }
    }

    private void maybeRefillReaderPageWindow() {
        if (loadingReaderPages || loadingChunk || currentBook == null
                || currentCache == null || readerPageWindow.isEmpty()) return;
        ReaderPageRefillPolicy.Decision decision = ReaderPageRefillPolicy.choose(
                readerPageWindow.pagesBefore(), readerPageWindow.pagesAfter(),
                readerPageWindow.firstStartOffset() > currentCache.offset,
                readerPageWindow.lastEndOffset() < currentCache.endOffset(),
                preferredPageRefillDirection);
        if (decision != null) requestReaderPages(decision.direction, decision.pageCount);
    }

    private void requestReaderPages(int direction, int requestedPages) {
        if (currentBook == null || currentCache == null || readerText == null
                || readerScroll == null || loadingReaderPages) return;
        ReaderPagePaginator.LayoutSpec spec = currentPageLayoutSpec();
        if (spec == null) {
            readerScroll.post(() -> requestReaderPages(direction, requestedPages));
            return;
        }
        Book book = currentBook;
        CombinedCacheSnapshot cache = currentCache;
        long boundaryOffset = direction == PAGE_DIRECTION_FORWARD
                ? readerPageWindow.lastEndOffset() : readerPageWindow.firstStartOffset();
        long requestId = ++pageBuildRequestId;
        loadingReaderPages = true;
        resettingReaderPages = false;
        int batchSize = Math.max(1, requestedPages);
        pageExecutor.execute(() -> {
            List<ReaderPage> pages = direction == PAGE_DIRECTION_FORWARD
                    ? readerPagePaginator.paginateForward(
                            cache, boundaryOffset, spec, batchSize)
                    : readerPagePaginator.paginateBackward(
                            cache, boundaryOffset, spec, batchSize);
            mainHandler.post(() -> finishReaderPageBatch(
                    book, cache, spec.key, requestId, direction, boundaryOffset, pages));
        });
    }

    private void finishReaderPageBatch(Book book, CombinedCacheSnapshot sourceCache,
                                       long layoutKey, long requestId, int direction,
                                       long expectedBoundary, List<ReaderPage> pages) {
        if (requestId != pageBuildRequestId || currentBook != book) return;
        loadingReaderPages = false;
        resettingReaderPages = false;
        boolean sourceIsCurrent = currentCache == sourceCache
                && pageLayoutGeneration == layoutKey;
        boolean appended = false;
        if (sourceIsCurrent && pages != null && !pages.isEmpty()) {
            ReaderPage currentPage = readerPageWindow.current();
            long anchorOffset = currentPage == null ? book.offset : currentPage.startOffset;
            appended = direction == PAGE_DIRECTION_FORWARD
                    ? readerPageWindow.appendForwardPages(
                            pages, expectedBoundary, anchorOffset, book.fileSize)
                    : readerPageWindow.prependBackwardPages(
                            pages, expectedBoundary, anchorOffset, book.fileSize);
        }
        if (!sourceIsCurrent) {
            maybeRefillReaderPageWindow();
            return;
        }
        if (!appended && pages != null && !pages.isEmpty()) {
            rebuildReaderPageWindow();
            return;
        }
        if (preferredPageRefillDirection == PAGE_DIRECTION_NONE) {
            preferredPageRefillDirection = direction;
        }
        maybeRefillCombineStack();
        maybeRefillReaderPageWindow();
    }

    private void animatePageTurn(int direction, Runnable turnAction) {
        if (readerText == null || readerScroll == null || readerFrame == null
                || readerScroll.getWidth() <= 0 || readerScroll.getHeight() <= 0) {
            turnAction.run();
            return;
        }
        pageAnimating = true;
        Bitmap pageBitmap;
        if (isCurrentPageSnapshotValid()) {
            pageBitmap = currentPageSnapshot;
            currentPageSnapshot = null;
            clearSnapshotMetadata();
        } else {
            releasePageSnapshot();
            pageBitmap = captureCurrentPage();
        }
        if (pageBitmap == null) {
            pageAnimating = false;
            turnAction.run();
            return;
        }
        final Bitmap animationBitmap = pageBitmap;

        ImageView turningPage = createPageOverlay(animationBitmap, direction);
        readerFrame.addView(turningPage, pageOverlayLayoutParams());

        // The validated page 0 stays on top; the destination (+1/-1) is placed underneath.
        turnAction.run();
        turningPage.animate()
                .rotationY(direction > 0 ? -90f : 90f)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setDuration(300)
                .withEndAction(() -> {
                    removePageOverlay(turningPage, animationBitmap);
                    pageAnimating = false;
                    readerScroll.post(() -> {
                        cacheCurrentPageSnapshot();
                        maybeRefillCombineStack();
                        maybeRefillReaderPageWindow();
                    });
                })
                .start();
    }

    private ImageView createPageOverlay(Bitmap bitmap, int direction) {
        ImageView page = new ImageView(this);
        page.setImageBitmap(bitmap);
        page.setScaleType(ImageView.ScaleType.FIT_XY);
        page.setCameraDistance(getResources().getDisplayMetrics().density * 12000f);
        page.setPivotX(direction >= 0 ? 0f : readerScroll.getWidth());
        page.setPivotY(readerScroll.getHeight() / 2f);
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return page;
    }

    private FrameLayout.LayoutParams pageOverlayLayoutParams() {
        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                readerScroll.getHeight(),
                Gravity.TOP);
        pageLp.topMargin = readerRoot.getTop() + readerScroll.getTop();
        return pageLp;
    }

    private void removePageOverlay(ImageView page, Bitmap bitmap) {
        ViewGroup parent = page.getParent() instanceof ViewGroup
                ? (ViewGroup) page.getParent() : null;
        if (parent != null) parent.removeView(page);
        page.setImageDrawable(null);
        if (!bitmap.isRecycled()) bitmap.recycle();
    }

    private Bitmap captureCurrentPage() {
        if (readerScroll == null || readerScroll.getWidth() <= 0
                || readerScroll.getHeight() <= 0) {
            return null;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(
                    readerScroll.getWidth(),
                    readerScroll.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int pageColor = currentBook == null
                    ? Color.WHITE
                    : backgroundColor(appTheme());
            canvas.drawColor(pageColor);
            canvas.save();
            canvas.clipRect(0, 0, readerScroll.getWidth(), readerScroll.getHeight());
            canvas.translate(0f, -readerScroll.getScrollY());
            readerText.draw(canvas);
            canvas.restore();
            return bitmap;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void cacheCurrentPageSnapshot() {
        if (pageAnimating || currentBook == null || !currentBook.pageMode) return;
        Bitmap snapshot = captureCurrentPage();
        if (snapshot == null) return;
        releasePageSnapshot();
        currentPageSnapshot = snapshot;
        ReaderPage page = readerPageWindow.current();
        snapshotPageOffset = page == null ? -1L : page.startOffset;
        snapshotScrollY = readerScroll.getScrollY();
        snapshotWidth = readerScroll.getWidth();
        snapshotHeight = readerScroll.getHeight();
        snapshotFontSize = readingFontSize();
        snapshotTheme = appTheme();
        snapshotGeneration = pageStateGeneration;
    }

    private boolean isCurrentPageSnapshotValid() {
        return currentPageSnapshot != null
                && !currentPageSnapshot.isRecycled()
                && currentBook != null
                && readerScroll != null
                && readerPageWindow.current() != null
                && snapshotPageOffset == readerPageWindow.current().startOffset
                && snapshotScrollY == readerScroll.getScrollY()
                && snapshotWidth == readerScroll.getWidth()
                && snapshotHeight == readerScroll.getHeight()
                && Float.compare(snapshotFontSize, readingFontSize()) == 0
                && snapshotTheme == appTheme()
                && snapshotGeneration == pageStateGeneration;
    }

    private void releasePageSnapshot() {
        if (currentPageSnapshot != null && !currentPageSnapshot.isRecycled()) {
            currentPageSnapshot.recycle();
        }
        currentPageSnapshot = null;
        clearSnapshotMetadata();
    }

    private void clearSnapshotMetadata() {
        snapshotPageOffset = -1L;
        snapshotScrollY = -1;
        snapshotWidth = -1;
        snapshotHeight = -1;
        snapshotFontSize = -1f;
        snapshotTheme = -1;
        snapshotGeneration = -1L;
    }

    private CacheSegment readSegment(File file, long offset, int maxBytes,
                                     String encoding) throws Exception {
        return ReaderSegmentSource.read(file, offset, maxBytes, charsetFor(encoding));
    }

    private Charset charsetFor(String encoding) {
        return Charset.forName(encoding == null ? "UTF-8" : encoding);
    }

    private CacheSegment readCachedSegment(File file, long offset, int maxBytes,
                                           String encoding) throws Exception {
        long safeOffset = Math.max(0L, Math.min(offset, Math.max(0L, file.length() - 1L)));
        String key = file.getAbsolutePath() + ":" + file.length() + ":"
                + file.lastModified() + ":" + encoding
                + ":" + safeOffset + ":" + maxBytes;
        synchronized (segmentCache) {
            CacheSegment cached = segmentCache.get(key);
            if (cached != null) return cached;
        }
        CacheSegment chunk = readSegment(file, safeOffset, maxBytes, encoding);
        synchronized (segmentCache) {
            segmentCache.put(key, chunk);
        }
        return chunk;
    }

    private void ensureBookIndex(Book book, File file) throws Exception {
        long size = file.length();
        synchronized (book) {
            if (book.indexOffsets != null && book.indexFileSize == size) return;
        }
        ArrayList<Long> offsets = new ArrayList<>(ReaderSegmentSource.buildReadableIndex(
                file, book.encoding, INDEX_STEP_BYTES));
        synchronized (book) {
            book.indexOffsets = offsets;
            book.indexFileSize = size;
        }
    }

    private long indexedReadableOffset(Book book, File file, long targetOffset) throws Exception {
        List<Long> offsets;
        long size = file.length();
        synchronized (book) {
            offsets = book.indexOffsets;
        }
        if (offsets != null && !offsets.isEmpty()) {
            long target = Math.max(0L, Math.min(targetOffset, Math.max(0L, size - 1L)));
            int low = 0;
            int high = offsets.size() - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                long value = offsets.get(mid);
                if (value <= target) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            long indexed = offsets.get(Math.max(0, high));
            if (target - indexed <= INDEX_STEP_BYTES + 4096L) {
                return indexed;
            }
        }
        return findReadableOffset(file, targetOffset, book.encoding);
    }

    private long findReadableOffset(File file, long targetOffset, String encoding) throws Exception {
        return ReaderSegmentSource.findReadableOffset(file, targetOffset, encoding);
    }

    private void toggleSeekPanel() {
        if (seekPanel != null) {
            boolean show = seekPanel.getVisibility() != View.VISIBLE;
            if (show) {
                seekPanel.setVisibility(View.VISIBLE);
                for (View stepButton : progressStepButtons) {
                    stepButton.setVisibility(View.VISIBLE);
                }
                if (progressButton != null) {
                    progressButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                }
            } else {
                collapseSeekPanel();
            }
            alignMenusToReaderViewport();
        }
    }

    private void collapseSeekPanel() {
        if (seekPanel != null) seekPanel.setVisibility(View.GONE);
        for (View stepButton : progressStepButtons) {
            stepButton.setVisibility(View.GONE);
        }
        if (progressButton != null) {
            progressButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        }
    }

    private void configureRepeatingProgressStep(View button, float delta) {
        final boolean[] repeated = {false};
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            repeated[0] = true;
            adjustProgressByPercent(delta);
            mainHandler.postDelayed(repeat[0], 120L);
        };
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    repeated[0] = false;
                    mainHandler.postDelayed(repeat[0], 420L);
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    mainHandler.removeCallbacks(repeat[0]);
                    view.setPressed(false);
                    if (!repeated[0]) adjustProgressByPercent(delta);
                    view.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mainHandler.removeCallbacks(repeat[0]);
                    view.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void showReaderMenus() {
        saveCurrentProgress();
        collapseSeekPanel();
        readerMenusOpen = true;
        alignMenusToReaderViewport();
        animateMenuIn(readerTopBar, -dp(72));
        animateMenuIn(readerBottomBar, dp(120));
    }

    private void showReaderMenusImmediately() {
        collapseSeekPanel();
        readerMenusOpen = true;
        alignMenusToReaderViewport();
        showMenuImmediately(readerTopBar);
        showMenuImmediately(readerBottomBar);
    }

    private void showMenuImmediately(View menu) {
        if (menu == null) return;
        menu.animate().cancel();
        menu.setTranslationY(0f);
        menu.setAlpha(1f);
        menu.setVisibility(View.VISIBLE);
    }

    private void hideReaderMenus() {
        saveCurrentProgress();
        collapseSeekPanel();
        readerMenusOpen = false;
        animateMenuOut(readerTopBar, -dp(72));
        animateMenuOut(readerBottomBar, dp(120));
    }

    private void animateMenuIn(View menu, float startTranslationY) {
        if (menu == null) return;
        menu.animate().cancel();
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setTranslationY(startTranslationY);
        menu.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(240)
                .start();
    }

    private void animateMenuOut(View menu, float endTranslationY) {
        if (menu == null || menu.getVisibility() != View.VISIBLE) return;
        menu.animate().cancel();
        menu.animate()
                .translationY(endTranslationY)
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    if (!readerMenusOpen) {
                        menu.setVisibility(View.GONE);
                        menu.setTranslationY(0f);
                        menu.setAlpha(1f);
                        if (menu == readerBottomBar && seekPanel != null) {
                            seekPanel.setVisibility(View.GONE);
                        }
                    }
                })
                .start();
    }

    private boolean areReaderMenusVisible() {
        return readerMenusOpen;
    }

    private void adjustProgressByPercent(float percentDelta) {
        if (currentBook == null) return;
        saveCurrentProgress();
        float next = Math.max(0f, Math.min(1f, currentBook.progress + percentDelta / 100f));
        pendingSeekProgress = next;
        currentBook.progress = next;
        updateProgressText(next);
        if (seekBar != null) seekBar.setProgress(Math.round(next * 1000f));
        loadCacheWindowAtProgress(next);
    }

    private void updateFontSize(float delta) {
        if (currentBook == null || readerText == null) return;
        saveCurrentProgress();
        float fontSize = ReaderSettingsOptions.normalizeFontSize(readingFontSize() + delta);
        setReadingFontSize(fontSize);
        pageLayoutGeneration++;
        pageStateGeneration++;
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        refreshReaderSpacing(true);
        readerScroll.post(() -> readerScroll.postOnAnimation(this::rebuildReaderPageWindow));
    }

    private void rebuildReaderPageWindow() {
        if (currentBook == null || currentCache == null) return;
        ReaderPage page = readerPageWindow.current();
        long anchorOffset = page == null ? currentBook.offset : page.startOffset;
        pageBuildRequestId++;
        loadingReaderPages = false;
        resettingReaderPages = false;
        requestReaderPageWindow(currentBook, currentCache, anchorOffset);
    }

    private void refreshReaderSpacing() {
        refreshReaderSpacing(true);
    }

    private void refreshReaderSpacing(boolean updateViewportBounds) {
        if (currentBook == null) return;
        float fontSize = readingFontSize();
        boolean needsViewportFit = updateViewportBounds
                || fittedReaderBottomInset < 0
                || Float.compare(fittedReaderFontSize, fontSize) != 0;
        if (needsViewportFit) {
            applyReaderSafePadding(fontSize);
        }
        applyReaderLineSpacing(fontSize);
        if (readerText != null) {
            readerText.post(() -> fitReaderViewportToWholeLines(fontSize));
        } else if (readerScroll != null) {
            readerScroll.post(this::cacheCurrentPageSnapshot);
        }
    }

    private void applyReaderLineSpacing(float fontSize) {
        if (readerText == null) return;
        int extra = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                ReaderDisplayPolicy.lineSpacingExtraSp(
                        fontSize, readingLineSpacingRatio()),
                getResources().getDisplayMetrics());
        readerText.setLineSpacing(extra, 1.0f);
    }

    private android.graphics.Typeface readerTypeface(int family) {
        int style = ReaderDisplayPolicy.fontUsesItalicStyle(family)
                ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL;
        return android.graphics.Typeface.create(
                ReaderDisplayPolicy.fontFamilyName(family), style);
    }

    private void applyKeepScreenOn(boolean enabled) {
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyReaderSafePadding(float fontSize) {
        if (readerRoot == null || readerScroll == null) return;
        int topInset = readerTopInset();
        int bottomInset = readerBottomInset(fontSize);
        if (readerRoot.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerRoot.getLayoutParams();
            lp.topMargin = topInset;
            lp.bottomMargin = bottomInset;
            readerRoot.setLayoutParams(lp);
        }
        readerRoot.setPadding(0, 0, 0, 0);
        readerScroll.setPadding(0, 0, 0, 0);
        resetReaderContentFrameHeight();
        alignMenusToReaderViewport();
    }

    private void resetReaderContentFrameHeight() {
        if (readerScroll == null || !(readerScroll.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) readerScroll.getLayoutParams();
        lp.height = 0;
        lp.weight = 1f;
        readerScroll.setLayoutParams(lp);
        fittedReaderVisibleHeight = -1;
        readerContainerHeight = -1;
    }

    private int readerTopInset() {
        return statusBarHeight() + dp(7);
    }

    private int readerBottomInset(float fontSize) {
        return navigationBarHeight() + dp(18);
    }

    private void fitReaderViewportToWholeLines(float fontSize) {
        if (readerRoot == null || readerScroll == null || readerText == null) {
            return;
        }
        // readerRoot is the fixed reader container. Its measured height already
        // excludes the status and navigation areas on the current device.
        int available = Math.max(0, readerRoot.getHeight());
        if (available <= 0) return;

        Layout layout = readerText.getLayout();
        int lineHeight;
        if (layout != null && layout.getLineCount() > 0) {
            lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        } else {
            android.graphics.Paint.FontMetricsInt metrics = readerText.getPaint().getFontMetricsInt();
            lineHeight = metrics.descent - metrics.ascent
                    + Math.max(0, Math.round(readerText.getLineSpacingExtra()));
        }
        lineHeight = Math.max(1, lineHeight);
        int fullLines = Math.max(1, available / lineHeight);
        int fittedVisible = Math.max(1, Math.min(available, fullLines * lineHeight));
        boolean heightChanged = applyReaderContentFrameHeight(fittedVisible);
        if (fittedReaderVisibleHeight != fittedVisible || heightChanged) {
            pageStateGeneration++;
            pageLayoutGeneration++;
            fittedReaderBottomInset = readerBottomInset(fontSize);
            fittedReaderFontSize = fontSize;
            fittedReaderVisibleHeight = fittedVisible;
            readerRoot.post(() -> {
                alignMenusToReaderViewport();
                if (!readerPageWindow.isEmpty() && !loadingChunk) {
                    readerScroll.post(this::rebuildReaderPageWindow);
                } else {
                    readerScroll.post(this::cacheCurrentPageSnapshot);
                }
            });
        } else {
            fittedReaderBottomInset = readerBottomInset(fontSize);
            fittedReaderFontSize = fontSize;
            fittedReaderVisibleHeight = fittedVisible;
            readerScroll.post(this::cacheCurrentPageSnapshot);
        }
    }

    private boolean applyReaderContentFrameHeight(int height) {
        if (readerScroll == null || !(readerScroll.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return false;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) readerScroll.getLayoutParams();
        if (lp.height == height && lp.weight == 0f) return false;
        lp.height = height;
        lp.weight = 0f;
        readerScroll.setLayoutParams(lp);
        return true;
    }

    private void alignMenusToReaderViewport() {
        if (readerFrame == null || readerRoot == null || readerScroll == null
                || readerFrame.getHeight() <= 0) {
            return;
        }
        int viewportTop = readerRoot.getTop();
        int viewportBottom = readerRoot.getBottom();
        if (readerTopBar != null
                && readerTopBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) readerTopBar.getLayoutParams();
            lp.topMargin = viewportTop;
            readerTopBar.setLayoutParams(lp);
        }
        if (readerBottomBar != null
                && readerBottomBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) readerBottomBar.getLayoutParams();
            lp.bottomMargin = Math.max(0, readerFrame.getHeight() - viewportBottom);
            readerBottomBar.setLayoutParams(lp);
        }
    }

    private int statusBarHeight() {
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        return insets == null ? dp(24) : insets.getSystemWindowInsetTop();
    }

    private int navigationBarHeight() {
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        return insets == null ? dp(24) : insets.getSystemWindowInsetBottom();
    }

    private int readerBottomOverlayHeight() {
        if (readerBottomBar == null || readerBottomBar.getVisibility() != View.VISIBLE) return 0;
        int height = readerBottomBar.getHeight();
        if (height > 0) return height;
        int seekHeight = seekPanel != null && seekPanel.getVisibility() == View.VISIBLE ? dp(86) : 0;
        return seekHeight + dp(46) + dp(20);
    }

    private void saveCurrentProgress() {
        if (suppressProgressSave || currentBook == null) return;
        ReaderPage page = readerPageWindow.current();
        if (page == null) return;
        if (!temporarySearchReading) {
            applyFormalProgress(currentBook, page.startOffset, null);
        }
        saveBooks();
        if (seekBar != null) seekBar.setProgress((int) (currentBook.progress * 1000f));
    }

    private boolean applyFormalProgress(Book book, long offset, Long preservedReadAtMs) {
        long safeOffset = Math.max(0L, Math.min(offset, Math.max(0L, book.fileSize)));
        if (book.offset == safeOffset && preservedReadAtMs == null) return false;
        book.offset = safeOffset;
        book.progress = book.fileSize <= 0L ? 0f : safeOffset / (float) book.fileSize;
        book.updatedAt = preservedReadAtMs == null
                ? SyncRules.monotonicReadAt(System.currentTimeMillis(), book.updatedAt)
                : preservedReadAtMs;
        pendingProgressPublications.add(book.id);
        return true;
    }

    private void updateProgressText() {
        if (progressButton != null && currentBook != null) {
            progressButton.setText(String.format(Locale.getDefault(), "%.2f%%", currentBook.progress * 100f));
        }
    }

    private void updateProgressText(float progress) {
        if (progressButton != null) {
            float value = Math.max(0f, Math.min(1f, progress));
            progressButton.setText(String.format(Locale.getDefault(), "%.2f%%", value * 100f));
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return button;
    }

    private void refreshAutoPageButton() {
        Book book = currentBook;
        if (autoPageButton == null || book == null) return;
        boolean dark = isDarkTheme(appTheme());
        boolean enabled = AutoPageOptions.normalize(autoPageSeconds) != AutoPageOptions.OFF;
        int foreground = enabled
                ? (dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT)
                : (dark ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT);
        int background = enabled
                ? (dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER)
                : (dark ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT);
        UiKit.styleIconButton(this, autoPageButton, foreground, background, 14);
        autoPageButton.setContentDescription(autoPageDescription(autoPageSeconds));
    }

    private void refreshKeepScreenOnButton() {
        if (keepScreenOnButton == null) return;
        boolean dark = isDarkTheme(appTheme());
        boolean enabled = readingKeepScreenOn();
        int foreground = enabled
                ? (dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT)
                : (dark ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT);
        int background = enabled
                ? (dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER)
                : (dark ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT);
        UiKit.styleIconButton(this, keepScreenOnButton, foreground, background, 14);
        keepScreenOnButton.setSelected(enabled);
        keepScreenOnButton.setContentDescription(
                enabled ? "阅读时锁屏已开启" : "阅读时锁屏已关闭");
    }

    private String autoPageDescription(int seconds) {
        int normalized = AutoPageOptions.normalize(seconds);
        return normalized == AutoPageOptions.OFF
                ? "自动翻页已关闭"
                : "自动翻页，每 " + normalized + " 秒";
    }

    private void scheduleAutoPage() {
        mainHandler.removeCallbacks(autoPageRunnable);
        long delay = ReaderRuntimePolicy.autoPageDelayMillis(autoPageSeconds,
                activityResumed, currentBook != null, readerScroll != null,
                searchOpen, temporarySearchReading);
        if (delay == ReaderRuntimePolicy.NO_AUTO_PAGE_DELAY) return;
        mainHandler.postDelayed(autoPageRunnable, delay);
    }

    private void cancelAutoPage() {
        mainHandler.removeCallbacks(autoPageRunnable);
    }

    private void disableAutoPage() {
        autoPageSeconds = AutoPageOptions.OFF;
        cancelAutoPage();
        refreshAutoPageButton();
    }

    private ImageButton makeIconButton() {
        ImageButton button = new ImageButton(this);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setColorFilter(isDarkTheme(appTheme()) ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT);
        return button;
    }

    private int backgroundColor(int theme) {
        if (theme == THEME_LIGHT) return UiKit.LIGHT_BACKGROUND;
        if (theme == THEME_DARK) return UiKit.DARK_BACKGROUND;
        return UiKit.LIGHT_BACKGROUND;
    }

    private int appTheme() {
        return preferences == null ? THEME_LIGHT
                : ReaderSettingsOptions.normalizeTheme(
                        preferences.getInt(KEY_APP_THEME, THEME_LIGHT));
    }

    private void setAppTheme(int theme) {
        preferences.edit().putInt(KEY_APP_THEME,
                ReaderSettingsOptions.normalizeTheme(theme)).apply();
    }

    private boolean readingKeepScreenOn() {
        return preferences.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    private void setReadingKeepScreenOn(boolean enabled) {
        preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
    }

    private int readingAutoPageInterval() {
        return AutoPageOptions.normalizePreference(preferences.getInt(KEY_AUTO_PAGE_INTERVAL,
                AutoPageOptions.DEFAULT_SECONDS));
    }

    private void setReadingAutoPageInterval(int seconds) {
        preferences.edit().putInt(KEY_AUTO_PAGE_INTERVAL,
                AutoPageOptions.normalizePreference(seconds)).apply();
    }

    private int readingSensitivity() {
        return ReaderSettingsOptions.normalizeSensitivity(
                preferences.getInt(KEY_SENSITIVITY, SENSITIVITY_STANDARD));
    }

    private void setReadingSensitivity(int sensitivity) {
        preferences.edit().putInt(KEY_SENSITIVITY,
                ReaderSettingsOptions.normalizeSensitivity(sensitivity)).apply();
    }

    private int readingFontFamily() {
        return ReaderSettingsOptions.normalizeFontFamily(
                preferences.getInt(KEY_FONT_FAMILY, ReaderSettingsOptions.FONT_SYSTEM));
    }

    private void setReadingFontFamily(int family) {
        preferences.edit().putInt(KEY_FONT_FAMILY,
                ReaderSettingsOptions.normalizeFontFamily(family)).apply();
    }

    private float readingFontSize() {
        return ReaderSettingsOptions.normalizeFontSize(
                preferences.getFloat(KEY_FONT_SIZE, ReaderSettingsOptions.DEFAULT_FONT_SIZE));
    }

    private void setReadingFontSize(float size) {
        preferences.edit().putFloat(KEY_FONT_SIZE,
                ReaderSettingsOptions.normalizeFontSize(size)).apply();
    }

    private float readingLineSpacingRatio() {
        return ReaderSettingsOptions.normalizeLineSpacing(
                preferences.getFloat(KEY_LINE_SPACING,
                        ReaderSettingsOptions.DEFAULT_LINE_SPACING));
    }

    private void setReadingLineSpacingRatio(float ratio) {
        preferences.edit().putFloat(KEY_LINE_SPACING,
                ReaderSettingsOptions.normalizeLineSpacing(ratio)).apply();
    }

    private boolean isDarkTheme(int theme) {
        return ReaderDisplayPolicy.isDarkTheme(theme);
    }

    private int textColor(int theme) {
        if (theme == THEME_LIGHT) return UiKit.LIGHT_TEXT;
        if (theme == THEME_DARK) return UiKit.DARK_TEXT;
        return UiKit.LIGHT_TEXT;
    }

    private void applyWindowColors(int theme) {
        getWindow().setStatusBarColor(backgroundColor(theme));
        getWindow().setNavigationBarColor(backgroundColor(theme));
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (theme == THEME_DARK) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private boolean isSystemNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void migrateLegacySystemTheme() {
        if (preferences.getInt(KEY_APP_THEME, THEME_LIGHT)
                != ReaderSettingsOptions.LEGACY_THEME_SYSTEM) return;
        preferences.edit().putInt(KEY_APP_THEME,
                isSystemNight() ? THEME_DARK : THEME_LIGHT).apply();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? null : new File(fallback).getName();
    }

    private String detectEncoding(File file) {
        return TextFileUtils.detectEncoding(file);
    }

    private String getVersionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long build = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return "v" + info.versionName + " build " + build;
        } catch (Exception ignored) {
            return "v0.9.0 build 54";
        }
    }

    private String getVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "0.0.0" : info.versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private String formatFileSize(long bytes) {
        return TextFileUtils.formatFileSize(bytes);
    }

    private void loadBooks() {
        books.clear();
        books.addAll(bookStore.load());
    }

    private void saveBooks() {
        booksSavePending = false;
        mainHandler.removeCallbacks(booksSaveRunnable);
        Book preservedBook = temporarySearchReading && searchSession != null ? currentBook : null;
        long preservedOffset = preservedBook == null ? 0L : searchSession.returnOffset;
        float preservedProgress = preservedBook == null ? 0f : searchSession.returnProgress;
        if (bookStore.save(books, preservedBook, preservedOffset, preservedProgress)
                && syncCoordinator != null && !pendingProgressPublications.isEmpty()) {
            for (Book book : books) {
                if (pendingProgressPublications.contains(book.id)) {
                    long publishedOffset = book == preservedBook ? preservedOffset : book.offset;
                    long publishedReadAt = book == preservedBook && searchSession != null
                            ? searchSession.returnUpdatedAt : book.updatedAt;
                    syncCoordinator.onLocalProgressChanged(book.id, book.fileSize,
                            publishedOffset, publishedReadAt);
                }
            }
            pendingProgressPublications.clear();
        }
    }

    private void scheduleBooksSave() {
        booksSavePending = true;
        mainHandler.removeCallbacks(booksSaveRunnable);
        mainHandler.postDelayed(booksSaveRunnable, BOOK_PROGRESS_SAVE_DELAY_MS);
    }

    private void flushScheduledBooksSave() {
        if (!booksSavePending) return;
        mainHandler.removeCallbacks(booksSaveRunnable);
        booksSavePending = false;
        saveBooks();
    }

    private static class ReaderCache {
        final long fileSize;
        final long windowStart;
        final int bytesRead;
        final String text;

        ReaderCache(long fileSize, long windowStart, int bytesRead, String text) {
            this.fileSize = fileSize;
            this.windowStart = windowStart;
            this.bytesRead = bytesRead;
            this.text = text;
        }
    }

    private static class ReaderCacheWrite {
        final Book book;
        final long fileSize;
        final long modifiedAt;
        final long windowStart;
        final int bytesRead;
        final String text;

        ReaderCacheWrite(Book book, long fileSize, long modifiedAt, long windowStart,
                         int bytesRead, String text) {
            this.book = book;
            this.fileSize = fileSize;
            this.modifiedAt = modifiedAt;
            this.windowStart = windowStart;
            this.bytesRead = bytesRead;
            this.text = text;
        }
    }

    private static class SearchResult {
        final long offset;
        final String snippet;

        SearchResult(long offset, String snippet) {
            this.offset = offset;
            this.snippet = snippet;
        }
    }

    private static class SearchBatch {
        final List<SearchResult> results;
        final long nextOffset;
        final boolean reachedBoundary;

        SearchBatch(List<SearchResult> results, long nextOffset, boolean reachedBoundary) {
            this.results = results;
            this.nextOffset = nextOffset;
            this.reachedBoundary = reachedBoundary;
        }
    }

    private static class SearchSession {
        final Book book;
        final String query;
        final long originOffset;
        final long fileSize;
        final long returnOffset;
        final float returnProgress;
        final long returnUpdatedAt;
        final List<SearchResult> results = new ArrayList<>();
        long nextOffset;
        boolean loading;
        boolean wrappedToStart;
        boolean needsWrapConfirmation;
        boolean complete;

        SearchSession(Book book, String query, long originOffset, long fileSize) {
            this.book = book;
            this.query = query;
            this.originOffset = originOffset;
            this.fileSize = fileSize;
            this.returnOffset = originOffset;
            this.returnProgress = book.progress;
            this.returnUpdatedAt = book.updatedAt;
            this.nextOffset = originOffset;
        }
    }

    private static class CacheWindowLoad {
        final long fileSize;
        final long targetOffset;
        final CacheCombineStack combineStack;
        final CombinedCacheSnapshot cache;

        CacheWindowLoad(long fileSize, long targetOffset, CacheCombineStack combineStack,
                        CombinedCacheSnapshot cache) {
            this.fileSize = fileSize;
            this.targetOffset = targetOffset;
            this.combineStack = combineStack;
            this.cache = cache;
        }
    }

}
