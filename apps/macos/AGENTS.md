# macOS-specific instructions

- Status: planned. There is no macOS application target yet.
- New work must consume `packages/apple-shared` rather than copy iOS reader-core code.
- Do not report macOS as buildable or releasable until a real target and tests exist.
- Prefer SwiftUI and narrow AppKit interop when implementation begins.
