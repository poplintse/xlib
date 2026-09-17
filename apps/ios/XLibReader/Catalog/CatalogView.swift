import SwiftUI

struct CatalogView: View {
    enum SectionKind: String, CaseIterable, Identifiable { case toc = "目录"; case bookmarks = "书签"; var id: Self { self } }
    let book: Book
    let store: LibraryStore
    @Bindable var settings: SettingsStore
    var readingSessionID: UUID? = nil
    let currentOffset: () -> Int64
    let jump: (Int64) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(ProgressSyncCoordinator.self) private var sync
    @State private var section = SectionKind.toc
    @State private var entries: [TocEntry] = []
    @State private var bookmarks: [Bookmark] = []
    @State private var loading = true
    @State private var prompt: CatalogPrompt?
    @State private var operationMessage: String?
    @State private var confirmsCloudDeletion = false
    @State private var deletingCloudProgress = false
    private let tocService = TocService()
    private var theme: AppTheme { settings.settings.theme }

    var body: some View {
        VStack(spacing: 8) {
            header
            XLSegmentedControl(options: [(SectionKind.toc, "目录"), (.bookmarks, "书签")], selection: $section, theme: theme)
                .padding(.top, 2)
            if let operationMessage {
                Text(operationMessage).font(.footnote).foregroundStyle(theme.text)
                    .accessibilityIdentifier("catalog.operationMessage")
            }
            if sync.isSyncEnabled {
                Button(deletingCloudProgress ? "正在删除…" : "删除本书云端阅读进度", role: .destructive) {
                    confirmsCloudDeletion = true
                }
                .disabled(deletingCloudProgress)
                .accessibilityIdentifier("catalog.deleteCloudProgress")
            }
            ScrollView {
                LazyVStack(spacing: section == .toc ? 2 : 8) {
                    if loading { ProgressView("正在整理…").tint(theme.accent).padding(.top, 48) }
                    if !loading && section == .toc && entries.isEmpty { emptyDirectory }
                    if section == .toc {
                        ForEach(entries) { entry in
                            Button { jump(entry.offset); dismiss() } label: {
                                Text(entry.title).font(.system(size: entry.level == 0 ? 16 : 15, weight: entry.level == 0 ? .bold : .regular))
                                    .foregroundStyle(theme.text).frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.leading, CGFloat(max(0, entry.level - 1) * 18)).padding(.horizontal, 16).padding(.vertical, 12)
                                    .background(entry.level == 0 ? theme.surfaceVariant : theme.surface,
                                                in: RoundedRectangle(cornerRadius: entry.level == 0 ? 14 : 8, style: .continuous))
                                    .contentShape(RoundedRectangle(cornerRadius: entry.level == 0 ? 14 : 8, style: .continuous))
                            }.buttonStyle(.plain).padding(.top, entry.level == 0 ? 8 : 0)
                        }
                    } else if bookmarks.isEmpty && !loading {
                        Text("还没有书签\n在阅读页点击书签按钮即可保存当前位置")
                            .font(.system(size: 15)).foregroundStyle(theme.secondaryText).multilineTextAlignment(.center).lineSpacing(6).padding(.top, 48)
                    } else {
                        ForEach(Array(bookmarks.enumerated()), id: \.element.id) { index, mark in
                            Button { jump(mark.offset); dismiss() } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(spacing: 0) {
                                        Text("\(index + 1): ")
                                            .font(.system(size: 16, weight: .bold))
                                        Text("\(book.fileSize > 0 ? Double(mark.offset) / Double(book.fileSize) * 100 : 0, specifier: "%.2f")% · \(mark.createdAt.formatted(date: .abbreviated, time: .shortened))")
                                            .font(.system(size: 13, weight: .bold))
                                    }
                                    .foregroundStyle(theme.text)
                                    if !mark.excerpt.isEmpty { Text(mark.excerpt).font(.system(size: 13)).foregroundStyle(theme.secondaryText).lineLimit(2) }
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
                                    .background(theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }.buttonStyle(.plain)
                                .accessibilityIdentifier("catalog.bookmark.\(mark.offset)")
                                .contextMenu { Button("删除书签", role: .destructive) { Task { try? await store.removeBookmark(id: mark.id); await load() } } }
                        }
                    }
                }.padding(.bottom, 16)
            }
        }
        .padding(.horizontal, 12).padding(.top, 10)
        .background(theme.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .confirmationDialog("删除《\(book.title)》的云端阅读进度？", isPresented: $confirmsCloudDeletion, titleVisibility: .visible) {
            Button("删除本书云端进度", role: .destructive) { deleteCloudProgress() }
            Button("取消", role: .cancel) { }
        } message: {
            Text("仅删除本书的云端进度，保留本地书籍、书签和阅读位置。本次阅读暂停上传，退出本书并重新打开后恢复。")
        }
        .alert(
            prompt?.title ?? "目录尚未生成",
            isPresented: Binding(get: { prompt != nil }, set: { if !$0 { prompt = nil } })
        ) {
            Button("是") { startBackgroundGeneration() }
            Button("否", role: .cancel) { prompt = nil }
        } message: {
            Text(prompt?.message ?? "当前书籍还没有可用目录。")
        }
    }

    private var header: some View {
        XLGlassToolbar(theme: theme, cornerRadius: 20, padding: 4) {
            HStack(spacing: 12) {
                XLIconButton(theme: theme, size: 44, foreground: theme.text, action: { dismiss() }) {
                    Image(systemName: "chevron.left")
                }.accessibilityLabel("返回阅读")
                Text(book.title).font(.system(size: 22, weight: .bold)).foregroundStyle(theme.text).lineLimit(1)
                Spacer()
                XLIconButton(theme: theme, size: 44, foreground: theme.text, action: { addBookmark() }) {
                    Image(systemName: "bookmark.badge.plus")
                }.accessibilityLabel("添加当前书签")
            }
        }.frame(height: 56)
    }

    private var emptyDirectory: some View {
        VStack(spacing: 14) {
            Text("目录不存在，请手工生成目录")
                .font(.system(size: 15)).foregroundStyle(theme.secondaryText)
            Button {
                prompt = .missing
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "list.bullet.rectangle")
                    Text("生成目录").font(.system(size: 15, weight: .bold))
                }
                .foregroundStyle(theme.accent)
                .frame(maxWidth: .infinity, minHeight: 48)
                .xlGlassSurface(theme: theme, cornerRadius: 16, tint: theme.accentContainer.opacity(0.48), interactive: true)
                .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .frame(maxWidth: 220)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }

    private func addBookmark() {
        Task {
            do {
                _ = try await store.addBookmark(bookID: book.id, offset: currentOffset(), excerpt: book.title)
                operationMessage = "书签已保存"
            } catch { operationMessage = error.localizedDescription }
            section = .bookmarks
            await load()
        }
    }

    private func deleteCloudProgress() {
        deletingCloudProgress = true
        Task {
            let url = await store.url(for: book)
            let deleted = await sync.deleteCloudProgress(book: book, fileURL: url, readingSessionID: readingSessionID)
            operationMessage = deleted ? "本书云端进度已删除，本次阅读暂停上传。" : (sync.lastFailureMessage ?? "删除失败，请重试。")
            deletingCloudProgress = false
        }
    }

    private func load() async {
        loading = true
        do {
            bookmarks = try await store.bookmarks(for: book.id)
            if let cached = try await store.cachedTOC(for: book) {
                entries = cached
            } else if section == .toc {
                prompt = .missing
            }
        } catch is CancellationError {
            return
        } catch {
            prompt = .unavailable
        }
        loading = false
    }

    private func startBackgroundGeneration() {
        prompt = nil
        Task {
            do {
                let url = await store.url(for: book)
                let generatedEntries = try await tocService.build(url: url, book: book)
                try await store.saveTOC(generatedEntries, for: book)
            } catch is CancellationError {
                return
            } catch {
                // The catalog can be retried from the reader or book management screen.
            }
        }
        dismiss()
    }

}

private enum CatalogPrompt {
    case missing
    case unavailable

    var title: String {
        switch self {
        case .missing: "是否在后台生成目录"
        case .unavailable: "目录暂不可用"
        }
    }

    var message: String {
        switch self {
        case .missing:
            "当前书籍还没有目录，是否在后台自动生成目录，完成后再次打开可以使用目录。"
        case .unavailable:
            "当前目录无法读取。可以在后台重新生成，完成后再次打开目录。"
        }
    }
}
