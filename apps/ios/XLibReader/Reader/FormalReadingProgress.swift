import Foundation

/// Shared by the renderer's persisted progress and the sync snapshot; no UI or storage side effects.
enum FormalReadingProgress {
    static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }
    static func nextMilliseconds(_ date: Date, after previous: Int64) -> Int64 {
        max(milliseconds(date), previous == .max ? .max : previous + 1)
    }
    static func nextDate(_ date: Date, after previous: Date) -> Date {
        max(date, previous.addingTimeInterval(0.001))
    }
    static func apply(to book: inout Book, offset: Int64, date: Date) {
        book.offset = offset
        book.updatedAt = date
    }
}
