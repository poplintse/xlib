# iOS-specific instructions

- Use Swift 6 and the checked-in `XLibReader.xcodeproj`.
- Keep UIKit/SwiftUI code in this application. Move code to `packages/apple-shared` only when it has no platform UI dependency.
- Build number and marketing version changes are release operations; ordinary Xcode builds must not mutate project files.
- Preserve byte-offset reading semantics and test Unicode/encoding boundaries after reader-core changes.
