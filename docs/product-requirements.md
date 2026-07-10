# XLib TXT Reader Product Requirements

## 1. Scope And Priority

XLib is a fast, simple, native Android TXT reader. The product starts on the
bookshelf and has two primary user-facing areas: the bookshelf and the reader.

When requirements conflict, the newest confirmed rule in this document takes
priority. Historical alternatives are intentionally omitted.

## 2. Release Rules

- Current version source: `version.properties`.
- The bookshelf displays the installed app version as `v<versionName> build <versionCode>`.
- A successful `assembleDebug` build outputs `xlib-debug.apk` and then increments
  `versionCode` by one. `versionName` changes only when explicitly requested.
- Do not package an APK unless the user explicitly requests packaging.

## 3. Bookshelf

- The app opens on the bookshelf.
- A book row opens the reader when tapped; there is no separate Read button.
- Add TXT and Manage Books use icons rather than text buttons.
- Manage Books supports selecting multiple books and deleting the selected books.
- The bookshelf title leaves enough top safe-area spacing to avoid the system bar.
- Each book row shows:
  - Title on the first line.
  - Reading progress with two decimal places and the last-read time on the left of
    the second line.
  - Current file size on the right of the second line, formatted as `Byte`, `KB`,
    `MB`, or `GB`.
- The bookshelf bottom-left displays the app version and build number.
- The launcher icon uses the supplied source image, sized to approximately 60% of
  its icon background without crossing the icon boundary.

## 4. Reading Position And Large Files

- Each book persists its reading position and resumes from that position.
- TXT files around 30 MB to 100 MB must be handled without loading the full file
  into memory.
- Text is read in windows and adjacent windows are preloaded. A byte-offset index
  is built in the background to make large-file seeking practical.
- The reader keeps a disk cache of the most recent reading window. On reopening a
  book, it displays this cache first, then validates and refreshes from the source
  file in the background using the saved reading position.
- Cache writes are not performed for every page turn. The latest window is written
  after a short idle delay (currently 800 ms), on a separate background executor.
- A source file size or modification-time change invalidates its reader cache.

## 5. Reader Layout

- The reader has a fixed-height container. Its bounds exclude the status bar,
  navigation area, and bottom rounded-corner area.
- The text viewport is a child of that fixed container. It calculates how many
  complete text rows fit, discards any remainder smaller than a row, and must not
  show clipped half-rows at the top or bottom.
- Floating top and bottom menus align exactly with the fixed reader container's
  top and bottom bounds. Showing or hiding menus must not resize the reader
  container or move the text viewport.
- Menus animate in from the top and bottom with fade, and animate out in the
  opposite directions. They hide only after a touch inside the lower reader
  content area, not after interacting with a menu control.
- Opening menus temporarily disables page-turn gestures until the user dismisses
  the menus by touching reader content.

## 6. Reader Controls

- Menus are hidden during immersive reading. In page mode, tapping the center 30%
  of the content area shows the menus.
- The top menu is always light themed and contains:
  - Font decrease (`A-`) and increase (`A+`) controls, left aligned.
  - A search icon.
  - A light/dark theme toggle using sun and moon icons. The reader can also follow
    the system theme.
  - A touch-sensitivity control with three levels: high, standard, and low.
- The bottom menu contains a seek bar, `0%` and `100%` labels, and progress steps:
  - Progress is displayed with two decimal places.
  - Large minus/plus changes progress by `1%`.
  - Small minus/plus changes progress by `0.01%`.
- The seek bar updates the target preview while dragging and loads the final target
  position when the drag ends. It must not cause the bottom menu to jump.

## 7. Reading Modes And Gestures

- Newly added books default to left/right page mode.
- The product supports vertical scrolling and left/right page mode. A mode-switch
  control is part of the required reader controls and must accurately show and
  change the current mode. This needs device verification because the current UI
  implementation retains the mode state but does not expose a visible switch.
- In page mode:
  - Left 30% tap: previous page.
  - Center 30% tap: show menus.
  - Right 40% tap: next page.
  - Swiping from left to right: previous page.
  - Swiping from right to left: next page.
  - Tap and swipe are distinct; a drag must not be treated as a tap.
- A standard edge-back gesture from outside-left toward the right returns to the
  bookshelf when not in a search preview.
- Page-turn animation duration is 0.3 seconds. The turning sheet uses the current
  page as an opaque snapshot, with the destination page underneath. It must not
  expose transparent text, flash a blank page, bounce back, or reverse direction.

## 8. In-Book Search

- The search entry is a magnifier in the reader top menu, to the left of the
  reading-mode control area.
- The search page uses one top row: back arrow, input field, and search-confirm
  magnifier. The input field must not occupy a separate row.
- Search query rules:
  - Minimum 2 characters.
  - Maximum 32 characters.
  - The input control and submission validation both enforce the limits.
- Search runs in the background using byte-stream scanning, so full text is not
  loaded into memory.
- Results are exact-text matches, ordered from the reader position toward the end
  of the file. Each result shows approximately 45 characters before and after the
  match, plus the full matched query.
- Search is paged in batches of up to 200 results:
  - The first pass searches from the original reading position toward file end.
  - If fewer than 200 results are found before file end, offer an explicit
    `从开头继续搜索` action.
  - Continuing from the beginning searches only up to the original reading
    position, then finishes to avoid duplicates and looping forever.
  - Reaching the bottom of the displayed results loads the next 200-result batch
    in the same direction. Existing result rows stay visible and the scroll
    position must not jump to the top.
- The matched query has a clearly visible background highlight in both the search
  results and the temporary result-reading page. Highlight colors must remain
  legible in light and dark themes.

## 9. Search Result Preview

- Tapping a search result opens a temporary reader at that result's location.
- Temporary reader navigation supports normal forward and backward page reading.
- A back arrow remains visible at the temporary reader's top-left and returns to
  the search page.
- The search query, loaded results, and pending continuation position remain
  available after returning to search.
- Temporary preview reading must never replace the primary reader's saved progress
  or its normal reader-window cache. Leaving search and returning to ordinary
  reading resumes at the position that was active before search began.

## 10. Acceptance Focus

- Verify the reader on actual target devices after each substantial layout change:
  no clipped top/bottom text, stable floating menus, and no system-bar overlap.
- Verify both page-turn directions and their animation snapshots after changes to
  loading, caching, or layout.
- Verify large-file opening, seek dragging, cached reopen, search pagination, and
  temporary search preview with at least one 30 MB or larger TXT file.
- Verify search mode switching, as the visible vertical/page-mode toggle still
  needs to be restored or confirmed in the UI.
