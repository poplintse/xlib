import SwiftUI
import UIKit

struct SearchView: View {
    let book: Book
    let store: LibraryStore
    @Bindable var settings: SettingsStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [SearchResult] = []
    @State private var searching = false
    @State private var errorMessage: String?
    private let service = SearchService()
    private var theme: AppTheme { settings.settings.theme }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            XLPageHeader(title: "书内搜索", subtitle: book.title, theme: theme).padding(.bottom, 18)
            XLGlassActionGroup {
                HStack(spacing: 10) {
                    XLIconButton(theme: theme, foreground: theme.text, action: { dismiss() }) { Image(systemName: "chevron.left") }
                        .accessibilityLabel("返回阅读")
                    TextField("搜索当前书籍", text: $query)
                        .font(.system(size: 17)).foregroundStyle(theme.text).submitLabel(.search).onSubmit(runSearch)
                        .padding(.horizontal, 16).frame(height: 52)
                        .xlGlassSurface(theme: theme, cornerRadius: 17, interactive: true)
                    XLIconButton(theme: theme, size: 52, foreground: theme.accent, action: runSearch) {
                        Image(systemName: "magnifyingglass")
                    }.accessibilityLabel("开始搜索")
                }
            }
            Text(statusText).font(.system(size: 14)).foregroundStyle(theme.secondaryText).padding(.vertical, 10)
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(results) { result in
                        NavigationLink { ReaderView(book: temporaryBook(offset: result.offset), store: store, settings: settings, persistsProgress: false) } label: {
                            HighlightedExcerpt(result: result, theme: theme)
                                .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }.buttonStyle(.plain)
                    }
                    if !searching && results.isEmpty && !query.isEmpty {
                        Text("没有找到相关内容").font(.system(size: 15)).foregroundStyle(theme.secondaryText).padding(.top, 48)
                    }
                }.padding(.bottom, 16)
            }
        }
        .padding(.horizontal, 20).padding(.top, 16)
        .background(theme.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("搜索失败", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) { Button("好") {} } message: { Text(errorMessage ?? "未知错误") }
    }

    private var statusText: String {
        if searching { return "正在搜索..." }
        if !results.isEmpty { return "已找到 \(results.count) 条结果" }
        return "输入关键词后，从当前阅读位置开始向后搜索"
    }

    private func runSearch() {
        let value = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...32).contains(value.count) else { errorMessage = "请输入 2–32 个字符"; return }
        searching = true
        Task {
            do {
                let url = await store.url(for: book)
                results = try await service.search(url: url, book: book, query: value, from: book.offset)
            } catch is CancellationError { return } catch { errorMessage = error.localizedDescription }
            searching = false
        }
    }

    private func temporaryBook(offset: Int64) -> Book { var copy = book; copy.offset = offset; return copy }
}

private struct HighlightedExcerpt: View {
    let result: SearchResult
    let theme: AppTheme
    var body: some View {
        Text(attributed).font(.system(size: 17)).foregroundStyle(theme.text).lineSpacing(4).lineLimit(4)
            .frame(maxWidth: .infinity, alignment: .leading).padding(16)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .shadow(color: .black.opacity(theme == .dark ? 0.2 : 0.05), radius: 2, y: 1)
    }
    private var attributed: AttributedString {
        let value = NSMutableAttributedString(string: result.excerpt)
        let lower = min(value.length, max(0, result.highlight.lowerBound))
        let upper = min(value.length, max(lower, result.highlight.upperBound))
        value.addAttribute(.backgroundColor, value: UIColor(theme == .dark ? Color.rgb(126, 92, 0) : Color.rgb(255, 224, 130)), range: NSRange(location: lower, length: upper - lower))
        return AttributedString(value)
    }
}
