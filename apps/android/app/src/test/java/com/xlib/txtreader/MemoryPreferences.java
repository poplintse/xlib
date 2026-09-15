package com.xlib.txtreader;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class MemoryPreferences {
    static SharedPreferences create() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.getString(anyString(), any())).thenAnswer(a -> values.getOrDefault(a.getArgument(0), a.getArgument(1)));
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(a -> values.getOrDefault(a.getArgument(0), a.getArgument(1)));
        when(prefs.contains(anyString())).thenAnswer(a -> values.containsKey(a.getArgument(0)));
        when(editor.putString(anyString(), any())).thenAnswer(a -> { values.put(a.getArgument(0), a.getArgument(1)); return editor; });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(a -> { values.put(a.getArgument(0), a.getArgument(1)); return editor; });
        when(editor.remove(anyString())).thenAnswer(a -> { values.remove(a.getArgument(0)); return editor; });
        return prefs;
    }
}
