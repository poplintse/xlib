package com.xlib.txtreader;

import android.app.Activity;
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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
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
    private static final String KEY_BOOKS = "books";
    private static final int THEME_SYSTEM = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;
    private static final int SENSITIVITY_HIGH = 0;
    private static final int SENSITIVITY_STANDARD = 1;
    private static final int SENSITIVITY_LOW = 2;
    private static final int CHUNK_BYTES = 128 * 1024;
    private static final int WINDOW_BYTES = CHUNK_BYTES * 3;
    private static final int INDEX_STEP_BYTES = 64 * 1024;
    private static final int CHUNK_CACHE_LIMIT = 6;
    private static final int READER_CACHE_MAGIC = 0x584C4942;
    private static final int MAX_READER_CACHE_TEXT_BYTES = WINDOW_BYTES * 6;
    private static final long READER_CACHE_WRITE_DELAY_MS = 800L;
    private static final int SEARCH_READ_BYTES = 64 * 1024;
    private static final int SEARCH_RESULT_LIMIT = 200;
    private static final int SEARCH_CONTEXT_CHARS = 45;
    private static final long SEEK_PREVIEW_DELAY_MS = 220L;

    private final List<Book> books = new ArrayList<>();
    private final Set<Long> selectedBookIds = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService readerCacheExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Chunk> chunkCache = new LinkedHashMap<String, Chunk>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Chunk> eldest) {
            return size() > CHUNK_CACHE_LIMIT;
        }
    };
    private SharedPreferences prefs;
    private Book currentBook;
    private boolean managingBooks;
    private boolean loadingChunk;
    private boolean pendingWindowLoad;
    private boolean pageAnimating;
    private boolean edgeBackCandidate;
    private boolean edgeBackTriggered;
    private boolean touchMoved;
    private boolean touchStartedWithMenusOpen;
    private boolean menuDismissTouch;
    private boolean readerMenusOpen;
    private boolean seekTracking;
    private boolean searchOpen;
    private boolean temporarySearchReading;
    private ReaderCacheWrite pendingReaderCache;
    private SearchSession searchSession;
    private float touchStartX;
    private float touchStartY;
    private float pendingSeekProgress;
    private volatile long loadRequestId;
    private volatile long searchRequestId;
    private long currentChunkOffset;
    private int currentChunkBytes;
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
    private ScrollView readerScroll;
    private TextView readerText;
    private LinearLayout readerTopBar;
    private LinearLayout readerBottomBar;
    private Button progressButton;
    private LinearLayout seekPanel;
    private SeekBar seekBar;

    private final Runnable seekPreviewRunnable = () -> {
        if (seekTracking) {
            loadChunkAtProgress(pendingSeekProgress);
        }
    };
    private final Runnable readerCacheWriteRunnable = () -> {
        ReaderCacheWrite cache = pendingReaderCache;
        pendingReaderCache = null;
        if (cache != null) writeReaderCache(cache);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadBooks();
        showLibrary();
    }

    @Override
    protected void onPause() {
        saveCurrentProgress();
        flushReaderCacheWrite();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdownNow();
        readerCacheExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
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
        applyWindowColors(THEME_SYSTEM);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorForSystem("#F7F4EF", "#111416"));
        root.setPadding(dp(18), dp(42), dp(18), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("我的书架");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTextColor(colorForSystem("#202124", "#F2F0EA"));
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));

        ImageButton add = makeIconButton();
        add.setImageResource(R.drawable.ic_add_book);
        add.setContentDescription("添加 TXT");
        add.setOnClickListener(v -> pickTxtFile());
        header.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageButton manage = makeIconButton();
        manage.setImageResource(managingBooks ? R.drawable.ic_done : R.drawable.ic_manage_books);
        manage.setContentDescription(managingBooks ? "完成管理" : "管理书籍");
        manage.setOnClickListener(v -> {
            managingBooks = !managingBooks;
            selectedBookIds.clear();
            showLibrary();
        });
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        manageLp.leftMargin = dp(8);
        header.addView(manage, manageLp);
        root.addView(header);

        if (books.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有书。点击右上角添加一个 txt 文件。");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            empty.setTextColor(colorForSystem("#6B6F73", "#B4B8BA"));
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, dp(12), 0, 0);
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
            deleteSelected.setEnabled(!selectedBookIds.isEmpty());
            deleteSelected.setOnClickListener(v -> deleteSelectedBooks());
            root.addView(deleteSelected, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }

        TextView version = new TextView(this);
        version.setText(getVersionText());
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        version.setTextColor(colorForSystem("#7C8185", "#8D9396"));
        version.setGravity(Gravity.LEFT);
        version.setPadding(0, dp(8), 0, 0);
        root.addView(version, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        setContentView(root);
    }

    private View makeBookRow(Book book) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(colorForSystem("#FFFFFF", "#1A1E20"));

        if (managingBooks) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setChecked(selectedBookIds.contains(book.id));
            checkBox.setOnClickListener(v -> toggleBookSelection(book));
            row.addView(checkBox, new LinearLayout.LayoutParams(dp(44), dp(52)));
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(book.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(colorForSystem("#202124", "#F2F0EA"));
        texts.addView(title);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, dp(5), 0, 0);

        TextView meta = new TextView(this);
        meta.setText(String.format(Locale.getDefault(), "%.2f%% · %s",
                book.progress * 100f,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(book.updatedAt))));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setTextColor(colorForSystem("#6B6F73", "#A9ADB0"));
        metaRow.addView(meta, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView size = new TextView(this);
        size.setText(formatFileSize(book.fileSize));
        size.setSingleLine(true);
        size.setGravity(Gravity.RIGHT);
        size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        size.setTextColor(colorForSystem("#6B6F73", "#A9ADB0"));
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeLp.leftMargin = dp(12);
        metaRow.addView(size, sizeLp);
        texts.addView(metaRow);

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            if (managingBooks) {
                toggleBookSelection(book);
            } else {
                openBook(book);
            }
        });
        return row;
    }

    private void toggleBookSelection(Book book) {
        if (selectedBookIds.contains(book.id)) {
            selectedBookIds.remove(book.id);
        } else {
            selectedBookIds.add(book.id);
        }
        showLibrary();
    }

    private void deleteSelectedBooks() {
        List<Book> remaining = new ArrayList<>();
        for (Book book : books) {
            if (selectedBookIds.contains(book.id)) {
                cancelReaderCacheWrite(book);
                deleteReaderCache(book);
                File file = new File(book.path);
                if (file.exists()) {
                    boolean ignored = file.delete();
                }
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
            book.path = target.getAbsolutePath();
            book.fileSize = target.length();
            book.encoding = detectEncoding(target);
            book.offset = 0L;
            book.progress = 0f;
            book.fontSize = 20f;
            book.theme = THEME_SYSTEM;
            book.pageMode = true;
            book.sensitivity = SENSITIVITY_STANDARD;
            book.updatedAt = System.currentTimeMillis();
            books.add(0, book);
            saveBooks();
            showLibrary();
            Toast.makeText(this, "已添加：" + title, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "添加失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openBook(Book book) {
        readerMenusOpen = false;
        currentBook = book;
        showReader(book);
    }

    private void showReader(Book book) {
        releasePageSnapshot();
        fittedReaderBottomInset = -1;
        fittedReaderFontSize = -1f;
        fittedReaderVisibleHeight = -1;
        applyWindowColors(book.theme);
        int bg = backgroundColor(book.theme);
        int fg = textColor(book.theme);
        int menuBg = Color.rgb(247, 244, 239);
        int menuFg = Color.rgb(32, 33, 36);
        int menuMuted = Color.rgb(107, 111, 115);

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
        readerTopBar.setPadding(dp(12), 0, dp(12), dp(4));
        readerTopBar.setBackgroundColor(menuBg);
        readerTopBar.setClickable(true);
        readerTopBar.setVisibility(View.GONE);

        LinearLayout fontControls = new LinearLayout(this);
        fontControls.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);

        Button smaller = makeButton("A-");
        smaller.setTextColor(menuFg);
        smaller.setPadding(0, 0, 0, 0);
        smaller.setOnClickListener(v -> updateFontSize(-2f));
        fontControls.addView(smaller, new LinearLayout.LayoutParams(dp(56), dp(42)));

        Button larger = makeButton("A+");
        larger.setTextColor(menuFg);
        larger.setPadding(0, 0, 0, 0);
        larger.setOnClickListener(v -> updateFontSize(2f));
        LinearLayout.LayoutParams largerLp = new LinearLayout.LayoutParams(dp(56), dp(42));
        fontControls.addView(larger, largerLp);
        readerTopBar.addView(fontControls, new LinearLayout.LayoutParams(0, dp(42), 1));

        ImageButton search = makeIconButton();
        search.setImageResource(R.drawable.ic_search);
        search.setColorFilter(menuFg);
        search.setContentDescription("搜索当前书籍");
        search.setOnClickListener(v -> openSearchPage());
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(48), dp(42));
        searchLp.leftMargin = dp(6);
        readerTopBar.addView(search, searchLp);

        boolean darkTheme = isDarkReaderTheme(book.theme);
        ImageButton theme = makeIconButton();
        theme.setImageResource(darkTheme ? R.drawable.ic_moon : R.drawable.ic_sun);
        theme.setColorFilter(menuFg);
        theme.setContentDescription(darkTheme ? "深色主题" : "浅色主题");
        theme.setOnClickListener(v -> {
            saveCurrentProgress();
            book.theme = isDarkReaderTheme(book.theme) ? THEME_LIGHT : THEME_DARK;
            book.updatedAt = System.currentTimeMillis();
            saveBooks();
            applyReaderThemeInPlace(book);
            boolean nowDark = isDarkReaderTheme(book.theme);
            theme.setImageResource(nowDark ? R.drawable.ic_moon : R.drawable.ic_sun);
            theme.setContentDescription(nowDark ? "深色主题" : "浅色主题");
        });
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(dp(48), dp(42));
        themeLp.leftMargin = dp(6);
        readerTopBar.addView(theme, themeLp);

        Button sensitivity = makeButton(sensitivityShortLabel(book.sensitivity));
        sensitivity.setTextColor(menuFg);
        sensitivity.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sensitivity.setPadding(dp(4), 0, dp(4), 0);
        sensitivity.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_touch, 0, 0, 0);
        sensitivity.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(menuFg));
        sensitivity.setCompoundDrawablePadding(dp(2));
        sensitivity.setContentDescription("触控灵敏度");
        sensitivity.setOnClickListener(v -> {
            book.sensitivity = (book.sensitivity + 1) % 3;
            book.updatedAt = System.currentTimeMillis();
            sensitivity.setText(sensitivityShortLabel(book.sensitivity));
            saveBooks();
        });
        LinearLayout.LayoutParams sensitivityLp =
                new LinearLayout.LayoutParams(dp(76), dp(42));
        sensitivityLp.leftMargin = dp(6);
        readerTopBar.addView(sensitivity, sensitivityLp);

        readerScroll = new ScrollView(this);
        readerScroll.setFillViewport(true);
        readerScroll.setClipToPadding(true);
        readerScroll.setClipChildren(true);
        applyReaderSafePadding(book.fontSize);

        readerText = new TextView(this);
        readerText.setTextColor(fg);
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, book.fontSize);
        // The viewport is fitted to whole rendered rows, so font padding must not add
        // an extra partial row above or below the text layout.
        readerText.setIncludeFontPadding(false);
        readerText.setPadding(dp(22), 0, dp(22), 0);
        applyReaderLineSpacing(book.fontSize);
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
                if (isTap && handleReaderTap(event.getX())) {
                    return true;
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
            maybeLoadAdjacentWindow();
        });
        readerRoot.addView(readerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams readerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        readerLp.topMargin = readerTopInset();
        readerLp.bottomMargin = readerBottomInset(book.fontSize);
        frame.addView(readerRoot, readerLp);
        readerRoot.addOnLayoutChangeListener((v, left, top, right, bottom,
                                              oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            if (height > 0 && height != readerContainerHeight) {
                readerContainerHeight = height;
                if (readerText != null && currentBook != null) {
                    readerText.post(() -> fitReaderViewportToWholeLines(currentBook.fontSize));
                }
            }
        });

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.topMargin = readerTopInset();
        frame.addView(readerTopBar, topLp);

        readerBottomBar = new LinearLayout(this);
        readerBottomBar.setOrientation(LinearLayout.VERTICAL);
        readerBottomBar.setPadding(dp(14), dp(8), dp(14), dp(12));
        readerBottomBar.setBackgroundColor(menuBg);
        readerBottomBar.setClickable(true);
        readerBottomBar.setVisibility(View.GONE);

        seekPanel = new LinearLayout(this);
        seekPanel.setGravity(Gravity.CENTER_VERTICAL);
        seekPanel.setVisibility(View.GONE);
        TextView start = new TextView(this);
        start.setText("0%");
        start.setTextColor(menuMuted);
        seekPanel.addView(start, new LinearLayout.LayoutParams(dp(42), dp(44)));
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
                loadChunkAtProgress(seekBar.getProgress() / 1000f);
            }
        });
        seekPanel.addView(seekBar, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView end = new TextView(this);
        end.setText("100%");
        end.setTextColor(menuMuted);
        end.setGravity(Gravity.RIGHT);
        seekPanel.addView(end, new LinearLayout.LayoutParams(dp(52), dp(44)));
        readerBottomBar.addView(seekPanel);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        Button bigMinus = makeProgressStepButton("-", 20);
        bigMinus.setTextColor(menuFg);
        bigMinus.setOnClickListener(v -> adjustProgressByPercent(-1f));
        nav.addView(bigMinus, new LinearLayout.LayoutParams(dp(44), dp(46)));

        Button smallMinus = makeProgressStepButton("-", 14);
        smallMinus.setTextColor(menuFg);
        smallMinus.setOnClickListener(v -> adjustProgressByPercent(-0.01f));
        LinearLayout.LayoutParams smallMinusLp = new LinearLayout.LayoutParams(dp(38), dp(46));
        smallMinusLp.leftMargin = dp(6);
        nav.addView(smallMinus, smallMinusLp);

        progressButton = makeButton("");
        progressButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        progressButton.setTextColor(menuFg);
        progressButton.setOnClickListener(v -> toggleSeekPanel());
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        progressLp.leftMargin = dp(6);
        progressLp.rightMargin = dp(6);
        nav.addView(progressButton, progressLp);

        Button smallPlus = makeProgressStepButton("+", 14);
        smallPlus.setTextColor(menuFg);
        smallPlus.setOnClickListener(v -> adjustProgressByPercent(0.01f));
        nav.addView(smallPlus, new LinearLayout.LayoutParams(dp(38), dp(46)));

        Button bigPlus = makeProgressStepButton("+", 20);
        bigPlus.setTextColor(menuFg);
        bigPlus.setOnClickListener(v -> adjustProgressByPercent(1f));
        LinearLayout.LayoutParams bigPlusLp = new LinearLayout.LayoutParams(dp(44), dp(46));
        bigPlusLp.leftMargin = dp(6);
        nav.addView(bigPlus, bigPlusLp);
        readerBottomBar.addView(nav);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bottomLp.bottomMargin = readerBottomInset(book.fontSize);
        frame.addView(readerBottomBar, bottomLp);

        if (temporarySearchReading && searchSession != null) {
            ImageButton returnToSearch = makeIconButton();
            returnToSearch.setImageResource(R.drawable.ic_arrow_back);
            returnToSearch.setColorFilter(menuFg);
            returnToSearch.setBackgroundColor(menuBg);
            returnToSearch.setContentDescription("返回搜索结果");
            returnToSearch.setOnClickListener(v -> returnToSearchPage());
            FrameLayout.LayoutParams returnLp = new FrameLayout.LayoutParams(
                    dp(48), dp(48), Gravity.TOP | Gravity.LEFT);
            returnLp.leftMargin = dp(12);
            returnLp.topMargin = readerTopInset();
            frame.addView(returnToSearch, returnLp);
        }

        setContentView(frame);
        restoreReaderCache(book);
        frame.post(this::alignMenusToReaderViewport);
        loadChunkAtOffset(book.offset);
        if (readerMenusOpen) {
            frame.post(this::showReaderMenus);
        }
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

    private void returnToSearchPage() {
        if (currentBook == null || searchSession == null) return;
        saveCurrentProgress();
        flushReaderCacheWrite();
        loadRequestId++;
        releasePageSnapshot();
        temporarySearchReading = false;
        searchOpen = true;
        showSearchPage();
    }

    private void showSearchPage() {
        if (currentBook == null) return;
        Book book = currentBook;
        applyWindowColors(book.theme);

        int bg = backgroundColor(book.theme);
        int fg = textColor(book.theme);
        int muted = isDarkReaderTheme(book.theme)
                ? Color.rgb(176, 181, 184) : Color.rgb(96, 101, 105);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(18), statusBarHeight() + dp(12), dp(18), navigationBarHeight() + dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = makeIconButton();
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(fg);
        back.setContentDescription("返回阅读");
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setTextColor(fg);
        input.setHintTextColor(muted);
        input.setHint("搜索当前书籍");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        if (searchSession != null && searchSession.book == book) {
            input.setText(searchSession.query);
            input.setSelection(input.length());
        }
        top.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));

        ImageButton submit = makeIconButton();
        submit.setImageResource(R.drawable.ic_search);
        submit.setColorFilter(fg);
        submit.setContentDescription("开始搜索");
        top.addView(submit, new LinearLayout.LayoutParams(dp(52), dp(52)));
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
            status.setText("请输入要搜索的文字");
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
                    status.setText("搜索失败：" + finalError.getMessage());
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
            status.setText("当前位置向后已找到 " + session.results.size()
                    + " 条结果，是否从开头继续搜索？");
            continueFromStart.setVisibility(View.VISIBLE);
        } else if (session.complete) {
            status.setText("已回到最初搜索位置，共找到 " + session.results.size() + " 条结果");
        } else if (session.results.isEmpty()) {
            status.setText("继续下滑可搜索下一批结果");
        } else {
            status.setText("已加载 " + session.results.size() + " 条结果，继续下滑加载下一批");
        }
    }

    private View makeSearchResultRow(SearchResult result, Book book) {
        TextView row = new TextView(this);
        row.setText(result.snippet);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        row.setTextColor(textColor(book.theme));
        row.setLineSpacing(dp(4), 1f);
        row.setPadding(dp(4), dp(16), dp(4), dp(16));
        row.setBackgroundColor(backgroundColor(book.theme));
        row.setOnClickListener(v -> openSearchResult(book, result.offset));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(1);
        row.setLayoutParams(lp);
        return row;
    }

    private void openSearchResult(Book book, long offset) {
        searchRequestId++;
        searchOpen = false;
        temporarySearchReading = true;
        book.offset = Math.max(0L, Math.min(offset, Math.max(0L, book.fileSize)));
        book.progress = book.fileSize <= 0 ? 0f : book.offset / (float) book.fileSize;
        book.updatedAt = System.currentTimeMillis();
        saveBooks();
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
        Chunk context = readChunk(file, contextStart, readLength, encoding);
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
        if (currentBook.sensitivity == SENSITIVITY_HIGH) return 20;
        if (currentBook.sensitivity == SENSITIVITY_LOW) return 12;
        return 16;
    }

    private int swipeThresholdDp() {
        if (currentBook == null) return 72;
        if (currentBook.sensitivity == SENSITIVITY_HIGH) return 48;
        if (currentBook.sensitivity == SENSITIVITY_LOW) return 96;
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

    private void loadChunkAtProgress(float progress) {
        if (currentBook == null) return;
        long size = Math.max(1L, currentBook.fileSize);
        loadChunkAtOffset((long) (Math.max(0f, Math.min(1f, progress)) * size));
    }

    private void loadChunkAtOffset(long targetOffset) {
        if (currentBook == null) return;
        pendingWindowLoad = false;
        Book book = currentBook;
        long requestId = ++loadRequestId;
        loadingChunk = true;
        ioExecutor.execute(() -> {
            if (requestId != loadRequestId) return;
            ChunkLoad load = null;
            Exception error = null;
            try {
                load = prepareChunkLoad(book, targetOffset);
            } catch (Exception e) {
                error = e;
            }
            if (requestId != loadRequestId) return;
            ChunkLoad finalLoad = load;
            Exception finalError = error;
            mainHandler.post(() -> {
                if (requestId != loadRequestId || currentBook != book) return;
                loadingChunk = false;
                if (finalError != null) {
                    Toast.makeText(this, "打开失败：" + finalError.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                applyChunkLoad(book, finalLoad);
                preloadAdjacentWindows(book, finalLoad);
            });
        });
    }

    private ChunkLoad prepareChunkLoad(Book book, long targetOffset) throws Exception {
        File file = new File(book.path);
        long fileSize = file.length();
        long clampedTarget = fileSize <= 0
                ? 0L
                : Math.max(0L, Math.min(targetOffset, fileSize - 1L));
        requestBookIndex(book);
        long windowStart = hasBookIndex(book, fileSize)
                ? indexedReadableOffset(book, file, Math.max(0L, clampedTarget - CHUNK_BYTES))
                : findReadableOffset(file, Math.max(0L, clampedTarget - CHUNK_BYTES));
        Chunk chunk = readCachedChunk(file, windowStart, WINDOW_BYTES, book.encoding);
        return new ChunkLoad(fileSize, clampedTarget, windowStart, chunk);
    }

    private void applyChunkLoad(Book book, ChunkLoad load) {
        if (load == null || readerText == null || readerScroll == null) return;
        book.fileSize = load.fileSize;
        currentChunkOffset = load.windowStart;
        currentChunkBytes = load.chunk.bytesRead;
        book.offset = load.targetOffset;
        book.progress = load.fileSize <= 0 ? 0f : load.targetOffset / (float) load.fileSize;
        book.updatedAt = System.currentTimeMillis();
        pageStateGeneration++;
        readerText.setText(load.chunk.text);
        refreshReaderSpacing(false);
        readerScroll.post(() -> {
            scrollToOffsetWithinWindow(load.targetOffset);
            readerScroll.post(this::cacheCurrentPageSnapshot);
        });
        saveBooks();
        saveReaderCache(book, load);
        updateProgressText();
        if (seekBar != null && !seekTracking) {
            seekBar.setProgress((int) (book.progress * 1000f));
        }
    }

    private void restoreReaderCache(Book book) {
        ReaderCache cache = readReaderCache(book);
        if (cache == null || readerText == null || readerScroll == null) return;
        currentChunkOffset = cache.windowStart;
        currentChunkBytes = cache.bytesRead;
        book.fileSize = cache.fileSize;
        book.offset = Math.max(0L, Math.min(book.offset, cache.fileSize));
        book.progress = cache.fileSize <= 0 ? 0f : book.offset / (float) cache.fileSize;
        long displayOffset = Math.max(cache.windowStart,
                Math.min(book.offset, cache.windowStart + Math.max(0, cache.bytesRead)));
        pageStateGeneration++;
        readerText.setText(cache.text);
        refreshReaderSpacing(false);
        readerScroll.post(() -> {
            scrollToOffsetWithinWindow(displayOffset);
            readerScroll.post(this::cacheCurrentPageSnapshot);
        });
        updateProgressText();
        if (seekBar != null) seekBar.setProgress((int) (book.progress * 1000f));
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

    private void saveReaderCache(Book book, ChunkLoad load) {
        if (book == null || load == null || load.chunk == null) return;
        final File source = new File(book.path);
        pendingReaderCache = new ReaderCacheWrite(book, load.fileSize, source.lastModified(),
                load.windowStart, load.chunk.bytesRead, load.chunk.text);
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

    private void preloadAdjacentWindows(Book book, ChunkLoad loaded) {
        if (book == null || loaded == null || loaded.fileSize <= 0) return;
        ioExecutor.execute(() -> {
            try {
                File file = new File(book.path);
                ensureBookIndex(book, file);
                long prevTarget = Math.max(0L, loaded.windowStart - CHUNK_BYTES);
                long nextTarget = Math.min(Math.max(0L, loaded.fileSize - 1L),
                        loaded.windowStart + CHUNK_BYTES);
                if (loaded.windowStart > 0) {
                    long prevStart = indexedReadableOffset(book, file, prevTarget);
                    readCachedChunk(file, prevStart, WINDOW_BYTES, book.encoding);
                }
                if (nextTarget < loaded.fileSize - 1L) {
                    long nextStart = indexedReadableOffset(book, file, nextTarget);
                    readCachedChunk(file, nextStart, WINDOW_BYTES, book.encoding);
                }
            } catch (Exception ignored) {
            }
        });
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
        ioExecutor.execute(() -> {
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

    private void maybeLoadAdjacentWindow() {
        if (loadingChunk || pendingWindowLoad || currentBook == null || readerScroll == null || readerText == null) return;
        int maxScroll = maxReaderScroll();
        if (maxScroll <= 0) return;
        int y = readerScroll.getScrollY();
        if (y <= dp(24) && currentChunkOffset > 0) {
            long target = Math.max(0L, currentBook.offset);
            pendingWindowLoad = true;
            readerScroll.postDelayed(() -> loadChunkAtOffset(target), 120);
            return;
        }
        if (y >= maxScroll - dp(24)) {
            long loadedEnd = currentChunkOffset + Math.max(currentChunkBytes, 0);
            if (loadedEnd < currentBook.fileSize) {
                long target = Math.min(currentBook.fileSize - 1L, currentBook.offset);
                pendingWindowLoad = true;
                readerScroll.postDelayed(() -> loadChunkAtOffset(target), 120);
            }
        }
    }

    private void pageBackward() {
        if (pageAnimating) return;
        animatePageTurn(-1, this::performPageBackward);
    }

    private void pageForward() {
        if (pageAnimating) return;
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
            loadChunkAtOffset(Math.max(0L, currentBook.offset));
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
            loadChunkAtOffset(Math.min(currentBook.fileSize - 1L, currentBook.offset));
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
                    : backgroundColor(currentBook.theme);
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
        snapshotFontSize = currentBook.fontSize;
        snapshotTheme = currentBook.theme;
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
                && Float.compare(snapshotFontSize, currentBook.fontSize) == 0
                && snapshotTheme == currentBook.theme
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
        if (readerScroll == null || readerText == null) return;
        if (readerText.getLayout() == null) {
            readerText.post(() -> scrollToOffsetWithinWindow(targetOffset));
            return;
        }
        int maxScroll = maxReaderScroll();
        if (maxScroll == 0 || currentChunkBytes <= 0) {
            pageStateGeneration++;
            readerScroll.scrollTo(0, 0);
            return;
        }
        float within = (targetOffset - currentChunkOffset) / (float) currentChunkBytes;
        within = Math.max(0f, Math.min(1f, within));
        pageStateGeneration++;
        readerScroll.scrollTo(0, snapToLineTop(Math.round(maxScroll * within)));
    }

    private Chunk readChunk(File file, long offset, int maxBytes, String encoding) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(Math.max(0L, Math.min(offset, file.length())));
            int length = (int) Math.min(maxBytes, Math.max(0L, file.length() - raf.getFilePointer()));
            byte[] bytes = new byte[length];
            int read = raf.read(bytes);
            if (read <= 0) return new Chunk("", 0);
            String text = new String(bytes, 0, read, Charset.forName(encoding == null ? "UTF-8" : encoding));
            return new Chunk(text, read);
        }
    }

    private Chunk readCachedChunk(File file, long offset, int maxBytes, String encoding) throws Exception {
        long safeOffset = Math.max(0L, Math.min(offset, Math.max(0L, file.length() - 1L)));
        String key = file.getAbsolutePath() + ":" + file.length() + ":" + encoding + ":" + safeOffset;
        synchronized (chunkCache) {
            Chunk cached = chunkCache.get(key);
            if (cached != null) return cached;
        }
        Chunk chunk = readChunk(file, safeOffset, maxBytes, encoding);
        synchronized (chunkCache) {
            chunkCache.put(key, chunk);
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
                long offset = findReadableOffset(raf, size, target);
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
        return findReadableOffset(file, targetOffset);
    }

    private long findReadableOffset(File file, long targetOffset) throws Exception {
        long size = file.length();
        if (targetOffset <= 0 || size <= 0) return 0L;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return findReadableOffset(raf, size, targetOffset);
        }
    }

    private long findReadableOffset(RandomAccessFile raf, long size, long targetOffset) throws Exception {
        if (targetOffset <= 0 || size <= 0) return 0L;
        long offset = Math.min(targetOffset, Math.max(0L, size - 1));
        long start = Math.max(0L, offset - 4096L);
        raf.seek(offset);
        while (raf.getFilePointer() > start) {
            raf.seek(raf.getFilePointer() - 1);
            int b = raf.read();
            if (b == '\n') {
                return Math.min(size, raf.getFilePointer());
            }
            raf.seek(raf.getFilePointer() - 1);
        }
        return offset;
    }

    private void toggleSeekPanel() {
        if (seekPanel != null) {
            seekPanel.setVisibility(seekPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }

    private void showReaderMenus() {
        saveCurrentProgress();
        readerMenusOpen = true;
        alignMenusToReaderViewport();
        if (seekPanel != null) seekPanel.setVisibility(View.VISIBLE);
        animateMenuIn(readerTopBar, -dp(72));
        animateMenuIn(readerBottomBar, dp(120));
    }

    private void hideReaderMenus() {
        saveCurrentProgress();
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
        loadChunkAtProgress(next);
    }

    private void updateFontSize(float delta) {
        if (currentBook == null || readerText == null) return;
        saveCurrentProgress();
        currentBook.fontSize = Math.max(14f, Math.min(34f, currentBook.fontSize + delta));
        currentBook.updatedAt = System.currentTimeMillis();
        pageStateGeneration++;
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentBook.fontSize);
        refreshReaderSpacing(true);
        saveBooks();
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
        float fontSize = currentBook.fontSize;
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
                Math.max(2f, fontSize * 0.18f),
                getResources().getDisplayMetrics());
        readerText.setLineSpacing(extra, 1.0f);
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
        return systemDimension("status_bar_height");
    }

    private int navigationBarHeight() {
        return systemDimension("navigation_bar_height");
    }

    private int systemDimension(String name) {
        int resourceId = getResources().getIdentifier(name, "dimen", "android");
        if (resourceId <= 0) return 0;
        return getResources().getDimensionPixelSize(resourceId);
    }

    private int readerBottomOverlayHeight() {
        if (readerBottomBar == null || readerBottomBar.getVisibility() != View.VISIBLE) return 0;
        int height = readerBottomBar.getHeight();
        if (height > 0) return height;
        int seekHeight = seekPanel != null && seekPanel.getVisibility() == View.VISIBLE ? dp(44) : 0;
        return seekHeight + dp(46) + dp(20);
    }

    private void saveCurrentProgress() {
        if (currentBook == null || readerScroll == null || readerText == null) return;
        int maxScroll = maxReaderScroll();
        float chunkScroll = maxScroll == 0 ? 0f : Math.max(0f, Math.min(1f, readerScroll.getScrollY() / (float) maxScroll));
        long visibleOffset = currentChunkOffset + Math.round(currentChunkBytes * chunkScroll);
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

    private String sensitivityShortLabel(int sensitivity) {
        if (sensitivity == SENSITIVITY_HIGH) return "高";
        if (sensitivity == SENSITIVITY_LOW) return "低";
        return "中";
    }

    private Button makeProgressStepButton(String text, int sp) {
        Button button = makeButton(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private ImageButton makeIconButton() {
        ImageButton button = new ImageButton(this);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setColorFilter(colorForSystem("#202124", "#F2F0EA"));
        return button;
    }

    private int backgroundColor(int theme) {
        if (theme == THEME_LIGHT) return Color.rgb(247, 244, 239);
        if (theme == THEME_DARK) return Color.rgb(17, 20, 22);
        return colorForSystem("#F7F4EF", "#111416");
    }

    private boolean isDarkReaderTheme(int theme) {
        return theme == THEME_DARK || (theme == THEME_SYSTEM && isSystemNight());
    }

    private void applyReaderThemeInPlace(Book book) {
        int bg = backgroundColor(book.theme);
        int fg = textColor(book.theme);
        applyWindowColors(book.theme);
        if (readerFrame != null) readerFrame.setBackgroundColor(bg);
        if (readerRoot != null) readerRoot.setBackgroundColor(bg);
        if (readerScroll != null) readerScroll.setBackgroundColor(bg);
        if (readerText != null) readerText.setTextColor(fg);
        pageStateGeneration++;
        releasePageSnapshot();
        if (readerScroll != null) {
            readerScroll.post(this::cacheCurrentPageSnapshot);
        }
    }

    private int textColor(int theme) {
        if (theme == THEME_LIGHT) return Color.rgb(32, 33, 36);
        if (theme == THEME_DARK) return Color.rgb(242, 240, 234);
        return colorForSystem("#202124", "#F2F0EA");
    }

    private int mutedTextColor(int theme) {
        if (theme == THEME_LIGHT) return Color.rgb(107, 111, 115);
        if (theme == THEME_DARK) return Color.rgb(180, 184, 186);
        return colorForSystem("#6B6F73", "#B4B8BA");
    }

    private int colorForSystem(String light, String dark) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return Color.parseColor(night ? dark : light);
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
        byte[] bytes = new byte[4096];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(bytes);
            if (read >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                return "UTF-8";
            }
            if (read >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return "UTF-16LE";
            }
            if (read >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return "UTF-16BE";
            }
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, Math.max(0, read)));
                return "UTF-8";
            } catch (CharacterCodingException ignored) {
                return "GB18030";
            }
        } catch (Exception ignored) {
            return "GB18030";
        }
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
        long value = Math.max(0L, bytes);
        if (value < 1024L) return value + " Byte";
        if (value < 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f KB", value / 1024f);
        if (value < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", value / (1024f * 1024f));
        }
        return String.format(Locale.getDefault(), "%.1f GB", value / (1024f * 1024f * 1024f));
    }

    private void loadBooks() {
        books.clear();
        String raw = prefs.getString(KEY_BOOKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Book book = new Book();
                book.id = item.optLong("id");
                book.title = item.optString("title");
                book.path = item.optString("path");
                book.fileSize = item.optLong("fileSize", 0L);
                book.encoding = item.optString("encoding", "UTF-8");
                book.offset = item.optLong("offset", 0L);
                book.progress = (float) item.optDouble("progress", 0d);
                book.fontSize = (float) item.optDouble("fontSize", 20d);
                book.theme = item.optInt("theme", THEME_SYSTEM);
                book.pageMode = item.optBoolean("pageMode", true);
                book.sensitivity = Math.max(
                        SENSITIVITY_HIGH,
                        Math.min(SENSITIVITY_LOW,
                                item.optInt("sensitivity", SENSITIVITY_STANDARD)));
                book.updatedAt = item.optLong("updatedAt", System.currentTimeMillis());
                File file = new File(book.path);
                if (!TextUtils.isEmpty(book.path) && file.exists()) {
                    book.fileSize = file.length();
                    if (TextUtils.isEmpty(book.encoding)) book.encoding = detectEncoding(file);
                    if (book.offset <= 0 && book.progress > 0) book.offset = (long) (book.fileSize * book.progress);
                    books.add(book);
                }
            }
        } catch (Exception ignored) {
            books.clear();
        }
    }

    private void saveBooks() {
        try {
            JSONArray array = new JSONArray();
            for (Book book : books) {
                JSONObject item = new JSONObject();
                item.put("id", book.id);
                item.put("title", book.title);
                item.put("path", book.path);
                item.put("fileSize", book.fileSize);
                item.put("encoding", book.encoding);
                item.put("offset", book.offset);
                item.put("progress", book.progress);
                item.put("fontSize", book.fontSize);
                item.put("theme", book.theme);
                item.put("pageMode", book.pageMode);
                item.put("sensitivity", book.sensitivity);
                item.put("updatedAt", book.updatedAt);
                array.put(item);
            }
            prefs.edit().putString(KEY_BOOKS, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static class Book {
        long id;
        String title;
        String path;
        long fileSize;
        String encoding;
        long offset;
        float progress;
        float fontSize;
        int theme;
        boolean pageMode;
        int sensitivity;
        long updatedAt;
        transient long indexFileSize;
        transient ArrayList<Long> indexOffsets;
        transient boolean indexBuilding;
    }

    private static class Chunk {
        final String text;
        final int bytesRead;

        Chunk(String text, int bytesRead) {
            this.text = text;
            this.bytesRead = bytesRead;
        }
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
            this.nextOffset = originOffset;
        }
    }

    private static class ChunkLoad {
        final long fileSize;
        final long targetOffset;
        final long windowStart;
        final Chunk chunk;

        ChunkLoad(long fileSize, long targetOffset, long windowStart, Chunk chunk) {
            this.fileSize = fileSize;
            this.targetOffset = targetOffset;
            this.windowStart = windowStart;
            this.chunk = chunk;
        }
    }

}
