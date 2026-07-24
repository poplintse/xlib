import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Bindable var model: LibraryModel
    let store: LibraryStore
    @Bindable var settings: SettingsStore
    @State private var importing = false
    @State private var selection = Set<UUID>()
    @State private var managing = false
    @State private var editingBook: Book?
    @State private var path: [Book] = []
    @State private var confirmation: ManagementConfirmation?
    @State private var directoryManagementBook: Book?

    private var theme: AppTheme { settings.settings.theme }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                header.padding(.horizontal, 20).frame(height: 86)
                content
                if managing { managementBar.padding(.horizontal, 20).padding(.bottom, 6) }
                Text(appVersion).font(.system(size: 12)).foregroundStyle(theme.secondaryText)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).frame(height: 28)
            }
            .background(theme.background.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Book.self) { ReaderView(book: $0, store: store, settings: settings) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText], allowsMultipleSelection: false) { result in
                if case let .success(urls) = result, let url = urls.first { Task { await model.importBook(from: url) } }
                if case let .failure(error) = result { model.errorMessage = error.localizedDescription }
            }
            .sheet(item: $editingBook) { book in BookEditor(book: book, theme: theme) { updated in Task { await model.update(updated) } } }
            .confirmationDialog(
                confirmation?.title ?? "确认操作",
                isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }),
                titleVisibility: .visible
            ) {
                if let confirmation {
                    Button(confirmation.confirmLabel, role: .destructive) { perform(confirmation) }
                    Button("取消", role: .cancel) { self.confirmation = nil }
                }
            } message: {
                Text(confirmation?.message ?? "")
            }
            .confirmationDialog(
                "目录管理",
                isPresented: Binding(get: { directoryManagementBook != nil }, set: { if !$0 { directoryManagementBook = nil } }),
                titleVisibility: .visible
            ) {
                if let book = directoryManagementBook {
                    if model.booksWithTOC.contains(book.id) {
                        Button("重新生成目录") { regenerateTOC(for: book) }
                        Button("删除目录", role: .destructive) { deleteTOC(for: book) }
                    } else {
                        Button("生成目录") { generateTOC(for: book) }
                    }
                    Button("取消", role: .cancel) { directoryManagementBook = nil }
                }
            } message: {
                if let book = directoryManagementBook {
                    Text(model.booksWithTOC.contains(book.id) ? "当前书籍已有目录，可删除或重新生成。" : "当前书籍尚未生成目录。")
                }
            }
            .alert("操作未完成", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("好") { model.errorMessage = nil }
            } message: { Text(model.errorMessage ?? "未知错误") }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            XLPageHeader(title: "我的书架", subtitle: model.books.isEmpty ? "安静地读一本好书" : "\(model.books.count) 本本地书籍", theme: theme)
                .frame(maxWidth: .infinity, alignment: .leading)
            XLGlassActionGroup {
                HStack(spacing: 8) {
                    XLIconButton(theme: theme, action: { importing = true }) { Image(systemName: "plus") }.accessibilityLabel("添加 TXT")
                    if !model.books.isEmpty {
                        XLIconButton(theme: theme, action: { managing.toggle(); selection.removeAll() }) {
                            Image(systemName: managing ? "checkmark" : "books.vertical")
                        }.accessibilityLabel(managing ? "完成管理" : "管理书籍")
                    }
                    XLNavigationIcon(theme: theme, destination: { SettingsView(store: settings) }) { Image(systemName: "gearshape") }
                        .accessibilityLabel("常规设置")
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        if model.isLoading && model.books.isEmpty {
            Spacer(); ProgressView("正在整理书架…").tint(theme.accent); Spacer()
        } else if model.books.isEmpty {
            EmptyLibraryCard(theme: theme) { importing = true }
                .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 16)
        } else {
            List {
                ForEach(model.books) { book in
                    bookListRow(book)
                        .listRowInsets(.init(top: 6, leading: 8, bottom: 6, trailing: 12))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) { confirmation = .deleteBooks([book.id]) } label: { Label("删除", systemImage: "trash") }
                            Button { directoryManagementBook = book } label: { Label("目录管理", systemImage: "list.bullet.rectangle") }
                                .tint(theme.accent)
                                .disabled(model.tocBusyBookIDs.contains(book.id))
                            Button { editingBook = book } label: { Label("编辑", systemImage: "ellipsis") }.tint(Color.rgb(112, 130, 149))
                        }
                }
            }
            .listStyle(.plain).scrollContentBackground(.hidden)
        }
    }

    @ViewBuilder private func bookListRow(_ book: Book) -> some View {
        if managing {
            Button { toggleSelection(for: book.id) } label: {
                BookRow(book: book, theme: theme, selected: selection.contains(book.id))
            }
            .buttonStyle(.plain)
        } else {
            Button { path.append(book) } label: {
                BookRow(book: book, theme: theme, selected: nil)
            }
            .buttonStyle(.plain)
        }
    }

    private var managementBar: some View {
        XLGlassToolbar(theme: theme, cornerRadius: 22, padding: 6) {
            Button {
                confirmation = .deleteBooks(selection)
            } label: {
                ManagementActionLabel(
                    title: selection.isEmpty ? "删除书籍" : "删除书籍 \(selection.count)",
                    systemImage: "trash",
                    theme: theme,
                    destructive: true
                )
            }
            .buttonStyle(.plain)
            .disabled(selection.isEmpty || selectionIsBusy)
            .opacity(selection.isEmpty || selectionIsBusy ? 0.5 : 1)
        }
    }

    private var selectionIsBusy: Bool { !selection.isDisjoint(with: model.tocBusyBookIDs) }

    private func toggleSelection(for id: UUID) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    private func generateTOC(for book: Book) {
        directoryManagementBook = nil
        Task { await model.generateTOC(ids: [book.id]) }
    }

    private func regenerateTOC(for book: Book) {
        directoryManagementBook = nil
        Task { await model.regenerateTOC(ids: [book.id]) }
    }

    private func deleteTOC(for book: Book) {
        directoryManagementBook = nil
        Task { await model.deleteTOC(ids: [book.id]) }
    }

    private func perform(_ confirmation: ManagementConfirmation) {
        self.confirmation = nil
        switch confirmation {
        case .deleteBooks(let ids):
            Task {
                await model.delete(ids: ids)
                selection.subtract(ids)
                if selection.isEmpty { managing = false }
            }
        }
    }

    private var appVersion: String {
        AppVersion.displayText
    }
}

private enum ManagementConfirmation {
    case deleteBooks(Set<UUID>)

    var title: String {
        switch self {
        case .deleteBooks: "删除所选书籍？"
        }
    }

    var confirmLabel: String {
        switch self {
        case .deleteBooks: "删除书籍"
        }
    }

    var message: String {
        switch self {
        case .deleteBooks(let ids):
            "将删除 \(ids.count) 本书及其本地 TXT 文件、目录和书签，此操作无法撤销。"
        }
    }
}

private struct ManagementActionLabel: View {
    let title: String
    let systemImage: String
    let theme: AppTheme
    var destructive = false

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: systemImage).font(.system(size: 15, weight: .semibold))
            Text(title).font(.system(size: 14, weight: .bold)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .foregroundStyle(destructive ? theme.danger : theme.accent)
        .frame(maxWidth: .infinity, minHeight: 48)
        .padding(.horizontal, 10)
        .xlGlassSurface(
            theme: theme,
            cornerRadius: 16,
            tint: destructive ? theme.dangerContainer.opacity(0.52) : theme.accentContainer.opacity(0.48),
            interactive: true
        )
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct EmptyLibraryCard: View {
    let theme: AppTheme
    let importAction: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            Text("TXT").font(.system(size: 18, weight: .bold)).foregroundStyle(theme.accent)
                .frame(width: 72, height: 72).background(theme.accentContainer, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            Text("书架还是空的").font(.system(size: 21, weight: .bold)).foregroundStyle(theme.text).padding(.top, 24)
            Text("从“文件”App 选择 TXT，书籍会安全地保存在本机。")
                .font(.system(size: 15)).foregroundStyle(theme.secondaryText).multilineTextAlignment(.center).padding(.top, 10).padding(.bottom, 24)
            Button("导入第一本书", action: importAction).buttonStyle(XLPrimaryButtonStyle(theme: theme)).frame(width: 168)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(36)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: .black.opacity(theme == .dark ? 0.25 : 0.07), radius: 2, y: 1)
    }
}

private struct BookRow: View {
    let book: Book
    let theme: AppTheme
    let selected: Bool?
    var body: some View {
        HStack(spacing: 12) {
            if let selected {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title2).foregroundStyle(selected ? theme.accent : theme.secondaryText).frame(width: 30)
            }
            Text("TXT").font(.system(size: 13, weight: .bold)).foregroundStyle(theme.accent)
                .frame(width: 58, height: 82).background(theme.accentContainer, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(book.title).font(.system(size: 18, weight: .bold)).foregroundStyle(theme.text).lineLimit(1)
                Text(book.displayAuthor).font(.system(size: 13)).foregroundStyle(theme.secondaryText).lineLimit(1).padding(.top, 5)
                Text("\(relativeTime) · \(book.progress * 100, specifier: "%.2f")%")
                    .font(.system(size: 13)).foregroundStyle(theme.secondaryText).lineLimit(1).padding(.top, 7)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.leading, 8).padding(.trailing, 14).padding(.vertical, 13)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(theme == .dark ? 0.24 : 0.07), radius: 2, y: 1)
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var relativeTime: String {
        let seconds = max(0, Date().timeIntervalSince(book.updatedAt))
        if seconds < 3_600 { return "刚刚" }
        if seconds < 86_400 { return "\(Int(seconds / 3_600)) 小时前" }
        if seconds < 604_800 { return "\(Int(seconds / 86_400)) 天前" }
        return book.updatedAt.formatted(date: .abbreviated, time: .omitted)
    }
}

private struct BookEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State var book: Book
    let theme: AppTheme
    let save: (Book) -> Void
    var body: some View {
        NavigationStack {
            Form { TextField("书名", text: $book.title); TextField("作者", text: $book.author) }
                .scrollContentBackground(.hidden).background(theme.background)
                .navigationTitle("书籍信息").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("保存") { book.title = book.title.trimmingCharacters(in: .whitespacesAndNewlines); save(book); dismiss() }
                            .disabled(book.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
        }.presentationDetents([.medium]).tint(theme.accent)
    }
}
