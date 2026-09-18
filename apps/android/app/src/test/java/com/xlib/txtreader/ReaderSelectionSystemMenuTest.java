package com.xlib.txtreader;

import android.view.ActionMode;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Entry-point contract only; actual OEM touch dispatch still needs device testing. */
public class ReaderSelectionSystemMenuTest {
    @Test public void readerRejectsBothSystemActionModeEntryPoints() throws Exception {
        ReaderPageTextView.class.getDeclaredMethod("startActionMode", ActionMode.Callback.class);
        ReaderPageTextView.class.getDeclaredMethod("startActionMode", ActionMode.Callback.class, int.class);
        ReaderPageTextView view = mock(ReaderPageTextView.class, CALLS_REAL_METHODS);
        ActionMode.Callback callback = mock(ActionMode.Callback.class);
        assertNull(view.startActionMode(callback));
        assertNull(view.startActionMode(callback, ActionMode.TYPE_FLOATING));
        verifyNoInteractions(callback);
    }

    @Test public void readerDoesNotDelegateLongPressOrContextCopyToTextView() throws Exception {
        ReaderPageTextView.class.getDeclaredMethod("showContextMenu");
        ReaderPageTextView.class.getDeclaredMethod("showContextMenu", float.class, float.class);
        ReaderPageTextView.class.getDeclaredMethod("onTextContextMenuItem", int.class);
        ReaderPageTextView.class.getDeclaredMethod("performLongClick");
        ReaderPageTextView view = mock(ReaderPageTextView.class, CALLS_REAL_METHODS);
        assertFalse(view.showContextMenu());
        assertFalse(view.showContextMenu(10, 20));
        assertFalse(view.onTextContextMenuItem(android.R.id.copy));
        assertTrue(view.performLongClick());
    }
}
