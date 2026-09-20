package com.xlib.txtreader;

/** Wall clock only; scheduler delays are relative and belong to SyncExecution. */
interface SyncClock { long nowMs(); }
