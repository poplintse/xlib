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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
    private static final int THEME_SYSTEM = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;
    private static final int SENSITIVITY_HIGH = 0;
    private static final int SENSITIVITY_STANDARD = 1;
    private static final int SENSITIVITY_LOW = 2;
    // 128 KiB keeps local reads fast while leaving several rendered pages for prefetching.
    private static final int CACHE_SEGMENT_BYTES = 128 * 1024;
    private static final float CACHE_REFILL_RATIO = 0.10f;
    private static final int INDEX_STEP_BYTES = 64 * 1024;
    private static final int SEGMENT_CACHE_LIMIT = 6;
    // XLI2: cache text is guaranteed to end on a complete encoded character.
    private static final int READER_CACHE_MAGIC = 0x584C4932;
    private static final int MAX_READER_CACHE_TEXT_BYTES = CACHE_SEGMENT_BYTES * 8;
    private static final long READER_CACHE_WRITE_DELAY_MS = 800L;
    private static final int SEARCH_READ_BYTES = 64 * 1024;
    private static final int SEARCH_RESULT_LIMIT = 200;
    private static final int SEARCH_CONTEXT_CHARS = 45;
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

    private final List<Book> books = new ArrayList<>();
    private final Set<Long> selectedBookIds = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService readerCacheExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService tocExecutor = Executors.newSingleThreadExecutor();
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
    private boolean catalogOpen;
    private boolean temporarySearchReading;
    private boolean suppressProgressSave;
    private boolean activityResumed;
    private boolean autoPageDispatching;
    private int autoPageSeconds = AutoPageOptions.OFF;
    private ReaderCacheWrite pendingReaderCache;
    private SearchSession searchSession;
    private float touchStartX;
    private float touchStartY;
    private float pendingSeekProgress;
    private volatile long loadRequestId;
    private volatile long searchRequestId;
    private long cacheStackGeneration;
    private long currentChunkOffset;
    private int currentChunkBytes;
    private CacheSegment currentChunk;
    private CacheCombineStack currentCombineStack;
    private Bitmap currentPageSnapshot;
    private long snapshotChunkOffset = -1L;
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
    private FrameLayout readerFrame;
    private LinearLayout readerRoot;
    private AccessibleScrollView readerScroll;
    private TextView readerText;
    private LinearLayout readerTopBar;
    private LinearLayout readerBottomBar;
    private Button progressButton;
    private final List<View> progressStepButtons = new ArrayList<>();
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
    private final Runnable autoPageRunnable = () -> {
        Book book = currentBook;
        if (book == null || AutoPageOptions.normalize(autoPageSeconds) == 0
                || searchOpen || temporarySearchReading) {
            return;
        }
        if (!pageAnimating && !loadingChunk && !suppressProgressSave && readerScroll != null) {
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
        bookStore = new BookStore(preferences);
        bookmarkStore = new BookmarkStore(preferences);
        tocStore = new TocStore(this);
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
        flushReaderCacheWrite();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        scheduleAutoPage();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdownNow();
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
            loadRequestId++;
            saveCurrentProgress();
            releasePageSnapshot();
            currentBook = null;
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
        boolean dark = isDarkTheme(appTheme());
        int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int textColor = dark ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT;
        int muted = dark ? UiKit.DARK_MUTED : UiKit.LIGHT_MUTED;
        int accent = dark ? UiKit.DARK_ACCENT : UiKit.LIGHT_ACCENT;
        int accentContainer = dark ? UiKit.DARK_ACCENT_CONTAINER : UiKit.LIGHT_ACCENT_CONTAINER;

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(13), dp(2), dp(13));
        row.setBackground(UiKit.interactive(this, surface, 22, UiKit.withAlpha(accent, 28)));
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
        UiKit.styleIconButton(this, more, actionText, moreBackground, 0);
        more.setOnClickListener(v -> showEditBookDialog(book));
        actions.addView(more, new LinearLayout.LayoutParams(actionWidth,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton delete = makeIconButton();
        delete.setImageResource(R.drawable.ic_delete);
        delete.setContentDescription("删除书籍");
        UiKit.styleIconButton(this, delete, actionText, deleteBackground, 0);
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
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDown[0] = event.getRawX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float translation = Math.min(0, Math.max(-actionMenuWidth,
                            event.getRawX() - touchDown[0]));
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
        try {
            String title = queryDisplayName(uri);
            if (title == null || title.trim().isEmpty()) {
                title = "book-" + System.currentTimeMillis() + ".txt";
            }
            File dir = new File(getFilesDir(), "books");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Cannot create book directory");
            }
            String fileName = System.currentTimeMillis() + "-" + title.replaceAll("[^A-Za-z0-9._-]", "_");
            File target = new File(dir, fileName);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IllegalStateException("Cannot open selected file");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            Book book = new Book();
            book.id = System.currentTimeMillis();
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
            books.add(0, book);
            saveBooks();
            if (isAutoTocEnabled()) generateTocInBackground(book, false);
            showLibrary();
            Toast.makeText(this, "已添加：" + title, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "添加失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openBook(Book book) {
        disableAutoPage();
        readerMenusOpen = false;
        currentBook = book;
        showReader(book);
    }

    private void showReader(Book book) {
        settingsOpen = false;
        catalogOpen = false;
        releasePageSnapshot();
        fittedReaderBottomInset = -1;
        fittedReaderFontSize = -1f;
        fittedReaderVisibleHeight = -1;
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

        Button smaller = makeButton("A-");
        UiKit.styleButton(this, smaller,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT,
                menuFg, 14);
        smaller.setPadding(0, 0, 0, 0);
        smaller.setOnClickListener(v -> updateFontSize(-2f));
        fontControls.addView(smaller, new LinearLayout.LayoutParams(dp(44), dp(42)));

        Button larger = makeButton("A+");
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
        theme.setImageResource(darkTheme ? R.drawable.ic_moon : R.drawable.ic_sun);
        UiKit.styleIconButton(this, theme, menuFg,
                darkTheme ? UiKit.DARK_SURFACE_VARIANT : UiKit.LIGHT_SURFACE_VARIANT, 14);
        theme.setContentDescription(darkTheme ? "深色主题" : "浅色主题");
        theme.setOnClickListener(v -> {
            saveCurrentProgress();
            setAppTheme(isDarkTheme(appTheme()) ? THEME_LIGHT : THEME_DARK);
            readerMenusOpen = true;
            showReader(book);
        });
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        themeLp.leftMargin = dp(4);
        readerTopBar.addView(theme, themeLp);

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

        readerText = new TextView(this);
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
        readerScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            pageStateGeneration++;
            saveCurrentProgress();
            updateProgressText();
            maybeRefillCombineStack();
        });
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
            if (height > 0 && height != readerContainerHeight) {
                readerContainerHeight = height;
                if (readerText != null && currentBook != null) {
                    readerText.post(() -> fitReaderViewportToWholeLines(readingFontSize()));
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
        suppressProgressSave = true;
        CacheRestoreResult cacheResult = restoreReaderCache(book, requestedOffset);
        frame.post(this::alignMenusToReaderViewport);
        if (cacheResult != CacheRestoreResult.COVERS_OFFSET) {
            loadCacheWindowAtOffset(requestedOffset);
        }
        if (readerMenusOpen) {
            frame.post(this::showReaderMenus);
        }
        scheduleAutoPage();
    }

    private void openSearchPage() {
        if (currentBook == null) return;
        saveCurrentProgress();
        flushReaderCacheWrite();
        loadRequestId++;
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
                if (tocStore.read(book) != null) return;
                try {
                    TocDocument document = TocGenerator.generate(
                            new File(book.path), book.encoding);
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
        TocDocument document = tocStore.read(currentBook);
        saveCurrentProgress();
        disableAutoPage();
        loadRequestId++;
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
        book.offset = Math.max(0L, Math.min(book.fileSize, offset));
        book.progress = book.fileSize <= 0 ? 0f : book.offset / (float) book.fileSize;
        book.updatedAt = System.currentTimeMillis();
        saveBooks();
        catalogOpen = false;
        readerMenusOpen = false;
        showReader(book);
    }

    private void openSettingsPage(int initialTab) {
        settingsOpenedFromLibrary = currentBook == null;
        if (currentBook != null) saveCurrentProgress();
        disableAutoPage();
        loadRequestId++;
        releasePageSnapshot();
        settingsOpen = true;
        applyKeepScreenOn(false);
        showSettingsPage(currentBook, initialTab);
    }

    private void showSettingsPage(Book book, int initialTab) {
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
        back.setContentDescription("返回阅读");
        back.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.CENTER_VERTICAL | Gravity.START);
        navigation.addView(back, backLp);

        boolean showGeneral = initialTab == SETTINGS_GENERAL;
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(UiKit.rounded(this, surface, 18));
        Button general = makeSettingsNavButton("常规", showGeneral, text, accent,
                accentContainer);
        tabs.addView(general, new LinearLayout.LayoutParams(dp(72), dp(36)));

        Button reading = makeSettingsNavButton("阅读", !showGeneral, text, accent,
                accentContainer);
        LinearLayout.LayoutParams readingLp = new LinearLayout.LayoutParams(dp(72), dp(36));
        tabs.addView(reading, readingLp);
        FrameLayout.LayoutParams tabsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
                Gravity.CENTER_VERTICAL | Gravity.END);
        navigation.addView(tabs, tabsLp);
        general.setOnClickListener(v -> {
            styleSettingsNavButton(general, true, text, accent, accentContainer);
            styleSettingsNavButton(reading, false, text, accent, accentContainer);
            renderGeneralSettings(surface, text, muted, accent);
        });
        reading.setOnClickListener(v -> {
            styleSettingsNavButton(general, false, text, accent, accentContainer);
            styleSettingsNavButton(reading, true, text, accent, accentContainer);
            renderReadingSettings(surface, text, muted, accent, accentContainer);
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
        else renderReadingSettings(surface, text, muted, accent, accentContainer);
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
        addChoiceButtons(settingsControl(theme), new String[]{"跟随", "浅色", "深色"},
                new int[]{THEME_SYSTEM, THEME_LIGHT, THEME_DARK}, appTheme(), value -> {
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
        addStepper(settingsControl(fontSize), "A⌄", "A^", fontSizeValue, true,
                () -> {
                    float size = Math.max(14f, readingFontSize() - 2f);
                    setReadingFontSize(size);
                    fontSizeValue.setText(getString(
                            R.string.font_size_value, Math.round(size)));
                }, () -> {
                    float size = Math.min(34f, readingFontSize() + 2f);
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
        spacingSeek.setMax(30);
        spacingSeek.setProgress(Math.round(readingLineSpacingRatio() * 100f) - 10);
        spacingSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                setReadingLineSpacingRatio((10 + progress) / 100f);
                spacingValue.setText(getString(R.string.percent_value, 10 + progress));
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
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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
        loadRequestId++;
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
        book.updatedAt = System.currentTimeMillis();
        showReader(book);
    }

    private SearchBatch searchBookBatch(Book book, String query, long startOffset, long endOffset)
            throws Exception {
        File file = new File(book.path);
        byte[] needle = query.getBytes(Charset.forName(book.encoding == null ? "UTF-8" : book.encoding));
        List<SearchResult> results = new ArrayList<>();
        long boundary = Math.max(0L, Math.min(endOffset, file.length()));
        long position = Math.max(0L, Math.min(startOffset, boundary));
        if (needle.length == 0 || !file.exists() || position >= boundary) {
            return new SearchBatch(results, position, true);
        }
        int[] prefix = buildSearchPrefix(needle);
        byte[] buffer = new byte[SEARCH_READ_BYTES];
        int matched = 0;
        boolean reachedBoundary = false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(position);
            int read = 0;
            while (position < boundary && results.size() < SEARCH_RESULT_LIMIT
                    && (read = input.read(buffer, 0,
                    (int) Math.min(buffer.length, boundary - position))) != -1) {
                for (int i = 0; i < read; i++, position++) {
                    byte value = buffer[i];
                    while (matched > 0 && value != needle[matched]) {
                        matched = prefix[matched - 1];
                    }
                    if (value == needle[matched]) matched++;
                    if (matched == needle.length) {
                        long offset = position - needle.length + 1L;
                        results.add(new SearchResult(offset,
                                makeSearchSnippet(file, offset, query, book.encoding, needle.length)));
                        matched = prefix[matched - 1];
                        if (results.size() >= SEARCH_RESULT_LIMIT) {
                            return new SearchBatch(results, offset + 1L, false);
                        }
                    }
                }
            }
            reachedBoundary = position >= boundary || read == -1;
        }
        return new SearchBatch(results, position, reachedBoundary);
    }

    private int[] buildSearchPrefix(byte[] needle) {
        int[] prefix = new int[needle.length];
        for (int i = 1, matched = 0; i < needle.length; i++) {
            while (matched > 0 && needle[i] != needle[matched]) {
                matched = prefix[matched - 1];
            }
            if (needle[i] == needle[matched]) matched++;
            prefix[i] = matched;
        }
        return prefix;
    }

    private String makeSearchSnippet(File file, long matchOffset, String query, String encoding,
                                     int queryByteLength) throws Exception {
        long contextStart = alignSearchContextOffset(file,
                Math.max(0L, matchOffset - SEARCH_CONTEXT_CHARS * 4L), encoding);
        int readLength = Math.max(2048, SEARCH_CONTEXT_CHARS * 8 + queryByteLength + 1024);
        CacheSegment context = readSegment(file, contextStart, readLength, encoding);
        int index = context.text.indexOf(query);
        if (index < 0) return query;
        int start = Math.max(0, index - SEARCH_CONTEXT_CHARS);
        int end = Math.min(context.text.length(), index + query.length() + SEARCH_CONTEXT_CHARS);
        return context.text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
    }

    private long alignSearchContextOffset(File file, long offset, String encoding) throws Exception {
        String normalized = encoding == null ? "UTF-8" : encoding.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("UTF-16")) return offset - (offset % 2L);
        if (!normalized.startsWith("UTF-8")) return offset;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long aligned = Math.min(offset, Math.max(0L, file.length() - 1L));
            while (aligned < file.length()) {
                input.seek(aligned);
                int value = input.read();
                if (value < 0 || (value & 0xC0) != 0x80) return aligned;
                aligned++;
            }
            return offset;
        }
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
        if (readingSensitivity() == SENSITIVITY_HIGH) return 20;
        if (readingSensitivity() == SENSITIVITY_LOW) return 12;
        return 16;
    }

    private int swipeThresholdDp() {
        if (currentBook == null) return 72;
        if (readingSensitivity() == SENSITIVITY_HIGH) return 48;
        if (readingSensitivity() == SENSITIVITY_LOW) return 96;
        return 72;
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
        if (currentBook == null) return;
        cacheStackGeneration++;
        loadingBackwardSegment = false;
        loadingForwardSegment = false;
        Book book = currentBook;
        long requestId = ++loadRequestId;
        loadingChunk = true;
        // Rebinding the TextView briefly preserves the previous window's scrollY. Ignore
        // that transient position until the new window has been laid out and positioned.
        suppressProgressSave = true;
        ioExecutor.execute(() -> {
            if (requestId != loadRequestId) return;
            CacheWindowLoad load = null;
            Exception error = null;
            try {
                load = prepareCacheWindowLoad(book, targetOffset);
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
                applyCacheWindowLoad(book, finalLoad);
            });
        });
    }

    private CacheWindowLoad prepareCacheWindowLoad(Book book, long targetOffset) throws Exception {
        File file = new File(book.path);
        long fileSize = file.length();
        long clampedTarget = fileSize <= 0
                ? 0L
                : Math.max(0L, Math.min(targetOffset, fileSize - 1L));
        requestBookIndex(book);
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
                new CacheCombineStack(CACHE_SEGMENT_BYTES, CACHE_REFILL_RATIO);
        combineStack.reset(initialSegments);
        CacheSegment combined = combineStack.combine(charsetFor(book.encoding));
        return new CacheWindowLoad(fileSize, clampedTarget, combineStack, combined);
    }

    private void applyCacheWindowLoad(Book book, CacheWindowLoad load) {
        if (load == null || readerText == null || readerScroll == null) return;
        book.fileSize = load.fileSize;
        currentCombineStack = load.combineStack;
        currentChunkOffset = load.chunk.offset;
        currentChunkBytes = load.chunk.bytesRead;
        currentChunk = load.chunk;
        book.offset = load.targetOffset;
        book.progress = load.fileSize <= 0 ? 0f : load.targetOffset / (float) load.fileSize;
        book.updatedAt = System.currentTimeMillis();
        pageStateGeneration++;
        readerText.setText(readerDisplayText(load.chunk.text, book));
        refreshReaderSpacing(false);
        positionReaderAtOffset(book, load.chunk, load.targetOffset, true);
        saveBooks();
        if (!temporarySearchReading) {
            saveReaderCache(book, load);
        }
        updateProgressText();
        if (seekBar != null && !seekTracking) {
            seekBar.setProgress((int) (book.progress * 1000f));
        }
    }

    private CacheRestoreResult restoreReaderCache(Book book, long requestedOffset) {
        ReaderCache cache = readReaderCache(book);
        if (cache == null || readerText == null || readerScroll == null) {
            return CacheRestoreResult.NOT_AVAILABLE;
        }
        Charset charset = charsetFor(book.encoding);
        CacheSegment cachedChunk = new CacheSegment(cache.windowStart, cache.text, cache.bytesRead,
                ByteOffsetMap.create(cache.text, charset));
        CacheCombineStack combineStack =
                new CacheCombineStack(CACHE_SEGMENT_BYTES, CACHE_REFILL_RATIO);
        combineStack.resetFromCombined(cachedChunk, charset);
        CacheSegment restoredWindow = combineStack.combine(charset);
        boolean completeFile = restoredWindow.offset == 0L
                && restoredWindow.endOffset() >= cache.fileSize;
        if (combineStack.segmentCount() < 2 && !completeFile) {
            return CacheRestoreResult.NOT_AVAILABLE;
        }
        boolean coversOffset = ReaderPosition.cacheCoversOffset(cache.fileSize,
                restoredWindow.offset, restoredWindow.bytesRead, requestedOffset);
        if (!coversOffset) return CacheRestoreResult.NOT_AVAILABLE;
        currentCombineStack = combineStack;
        currentChunkOffset = restoredWindow.offset;
        currentChunkBytes = restoredWindow.bytesRead;
        currentChunk = restoredWindow;
        book.fileSize = cache.fileSize;
        book.offset = Math.max(0L, Math.min(requestedOffset, cache.fileSize));
        book.progress = cache.fileSize <= 0 ? 0f : book.offset / (float) cache.fileSize;
        pageStateGeneration++;
        readerText.setText(readerDisplayText(restoredWindow.text, book));
        refreshReaderSpacing(false);
        positionReaderAtOffset(book, restoredWindow, requestedOffset, true);
        updateProgressText();
        if (seekBar != null) seekBar.setProgress((int) (book.progress * 1000f));
        return CacheRestoreResult.COVERS_OFFSET;
    }

    private void positionReaderAtOffset(Book book, CacheSegment chunk, long targetOffset,
                                        boolean finishRestore) {
        AccessibleScrollView targetScroll = readerScroll;
        targetScroll.post(() -> {
            if (currentBook != book || readerScroll != targetScroll || currentChunk != chunk) return;
            if (readerText == null || readerText.getLayout() == null) {
                targetScroll.post(() -> positionReaderAtOffset(
                        book, chunk, targetOffset, finishRestore));
                return;
            }
            scrollToOffsetWithinWindow(targetOffset);
            // Keep progress writes frozen through the next traversal, when Android emits
            // the scroll callback caused by replacing and repositioning the text.
            targetScroll.postOnAnimation(() -> targetScroll.postOnAnimation(() -> {
                if (currentBook != book || readerScroll != targetScroll || currentChunk != chunk) {
                    return;
                }
                if (finishRestore) {
                    loadingChunk = false;
                    suppressProgressSave = false;
                }
                cacheCurrentPageSnapshot();
                maybeRefillCombineStack();
            }));
        });
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
                    || windowStart < 0 || bytesRead < 0
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
        if (book == null || load == null || load.chunk == null) return;
        final File source = new File(book.path);
        pendingReaderCache = new ReaderCacheWrite(book, load.fileSize, source.lastModified(),
                load.chunk.offset, load.chunk.bytesRead, load.chunk.text);
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

    private void requestBookIndex(Book book) {
        synchronized (book) {
            if (book.indexBuilding || (book.indexOffsets != null && book.indexFileSize == book.fileSize)) {
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
                || currentCombineStack == null || currentChunk == null) return;
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
            } catch (Exception ignored) {
            }
            CacheSegment loaded = segment;
            mainHandler.post(() -> finishBackwardPrefetch(
                    book, combineStack, generation, requestId, loaded));
        });
    }

    private void finishBackwardPrefetch(Book book, CacheCombineStack combineStack,
                                        long generation, long requestId,
                                        CacheSegment loaded) {
        if (generation != cacheStackGeneration || requestId != loadRequestId
                || currentBook != book || currentCombineStack != combineStack) {
            return;
        }
        if (pageAnimating || loadingChunk || suppressProgressSave) {
            mainHandler.postDelayed(() -> finishBackwardPrefetch(
                    book, combineStack, generation, requestId, loaded), 32L);
            return;
        }
        loadingBackwardSegment = false;
        if (loaded == null || loaded.bytesRead <= 0
                || loaded.endOffset() != combineStack.startOffset()
                || !combineStack.needsBackwardRefill(book.offset)) {
            return;
        }
        combineStack.appendBackward(loaded);
        applyCombinedStack(book, combineStack, generation, requestId);
    }

    private void prefetchForwardSegment(Book book, CacheCombineStack combineStack) {
        if (loadingForwardSegment || combineStack.endOffset() >= book.fileSize) return;
        loadingForwardSegment = true;
        long generation = cacheStackGeneration;
        long requestId = loadRequestId;
        long segmentStart = combineStack.endOffset();
        ioExecutor.execute(() -> {
            CacheSegment segment = null;
            try {
                segment = readCachedSegment(new File(book.path), segmentStart,
                        CACHE_SEGMENT_BYTES, book.encoding);
            } catch (Exception ignored) {
            }
            CacheSegment loaded = segment;
            mainHandler.post(() -> finishForwardPrefetch(
                    book, combineStack, generation, requestId, loaded));
        });
    }

    private void finishForwardPrefetch(Book book, CacheCombineStack combineStack,
                                       long generation, long requestId,
                                       CacheSegment loaded) {
        if (generation != cacheStackGeneration || requestId != loadRequestId
                || currentBook != book || currentCombineStack != combineStack) {
            return;
        }
        if (pageAnimating || loadingChunk || suppressProgressSave) {
            mainHandler.postDelayed(() -> finishForwardPrefetch(
                    book, combineStack, generation, requestId, loaded), 32L);
            return;
        }
        loadingForwardSegment = false;
        if (loaded == null || loaded.bytesRead <= 0
                || loaded.offset != combineStack.endOffset()
                || !combineStack.needsForwardRefill(book.offset, book.fileSize)) {
            return;
        }
        combineStack.appendForward(loaded);
        applyCombinedStack(book, combineStack, generation, requestId);
    }

    private void applyCombinedStack(Book book, CacheCombineStack combineStack,
                                    long generation, long requestId) {
        if (generation != cacheStackGeneration || requestId != loadRequestId
                || currentBook != book || currentCombineStack != combineStack) {
            return;
        }
        if (pageAnimating || loadingChunk || suppressProgressSave) {
            mainHandler.postDelayed(() -> applyCombinedStack(
                    book, combineStack, generation, requestId), 32L);
            return;
        }
        long targetOffset = book.offset;
        CacheSegment combined = combineStack.combine(charsetFor(book.encoding));
        if (combined.bytesRead <= 0) return;
        loadingChunk = true;
        suppressProgressSave = true;
        currentChunkOffset = combined.offset;
        currentChunkBytes = combined.bytesRead;
        currentChunk = combined;
        pageStateGeneration++;
        readerText.setText(readerDisplayText(combined.text, book));
        refreshReaderSpacing(false);
        positionReaderAtOffset(book, combined, targetOffset, true);
        if (!temporarySearchReading) {
            saveReaderCache(book,
                    new CacheWindowLoad(book.fileSize, targetOffset, combineStack, combined));
        }
    }

    private void pageBackward() {
        if (!autoPageDispatching) scheduleAutoPage();
        if (pageAnimating || loadingChunk || suppressProgressSave) return;
        if (readerScroll != null && readerScroll.getScrollY() <= 0
                && currentCombineStack != null && currentCombineStack.startOffset() > 0L) {
            maybeRefillCombineStack();
            return;
        }
        animatePageTurn(-1, this::performPageBackward);
    }

    private void pageForward() {
        if (!autoPageDispatching) scheduleAutoPage();
        if (pageAnimating || loadingChunk || suppressProgressSave) return;
        if (readerScroll != null && readerScroll.getScrollY() >= maxReaderScroll()
                && currentCombineStack != null
                && currentCombineStack.endOffset() < currentBook.fileSize) {
            maybeRefillCombineStack();
            return;
        }
        animatePageTurn(1, this::performPageForward);
    }

    private void performPageBackward() {
        if (currentBook == null || readerScroll == null || readerText == null) return;
        saveCurrentProgress();
        int y = readerScroll.getScrollY();
        if (y > 0) {
            pageStateGeneration++;
            readerScroll.scrollTo(0, pageBackTargetY(y));
            saveCurrentProgress();
            updateProgressText();
            return;
        }
        if (currentBook.offset > 0) {
            maybeRefillCombineStack();
        }
    }

    private void performPageForward() {
        if (currentBook == null || readerScroll == null || readerText == null) return;
        saveCurrentProgress();
        int maxScroll = maxReaderScroll();
        int y = readerScroll.getScrollY();
        if (y < maxScroll) {
            pageStateGeneration++;
            readerScroll.scrollTo(0, pageForwardTargetY(y, maxScroll));
            saveCurrentProgress();
            updateProgressText();
            return;
        }
        if (currentBook.offset < currentBook.fileSize - 1L) {
            maybeRefillCombineStack();
        }
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

        ImageView turningPage = new ImageView(this);
        turningPage.setImageBitmap(animationBitmap);
        turningPage.setScaleType(ImageView.ScaleType.FIT_XY);
        turningPage.setCameraDistance(getResources().getDisplayMetrics().density * 12000f);
        turningPage.setPivotX(direction > 0 ? 0f : readerScroll.getWidth());
        turningPage.setPivotY(readerScroll.getHeight() / 2f);
        turningPage.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                readerScroll.getHeight(),
                Gravity.TOP);
        pageLp.topMargin = readerRoot.getTop() + readerScroll.getTop();
        readerFrame.addView(turningPage, pageLp);

        // The validated page 0 stays on top; the destination (+1/-1) is placed underneath.
        turnAction.run();
        turningPage.animate()
                .rotationY(direction > 0 ? -90f : 90f)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setDuration(300)
                .withEndAction(() -> {
                    readerFrame.removeView(turningPage);
                    turningPage.setImageDrawable(null);
                    animationBitmap.recycle();
                    pageAnimating = false;
                    readerScroll.post(this::cacheCurrentPageSnapshot);
                })
                .start();
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
        snapshotChunkOffset = currentChunkOffset;
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
                && snapshotChunkOffset == currentChunkOffset
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
        snapshotChunkOffset = -1L;
        snapshotScrollY = -1;
        snapshotWidth = -1;
        snapshotHeight = -1;
        snapshotFontSize = -1f;
        snapshotTheme = -1;
        snapshotGeneration = -1L;
    }

    private void scrollToOffsetWithinWindow(long targetOffset) {
        if (readerScroll == null || readerText == null || currentChunk == null) return;
        if (readerText.getLayout() == null) {
            readerText.post(() -> scrollToOffsetWithinWindow(targetOffset));
            return;
        }
        Layout layout = readerText.getLayout();
        if (layout.getLineCount() <= 0 || currentChunkBytes <= 0) {
            pageStateGeneration++;
            readerScroll.scrollTo(0, 0);
            return;
        }
        long relativeByteOffset = Math.max(0L,
                Math.min(targetOffset - currentChunkOffset, currentChunkBytes));
        int charIndex = currentChunk.offsetMap.charIndexForByteOffset(relativeByteOffset);
        int line = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForOffset(Math.min(charIndex, readerText.length()))));
        pageStateGeneration++;
        readerScroll.scrollTo(0, Math.min(maxReaderScroll(), layout.getLineTop(line)));
    }

    private CacheSegment readSegment(File file, long offset, int maxBytes,
                                     String encoding) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long safeOffset = Math.max(0L, Math.min(offset, file.length()));
            raf.seek(safeOffset);
            int length = (int) Math.min(maxBytes, Math.max(0L, file.length() - raf.getFilePointer()));
            byte[] bytes = new byte[length];
            int read = raf.read(bytes);
            Charset charset = charsetFor(encoding);
            if (read <= 0) {
                return new CacheSegment(safeOffset, "", 0,
                        ByteOffsetMap.create("", charset));
            }
            return decodeCompleteSegment(safeOffset, bytes, read, charset);
        }
    }

    private CacheSegment decodeCompleteSegment(long offset, byte[] bytes, int read,
                                               Charset charset) {
        int minimumLength = Math.max(0, read - 4);
        for (int validLength = read; validLength >= minimumLength; validLength--) {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                String text = decoder.decode(ByteBuffer.wrap(bytes, 0, validLength)).toString();
                ByteOffsetMap map = ByteOffsetMap.create(text, charset);
                if (map.totalBytes() == validLength) {
                    return new CacheSegment(offset, text, validLength, map);
                }
            } catch (CharacterCodingException ignored) {
                // A chunk may end halfway through a multi-byte character; trim only that suffix.
            }
        }
        String text = new String(bytes, 0, read, charset);
        return new CacheSegment(offset, text, read, ByteOffsetMap.create(text, charset));
    }

    private Charset charsetFor(String encoding) {
        return Charset.forName(encoding == null ? "UTF-8" : encoding);
    }

    private CacheSegment readCachedSegment(File file, long offset, int maxBytes,
                                           String encoding) throws Exception {
        long safeOffset = Math.max(0L, Math.min(offset, Math.max(0L, file.length() - 1L)));
        String key = file.getAbsolutePath() + ":" + file.length() + ":" + encoding
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
        ArrayList<Long> offsets = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            offsets.add(0L);
            for (long target = INDEX_STEP_BYTES; target < size; target += INDEX_STEP_BYTES) {
                long offset = findReadableOffset(raf, size, target, book.encoding);
                if (offsets.isEmpty() || offsets.get(offsets.size() - 1) < offset) {
                    offsets.add(offset);
                }
            }
        }
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
        long size = file.length();
        if (targetOffset <= 0 || size <= 0) return 0L;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return findReadableOffset(raf, size, targetOffset, encoding);
        }
    }

    private long findReadableOffset(RandomAccessFile raf, long size, long targetOffset,
                                    String encoding) throws Exception {
        if (targetOffset <= 0 || size <= 0) return 0L;
        long offset = Math.min(targetOffset, Math.max(0L, size - 1));
        long start = Math.max(0L, offset - INDEX_STEP_BYTES);
        String normalized = encoding == null ? "UTF-8" : encoding.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("UTF-16")) {
            long position = offset - (offset % 2L);
            boolean littleEndian = normalized.endsWith("LE");
            for (long cursor = position - 2L; cursor >= start; cursor -= 2L) {
                raf.seek(cursor);
                int first = raf.read();
                int second = raf.read();
                boolean newline = littleEndian
                        ? first == 0x0A && second == 0x00
                        : first == 0x00 && second == 0x0A;
                if (newline) return Math.min(size, cursor + 2L);
            }
            return position;
        }
        for (long cursor = offset - 1L; cursor >= start; cursor--) {
            raf.seek(cursor);
            if (raf.read() == 0x0A) return Math.min(size, cursor + 1L);
        }
        if (normalized.startsWith("UTF-8")) {
            long aligned = offset;
            while (aligned < size) {
                raf.seek(aligned);
                int value = raf.read();
                if (value < 0 || (value & 0xC0) != 0x80) break;
                aligned++;
            }
            return aligned;
        }
        return offset;
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
        float fontSize = Math.max(14f, Math.min(34f, readingFontSize() + delta));
        setReadingFontSize(fontSize);
        currentBook.updatedAt = System.currentTimeMillis();
        pageStateGeneration++;
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        refreshReaderSpacing(true);
        readerScroll.post(() -> {
            int y = Math.min(maxReaderScroll(), snapToLineTop(readerScroll.getScrollY()));
            readerScroll.scrollTo(0, y);
            saveCurrentProgress();
            updateProgressText();
            readerScroll.post(this::cacheCurrentPageSnapshot);
        });
    }

    private void refreshReaderSpacingAndPosition() {
        refreshReaderSpacing(true);
        if (readerScroll == null || currentBook == null) return;
        readerScroll.post(() -> {
            refreshReaderSpacing(true);
            scrollToOffsetWithinWindow(currentBook.offset);
            updateProgressText();
            readerScroll.post(this::cacheCurrentPageSnapshot);
        });
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
        float ratio = readingLineSpacingRatio();
        if (ratio <= 0f) ratio = 0.18f;
        int extra = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                Math.max(2f, fontSize * ratio),
                getResources().getDisplayMetrics());
        readerText.setLineSpacing(extra, 1.0f);
    }

    private android.graphics.Typeface readerTypeface(int family) {
        if (family == 1) return android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL);
        if (family == 2) return android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL);
        if (family == 3) return android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL);
        if (family == 4) return android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC);
        return android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL);
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
            fittedReaderBottomInset = readerBottomInset(fontSize);
            fittedReaderFontSize = fontSize;
            fittedReaderVisibleHeight = fittedVisible;
            readerRoot.post(() -> {
                alignMenusToReaderViewport();
                readerScroll.post(this::cacheCurrentPageSnapshot);
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
        if (suppressProgressSave || currentBook == null || readerScroll == null
                || readerText == null || currentChunk == null) return;
        Layout layout = readerText.getLayout();
        if (layout == null || layout.getLineCount() <= 0) return;
        int topLine = Math.max(0, Math.min(layout.getLineCount() - 1,
                layout.getLineForVertical(Math.max(0, readerScroll.getScrollY()))));
        int topCharIndex = layout.getLineStart(topLine);
        long relativeByteOffset = currentChunk.offsetMap.byteOffsetForCharIndex(topCharIndex);
        long visibleOffset = currentChunkOffset + relativeByteOffset;
        currentBook.offset = Math.min(Math.max(0L, visibleOffset), Math.max(0L, currentBook.fileSize));
        currentBook.progress = currentBook.fileSize <= 0 ? 0f : currentBook.offset / (float) currentBook.fileSize;
        currentBook.updatedAt = System.currentTimeMillis();
        saveBooks();
        if (seekBar != null) seekBar.setProgress((int) (currentBook.progress * 1000f));
    }

    private int readerVisibleHeight() {
        if (readerScroll == null) return 0;
        return Math.max(0, readerScroll.getHeight());
    }

    private int maxReaderScroll() {
        if (readerScroll == null || readerText == null) return 0;
        return Math.max(0, readerText.getHeight() - readerVisibleHeight());
    }

    private int pageForwardTargetY(int currentY, int maxScroll) {
        Layout layout = readerText == null ? null : readerText.getLayout();
        int visible = readerVisibleHeight();
        if (layout == null || visible <= 0) {
            return Math.min(maxScroll, currentY + Math.max(dp(120), visible));
        }
        int boundary = currentY + visible;
        int boundaryLine = Math.min(
                layout.getLineCount() - 1,
                layout.getLineForVertical(Math.max(currentY, boundary - 1)));
        int line = boundaryLine;
        if (layout.getLineBottom(boundaryLine) <= boundary
                && boundaryLine + 1 < layout.getLineCount()) {
            line = boundaryLine + 1;
        }
        int lineTop = layout.getLineTop(line);
        if (lineTop <= currentY && line + 1 < layout.getLineCount()) {
            lineTop = layout.getLineTop(line + 1);
        }
        if (lineTop <= currentY) {
            lineTop = currentY + Math.max(1, averageLineHeight(layout));
        }
        return Math.max(0, Math.min(maxScroll, lineTop));
    }

    private int pageBackTargetY(int currentY) {
        Layout layout = readerText == null ? null : readerText.getLayout();
        int visible = readerVisibleHeight();
        if (layout == null || visible <= 0) {
            return Math.max(0, currentY - Math.max(dp(120), visible));
        }
        int target = Math.max(0, currentY - visible);
        int line = Math.max(0, layout.getLineForVertical(target));
        if (layout.getLineTop(line) < target && line + 1 < layout.getLineCount()) {
            line++;
        }
        int lineTop = layout.getLineTop(line);
        if (lineTop >= currentY) {
            lineTop = currentY - Math.max(1, averageLineHeight(layout));
        }
        return Math.max(0, lineTop);
    }

    private int snapToLineTop(int targetY) {
        Layout layout = readerText == null ? null : readerText.getLayout();
        if (layout == null) return targetY;
        int line = Math.max(0, Math.min(layout.getLineCount() - 1, layout.getLineForVertical(targetY)));
        return Math.max(0, layout.getLineTop(line));
    }

    private int averageLineHeight(Layout layout) {
        if (layout == null || layout.getLineCount() <= 0) return dp(24);
        return Math.max(1, layout.getHeight() / layout.getLineCount());
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

    private String autoPageDescription(int seconds) {
        int normalized = AutoPageOptions.normalize(seconds);
        return normalized == AutoPageOptions.OFF
                ? "自动翻页已关闭"
                : "自动翻页，每 " + normalized + " 秒";
    }

    private void scheduleAutoPage() {
        mainHandler.removeCallbacks(autoPageRunnable);
        if (!activityResumed || currentBook == null || readerScroll == null
                || searchOpen || temporarySearchReading) return;
        int seconds = AutoPageOptions.normalize(autoPageSeconds);
        if (seconds == AutoPageOptions.OFF) return;
        mainHandler.postDelayed(autoPageRunnable, seconds * 1000L);
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
        button.setColorFilter(isSystemNight() ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT);
        return button;
    }

    private int backgroundColor(int theme) {
        if (theme == THEME_LIGHT) return UiKit.LIGHT_BACKGROUND;
        if (theme == THEME_DARK) return UiKit.DARK_BACKGROUND;
        return isSystemNight() ? UiKit.DARK_BACKGROUND : UiKit.LIGHT_BACKGROUND;
    }

    private int appTheme() {
        return preferences == null ? THEME_SYSTEM
                : preferences.getInt(KEY_APP_THEME, THEME_SYSTEM);
    }

    private void setAppTheme(int theme) {
        preferences.edit().putInt(KEY_APP_THEME, theme).apply();
    }

    private boolean readingKeepScreenOn() {
        return preferences.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    private void setReadingKeepScreenOn(boolean enabled) {
        preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
    }

    private int readingAutoPageInterval() {
        return AutoPageOptions.normalize(preferences.getInt(KEY_AUTO_PAGE_INTERVAL,
                AutoPageOptions.DEFAULT_SECONDS));
    }

    private void setReadingAutoPageInterval(int seconds) {
        preferences.edit().putInt(KEY_AUTO_PAGE_INTERVAL,
                AutoPageOptions.normalize(seconds)).apply();
    }

    private int readingSensitivity() {
        return Math.max(SENSITIVITY_HIGH, Math.min(SENSITIVITY_LOW,
                preferences.getInt(KEY_SENSITIVITY, SENSITIVITY_STANDARD)));
    }

    private void setReadingSensitivity(int sensitivity) {
        preferences.edit().putInt(KEY_SENSITIVITY,
                Math.max(SENSITIVITY_HIGH, Math.min(SENSITIVITY_LOW, sensitivity))).apply();
    }

    private int readingFontFamily() {
        return Math.max(0, Math.min(4, preferences.getInt(KEY_FONT_FAMILY, 0)));
    }

    private void setReadingFontFamily(int family) {
        preferences.edit().putInt(KEY_FONT_FAMILY, Math.max(0, Math.min(4, family))).apply();
    }

    private float readingFontSize() {
        return Math.max(14f, Math.min(34f, preferences.getFloat(KEY_FONT_SIZE, 20f)));
    }

    private void setReadingFontSize(float size) {
        preferences.edit().putFloat(KEY_FONT_SIZE, Math.max(14f, Math.min(34f, size))).apply();
    }

    private float readingLineSpacingRatio() {
        return Math.max(0.10f, Math.min(0.40f,
                preferences.getFloat(KEY_LINE_SPACING, 0.18f)));
    }

    private void setReadingLineSpacingRatio(float ratio) {
        preferences.edit().putFloat(KEY_LINE_SPACING,
                Math.max(0.10f, Math.min(0.40f, ratio))).apply();
    }

    private boolean isDarkTheme(int theme) {
        return theme == THEME_DARK || (theme == THEME_SYSTEM && isSystemNight());
    }

    private int textColor(int theme) {
        if (theme == THEME_LIGHT) return UiKit.LIGHT_TEXT;
        if (theme == THEME_DARK) return UiKit.DARK_TEXT;
        return isSystemNight() ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT;
    }

    private void applyWindowColors(int theme) {
        getWindow().setStatusBarColor(backgroundColor(theme));
        getWindow().setNavigationBarColor(backgroundColor(theme));
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (theme == THEME_DARK || (theme == THEME_SYSTEM && isSystemNight())) {
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
            return "v0.1.0 build 1";
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
        Book preservedBook = temporarySearchReading && searchSession != null ? currentBook : null;
        long preservedOffset = preservedBook == null ? 0L : searchSession.returnOffset;
        float preservedProgress = preservedBook == null ? 0f : searchSession.returnProgress;
        bookStore.save(books, preservedBook, preservedOffset, preservedProgress);
    }

    private enum CacheRestoreResult {
        NOT_AVAILABLE,
        COVERS_OFFSET
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
        final CacheSegment chunk;

        CacheWindowLoad(long fileSize, long targetOffset, CacheCombineStack combineStack,
                        CacheSegment chunk) {
            this.fileSize = fileSize;
            this.targetOffset = targetOffset;
            this.combineStack = combineStack;
            this.chunk = chunk;
        }
    }

}
