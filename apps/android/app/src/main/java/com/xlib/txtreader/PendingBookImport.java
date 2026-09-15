package com.xlib.txtreader;

/** Transient import state. Only a successfully prepared Book enters persisted metadata. */
final class PendingBookImport {
    enum State { WAITING, IMPORTING, READY, FAILED, DUPLICATE }
    String title;
    State state = State.WAITING;
    Book book;

    PendingBookImport(String title) { this.title = title; }

    void start(String name) {
        if (name != null && !name.trim().isEmpty()) title = name;
        state = State.IMPORTING;
    }

    void complete(Book result) { book = result; state = result == null ? State.FAILED : State.READY; }
    boolean canOpen() { return state == State.READY && book != null; }
    boolean isPending() { return state == State.WAITING || state == State.IMPORTING; }
    void skipDuplicate() { state = State.DUPLICATE; }

    String statusText() {
        switch (state) {
            case IMPORTING: return "正在导入，暂不可阅读";
            case FAILED: return "导入失败，暂不可阅读";
            case DUPLICATE: return "书籍已存在，已跳过";
            case READY: return "导入完成";
            default: return "等待导入，暂不可阅读";
        }
    }
}
