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
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int PICK_TXT_REQUEST = 1001;
    private static final String PREFS = "xlib_reader";
    private static final String KEY_BOOKS = "books";
    private static final int THEME_SYSTEM = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;
    private static final int CHUNK_BYTES = 128 * 1024;
    private static final int WINDOW_BYTES = CHUNK_BYTES * 3;

    private final List<Book> books = new ArrayList<>();
    private final Set<Long> selectedBookIds = new HashSet<>();
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
    private boolean readerMenusOpen;
    private float touchStartX;
    private float touchStartY;
    private long currentChunkOffset;
    private int currentChunkBytes;
    private FrameLayout readerFrame;
    private LinearLayout readerRoot;
    private ScrollView readerScroll;
    private TextView readerText;
    private TextView readerTitle;
    private LinearLayout readerTopBar;
    private LinearLayout readerBottomBar;
    private Button progressButton;
    private LinearLayout seekPanel;
    private SeekBar seekBar;

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
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (currentBook != null) {
            saveCurrentProgress();
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

        TextView meta = new TextView(this);
        meta.setText(String.format(Locale.getDefault(), "%.0f%% · %s",
                book.progress * 100f,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(book.updatedAt))));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setTextColor(colorForSystem("#6B6F73", "#A9ADB0"));
        meta.setPadding(0, dp(5), 0, 0);
        texts.addView(meta);

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
        applyWindowColors(book.theme);
        int bg = backgroundColor(book.theme);
        int fg = textColor(book.theme);
        int muted = mutedTextColor(book.theme);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(bg);
        readerFrame = frame;

        readerRoot = new LinearLayout(this);
        readerRoot.setOrientation(LinearLayout.VERTICAL);
        readerRoot.setBackgroundColor(bg);

        readerTopBar = new LinearLayout(this);
        readerTopBar.setGravity(Gravity.CENTER_VERTICAL);
        readerTopBar.setPadding(dp(12), 0, dp(12), dp(4));
        readerTopBar.setBackgroundColor(bg);
        readerTopBar.setClickable(true);
        readerTopBar.setVisibility(View.GONE);

        readerTitle = new TextView(this);
        readerTitle.setText(book.title);
        readerTitle.setSingleLine(true);
        readerTitle.setEllipsize(TextUtils.TruncateAt.END);
        readerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        readerTitle.setTextColor(fg);
        readerTitle.setPadding(dp(10), 0, dp(10), 0);
        readerTopBar.addView(readerTitle, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button smaller = makeButton("A-");
        smaller.setOnClickListener(v -> updateFontSize(-2f));
        readerTopBar.addView(smaller, new LinearLayout.LayoutParams(dp(54), dp(42)));

        Button larger = makeButton("A+");
        larger.setOnClickListener(v -> updateFontSize(2f));
        LinearLayout.LayoutParams largerLp = new LinearLayout.LayoutParams(dp(54), dp(42));
        largerLp.leftMargin = dp(6);
        readerTopBar.addView(larger, largerLp);

        Spinner theme = new Spinner(this);
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"跟随系统", "浅色", "深色"});
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        theme.setAdapter(themeAdapter);
        theme.setSelection(book.theme, false);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == book.theme) return;
                saveCurrentProgress();
                book.theme = position;
                book.updatedAt = System.currentTimeMillis();
                saveBooks();
                showReader(book);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(dp(108), dp(42));
        themeLp.leftMargin = dp(6);
        readerTopBar.addView(theme, themeLp);

        readerScroll = new ScrollView(this);
        readerScroll.setFillViewport(true);
        readerScroll.setClipToPadding(true);
        applyReaderSafePadding(book.fontSize);

        readerText = new TextView(this);
        readerText.setTextColor(fg);
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, book.fontSize);
        readerText.setIncludeFontPadding(true);
        readerText.setPadding(dp(22), 0, dp(22), 0);
        applyReaderLineSpacing(book.fontSize);
        readerScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (pageAnimating) return true;
            if (action == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getX();
                touchStartY = event.getY();
                edgeBackCandidate = touchStartX <= dp(16);
                edgeBackTriggered = false;
                touchMoved = false;
                touchStartedWithMenusOpen = areReaderMenusVisible();
                if (edgeBackCandidate) {
                    return true;
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - touchStartX;
                float dy = Math.abs(event.getY() - touchStartY);
                if (Math.abs(dx) > dp(16) || dy > dp(16)) {
                    touchMoved = true;
                }
                if (edgeBackCandidate && !edgeBackTriggered && dx >= dp(72) && dy <= dp(96)) {
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
                            && Math.abs(dx) <= dp(16)
                            && dy <= dp(16);
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
                        && Math.abs(event.getX() - touchStartX) <= dp(16)
                        && Math.abs(event.getY() - touchStartY) <= dp(16);
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
                        && Math.abs(event.getX() - touchStartX) <= dp(16)
                        && Math.abs(event.getY() - touchStartY) <= dp(16);
                if (isTap && handleReaderTap(event.getX())) {
                    return true;
                }
            }
            return currentBook != null && currentBook.pageMode;
        });
        readerScroll.addView(readerText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        readerScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            saveCurrentProgress();
            updateProgressText();
            maybeLoadAdjacentWindow();
        });
        readerRoot.addView(readerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        frame.addView(readerRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.topMargin = readerTopInset();
        frame.addView(readerTopBar, topLp);

        readerBottomBar = new LinearLayout(this);
        readerBottomBar.setOrientation(LinearLayout.VERTICAL);
        readerBottomBar.setPadding(dp(14), dp(8), dp(14), dp(12));
        readerBottomBar.setBackgroundColor(bg);
        readerBottomBar.setClickable(true);
        readerBottomBar.setVisibility(View.GONE);

        seekPanel = new LinearLayout(this);
        seekPanel.setGravity(Gravity.CENTER_VERTICAL);
        seekPanel.setVisibility(View.GONE);
        TextView start = new TextView(this);
        start.setText("0%");
        start.setTextColor(muted);
        seekPanel.addView(start, new LinearLayout.LayoutParams(dp(42), dp(44)));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgress((int) (book.progress * 1000f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) loadChunkAtProgress(progress / 1000f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                loadChunkAtProgress(seekBar.getProgress() / 1000f);
                saveCurrentProgress();
            }
        });
        seekPanel.addView(seekBar, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView end = new TextView(this);
        end.setText("100%");
        end.setTextColor(muted);
        end.setGravity(Gravity.RIGHT);
        seekPanel.addView(end, new LinearLayout.LayoutParams(dp(52), dp(44)));
        readerBottomBar.addView(seekPanel);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        Button bigMinus = makeProgressStepButton("-", 20);
        bigMinus.setOnClickListener(v -> adjustProgressByPercent(-1f));
        nav.addView(bigMinus, new LinearLayout.LayoutParams(dp(44), dp(46)));

        Button smallMinus = makeProgressStepButton("-", 14);
        smallMinus.setOnClickListener(v -> adjustProgressByPercent(-0.1f));
        LinearLayout.LayoutParams smallMinusLp = new LinearLayout.LayoutParams(dp(38), dp(46));
        smallMinusLp.leftMargin = dp(6);
        nav.addView(smallMinus, smallMinusLp);

        progressButton = makeButton("");
        progressButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        progressButton.setTextColor(fg);
        progressButton.setOnClickListener(v -> toggleSeekPanel());
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        progressLp.leftMargin = dp(6);
        progressLp.rightMargin = dp(6);
        nav.addView(progressButton, progressLp);

        Button smallPlus = makeProgressStepButton("+", 14);
        smallPlus.setOnClickListener(v -> adjustProgressByPercent(0.1f));
        nav.addView(smallPlus, new LinearLayout.LayoutParams(dp(38), dp(46)));

        Button bigPlus = makeProgressStepButton("+", 20);
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

        setContentView(frame);
        frame.post(this::alignMenusToReaderViewport);
        loadChunkAtOffset(book.offset);
        if (readerMenusOpen) {
            frame.post(this::showReaderMenus);
        }
    }

    private boolean handlePageSwipe(float dx, float dy) {
        boolean isHorizontalSwipe = Math.abs(dx) >= dp(72) && Math.abs(dx) > dy * 1.2f;
        if (!isHorizontalSwipe) return false;
        if (dx > 0) {
            pageBackward();
        } else {
            pageForward();
        }
        return true;
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
        if (currentBook == null || loadingChunk) return;
        pendingWindowLoad = false;
        loadingChunk = true;
        try {
            File file = new File(currentBook.path);
            currentBook.fileSize = file.length();
            long clampedTarget = Math.max(0L, Math.min(targetOffset, Math.max(0L, currentBook.fileSize - 1L)));
            long windowStart = findReadableOffset(file, Math.max(0L, clampedTarget - CHUNK_BYTES));
            currentChunkOffset = windowStart;
            Chunk chunk = readChunk(file, currentChunkOffset, WINDOW_BYTES, currentBook.encoding);
            currentChunkBytes = chunk.bytesRead;
            currentBook.offset = clampedTarget;
            currentBook.progress = currentBook.fileSize <= 0 ? 0f : clampedTarget / (float) currentBook.fileSize;
            currentBook.updatedAt = System.currentTimeMillis();
            readerText.setText(chunk.text);
            refreshReaderSpacing();
            readerScroll.post(() -> scrollToOffsetWithinWindow(clampedTarget));
            saveBooks();
            updateProgressText();
            if (seekBar != null) seekBar.setProgress((int) (currentBook.progress * 1000f));
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            loadingChunk = false;
        }
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
        try {
            pageBitmap = Bitmap.createBitmap(
                    readerScroll.getWidth(),
                    readerScroll.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas pageCanvas = new Canvas(pageBitmap);
            int pageColor = currentBook == null
                    ? Color.WHITE
                    : backgroundColor(currentBook.theme);
            pageCanvas.drawColor(pageColor);
            readerScroll.draw(pageCanvas);
        } catch (RuntimeException e) {
            pageAnimating = false;
            turnAction.run();
            return;
        }

        ImageView turningPage = new ImageView(this);
        turningPage.setImageBitmap(pageBitmap);
        turningPage.setScaleType(ImageView.ScaleType.FIT_XY);
        turningPage.setCameraDistance(getResources().getDisplayMetrics().density * 12000f);
        turningPage.setPivotX(direction > 0 ? 0f : readerScroll.getWidth());
        turningPage.setPivotY(readerScroll.getHeight() / 2f);
        turningPage.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                readerScroll.getHeight(),
                Gravity.TOP);
        pageLp.topMargin = readerTopInset();
        readerFrame.addView(turningPage, pageLp);

        // The destination page is rendered underneath while the captured page turns away.
        turnAction.run();
        turningPage.animate()
                .rotationY(direction > 0 ? -180f : 180f)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setDuration(520)
                .withEndAction(() -> {
                    readerFrame.removeView(turningPage);
                    turningPage.setImageDrawable(null);
                    pageBitmap.recycle();
                    pageAnimating = false;
                })
                .start();
    }

    private void scrollToOffsetWithinWindow(long targetOffset) {
        if (readerScroll == null || readerText == null) return;
        if (readerText.getLayout() == null) {
            readerText.post(() -> scrollToOffsetWithinWindow(targetOffset));
            return;
        }
        int maxScroll = maxReaderScroll();
        if (maxScroll == 0 || currentChunkBytes <= 0) {
            readerScroll.scrollTo(0, 0);
            return;
        }
        float within = (targetOffset - currentChunkOffset) / (float) currentChunkBytes;
        within = Math.max(0f, Math.min(1f, within));
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

    private long findReadableOffset(File file, long targetOffset) throws Exception {
        long size = file.length();
        if (targetOffset <= 0 || size <= 0) return 0L;
        long offset = Math.min(targetOffset, Math.max(0L, size - 1));
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
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
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentBook.fontSize);
        refreshReaderSpacing();
        saveBooks();
        readerScroll.post(() -> {
            int y = Math.min(maxReaderScroll(), snapToLineTop(readerScroll.getScrollY()));
            readerScroll.scrollTo(0, y);
            saveCurrentProgress();
            updateProgressText();
        });
    }

    private void refreshReaderSpacingAndPosition() {
        refreshReaderSpacing();
        if (readerScroll == null || currentBook == null) return;
        readerScroll.post(() -> {
            refreshReaderSpacing();
            scrollToOffsetWithinWindow(currentBook.offset);
            updateProgressText();
        });
    }

    private void refreshReaderSpacing() {
        if (currentBook == null) return;
        float fontSize = currentBook.fontSize;
        applyReaderSafePadding(fontSize);
        applyReaderLineSpacing(fontSize);
        if (readerText != null) {
            readerText.post(() -> fitReaderViewportToWholeLines(fontSize));
        }
    }

    private void applyReaderLineSpacing(float fontSize) {
        if (readerText == null) return;
        int defaultExtra = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                Math.max(2f, fontSize * 0.18f),
                getResources().getDisplayMetrics());
        int extra = defaultExtra;
        int fontHeight = readerText.getPaint().getFontMetricsInt(null);
        int textArea = readerVisibleHeight() - dp(8);
        int defaultLineHeight = Math.max(1, fontHeight + defaultExtra);
        if (textArea > defaultLineHeight * 2) {
            int fullLines = Math.max(1, textArea / defaultLineHeight);
            int fittedLineHeight = Math.max(defaultLineHeight, textArea / fullLines);
            extra = Math.max(0, fittedLineHeight - fontHeight);
        }
        readerText.setLineSpacing(extra, 1.0f);
    }

    private void applyReaderSafePadding(float fontSize) {
        if (readerRoot == null || readerScroll == null) return;
        int topInset = readerTopInset();
        int bottomInset = readerBottomInset(fontSize);
        readerRoot.setPadding(0, topInset, 0, bottomInset);
        readerScroll.setPadding(0, 0, 0, 0);
        if (readerTopBar != null && readerTopBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerTopBar.getLayoutParams();
            lp.topMargin = topInset;
            readerTopBar.setLayoutParams(lp);
        }
        if (readerBottomBar != null && readerBottomBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerBottomBar.getLayoutParams();
            lp.bottomMargin = bottomInset;
            readerBottomBar.setLayoutParams(lp);
        }
    }

    private int readerTopInset() {
        return statusBarHeight() + dp(7);
    }

    private int readerBottomInset(float fontSize) {
        int oneLine = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSize + 6f,
                getResources().getDisplayMetrics());
        return navigationBarHeight() + oneLine / 2 + dp(5);
    }

    private void fitReaderViewportToWholeLines(float fontSize) {
        if (readerRoot == null || readerScroll == null || readerText == null
                || readerText.getLayout() == null || readerRoot.getHeight() <= 0) {
            return;
        }
        Layout layout = readerText.getLayout();
        if (layout.getLineCount() <= 0) return;
        int lineHeight = Math.max(1, layout.getLineBottom(0) - layout.getLineTop(0));
        int baseBottom = readerBottomInset(fontSize);
        int available = readerRoot.getHeight() - readerTopInset() - baseBottom;
        int clippedRemainder = Math.max(0, available % lineHeight);
        int fittedBottom = baseBottom + clippedRemainder;
        if (readerRoot.getPaddingBottom() != fittedBottom) {
            readerRoot.setPadding(0, readerTopInset(), 0, fittedBottom);
            readerRoot.post(this::alignMenusToReaderViewport);
        }
    }

    private void alignMenusToReaderViewport() {
        if (readerFrame == null || readerRoot == null || readerScroll == null
                || readerFrame.getHeight() <= 0) {
            return;
        }
        int viewportTop = readerRoot.getTop() + readerScroll.getTop();
        int viewportBottom = readerRoot.getTop() + readerScroll.getBottom();
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
        int target = currentY + visible;
        int line = Math.min(layout.getLineCount() - 1, layout.getLineForVertical(target));
        if (layout.getLineTop(line) < target && line + 1 < layout.getLineCount()) {
            line++;
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
            progressButton.setText(String.format(Locale.getDefault(), "%.1f%%", currentBook.progress * 100f));
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return button;
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
        long updatedAt;
    }

    private static class Chunk {
        final String text;
        final int bytesRead;

        Chunk(String text, int bytesRead) {
            this.text = text;
            this.bytesRead = bytesRead;
        }
    }

}
