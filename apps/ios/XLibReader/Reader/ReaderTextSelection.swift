import Foundation

/// UTF-16 ranges match Core Text's string indices; they are unrelated to saved byte offsets.
enum ReaderTextSelection {
    static func word(in text: String, at offset: Int) -> NSRange? {
        let value = text as NSString
        guard value.length > 0, offset >= 0, offset < value.length else { return nil }
        var result: NSRange?
        value.enumerateSubstrings(in: NSRange(location: 0, length: value.length), options: .byWords) {
            _, range, _, stop in
            if NSLocationInRange(offset, range) {
                result = range
                stop.pointee = true
            }
        }
        if let result { return result }
        let character = value.rangeOfComposedCharacterSequence(at: offset)
        let scalar = value.substring(with: character)
        return scalar.rangeOfCharacter(from: .whitespacesAndNewlines) == nil ? character : nil
    }

    static func draggedRange(in text: String, initial: NSRange, to offset: Int) -> NSRange {
        let value = text as NSString
        guard value.length > 0, initial.location >= 0,
              NSMaxRange(initial) <= value.length else { return initial }
        let position = min(max(0, offset), value.length)
        if position < initial.location {
            let start = value.rangeOfComposedCharacterSequence(at: position).location
            return NSRange(location: start, length: NSMaxRange(initial) - start)
        }
        if position >= NSMaxRange(initial), position < value.length {
            let end = NSMaxRange(value.rangeOfComposedCharacterSequence(at: position))
            return NSRange(location: initial.location, length: end - initial.location)
        }
        if position == value.length {
            return NSRange(location: initial.location, length: value.length - initial.location)
        }
        return initial
    }

    static func movedHandle(in text: String, selection: NSRange, start: Bool, to offset: Int) -> NSRange {
        let value = text as NSString
        guard value.length > 0, selection.length > 0,
              selection.location >= 0, NSMaxRange(selection) <= value.length else { return selection }
        let position = min(max(0, offset), value.length)
        if start {
            let character = value.rangeOfComposedCharacterSequence(at: min(position, value.length - 1))
            guard character.location < NSMaxRange(selection) else { return selection }
            return NSRange(location: character.location, length: NSMaxRange(selection) - character.location)
        }
        let upper = position == value.length ? value.length
            : NSMaxRange(value.rangeOfComposedCharacterSequence(at: position))
        guard upper > selection.location else { return selection }
        return NSRange(location: selection.location, length: upper - selection.location)
    }
}
