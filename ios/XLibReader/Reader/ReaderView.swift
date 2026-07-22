import SwiftUI
import UIKit

struct ReaderProgressSelection: Equatable {
    var wholePercent: Int
    var fractionalPercent: Int

    init(progress: Double) {
        let hundredths = min(10_000, max(0, Int((progress * 10_000).rounded())))
        wholePercent = hundredths / 100
        fractionalPercent = hundredths % 100
    }

    var progress: Double {
        let whole = min(100, max(0, wholePercent))
        let fraction = whole == 100 ? 0 : min(99, max(0, fractionalPercent))
        return Double(whole * 100 + fraction) / 10_000
    }
}

enum ReaderTapAction: Equatable {
    case previousPage
    case nextPage
    case toggleMenu
}

enum ReaderPageInteraction {
    static let navigationEdgeWidth: CGFloat = 24

    static func isNavigationEdgeDrag(startX: CGFloat) -> Bool {
        startX <= navigationEdgeWidth
    }

    static func tapAction(x: CGFloat, width: CGFloat) -> ReaderTapAction {
        guard width > 0 else { return .toggleMenu }
        if x < width * 0.30 { return .previousPage }
        if x > width * 0.70 { return .nextPage }
        return .toggleMenu
    }

    static func allowsPageGesture(y: CGFloat, height: CGFloat, menuVisible: Bool) -> Bool {
        guard height > 0, y >= 0, y <= height else { return false }
        return !menuVisible
    }
}

struct ReaderView: View {
    let book: Book
    let store: LibraryStore
    @Bindable var settings: SettingsStore
    @State private var coordinator: ReaderCoordinator
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(ProgressSyncCoordinator.self) private var sync
    @State private var progressPanelVisible = false
    @State private var toastMessage: String?

    init(book: Book, store: LibraryStore, settings: SettingsStore, persistsProgress: Bool = true) {
        self.book = book; self.store = store; self.settings = settings
        _coordinator = State(initialValue: ReaderCoordinator(book: book, store: store, persistsProgress: persistsProgress))
    }

    var body: some View {
        GeometryReader { proxy in
            let contentSize = CGSize(width: max(1, proxy.size.width - 40), height: max(1, proxy.size.height))
            ZStack {
                settings.settings.theme.background.ignoresSafeArea()
                ReaderSoftPageTurnRepresentable(
                    page: coordinator.page,
                    spec: ReaderLayoutSpec(width: contentSize.width, height: contentSize.height, fontSize: settings.settings.fontSize, lineSpacing: settings.settings.lineSpacing, fontName: settings.settings.fontName),
                    backgroundColor: settings.settings.theme.uiBackground,
                    textColor: settings.settings.theme.uiText,
                    direction: coordinator.lastTurnDirection,
                    completedPageTurns: coordinator.completedPageTurns,
                    reduceMotion: reduceMotion,
                    previous: { coordinator.previous() },
                    next: { coordinator.next() },
                    toggleMenu: { coordinator.toggleMenu() }
                )
                .frame(width: contentSize.width, height: contentSize.height)
                .clipped()
                .contentShape(Rectangle())
                .gesture(pageGesture(width: proxy.size.width, height: proxy.size.height))
                .simultaneousGesture(SpatialTapGesture().onEnded { value in
                    if coordinator.menuVisible {
                        coordinator.toggleMenu()
                        return
                    }
                    guard ReaderPageInteraction.allowsPageGesture(
                        y: value.location.y,
                        height: proxy.size.height,
                        menuVisible: coordinator.menuVisible
                    ) else { return }
                    switch ReaderPageInteraction.tapAction(x: value.location.x, width: proxy.size.width) {
                    case .previousPage:
                        coordinator.previous()
                    case .nextPage:
                        coordinator.next()
                    case .toggleMenu:
                        coordinator.toggleMenu()
                    }
                })

                if coordinator.isLoading { ProgressView().controlSize(.large) }
                if coordinator.menuVisible { readerChrome }
                if let toastMessage {
                    Text(toastMessage).font(.system(size: 13, weight: .bold)).foregroundStyle(settings.settings.theme.text)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(settings.settings.theme.surface, in: Capsule()).shadow(radius: 4)
                        .transition(.opacity.combined(with: .scale)).accessibilityAddTraits(.isStaticText)
                }
                if progressPanelVisible {
                    ZStack(alignment: .bottom) {
                        Color.black.opacity(0.34)
                            .ignoresSafeArea()
                            .contentShape(Rectangle())
                            .onTapGesture { progressPanelVisible = false }

                        ReaderProgressSelectionPanel(
                            progress: coordinator.progress,
                            theme: settings.settings.theme
                        ) { progress in
                            coordinator.seek(progress: progress)
                            progressPanelVisible = false
                        }
                    }
                    .zIndex(20)
                }
            }
            .onAppear {
                coordinator.configure(size: contentSize, settings: settings.settings)
                UIApplication.shared.isIdleTimerDisabled = settings.settings.keepScreenAwake
            }
            .onChange(of: contentSize) { _, size in coordinator.configure(size: size, settings: settings.settings) }
            .onChange(of: settings.settings) { _, value in
                coordinator.configure(size: contentSize, settings: value)
                UIApplication.shared.isIdleTimerDisabled = value.keepScreenAwake
            }
        }
        .background {
            XLInteractivePopGestureEnabler()
                .frame(width: 0, height: 0)
        }
        .toolbar(.hidden, for: .navigationBar)
        .preferredColorScheme(settings.settings.theme.colorScheme)
        .task {
            let fileURL = await store.url(for: book)
            await sync.beginReading(book: book, fileURL: fileURL)
        }
        .onChange(of: coordinator.progressEvent) { _, event in
            guard let event else { return }
            sync.recordLocalProgress(bookID: book.id, offset: event.offset, changedAt: event.changedAt)
        }
        .onChange(of: scenePhase) { _, phase in
            flushReaderIfNeeded(for: phase)
        }
        .onDisappear {
            Task {
                await coordinator.flush()
                await sync.endReading(bookID: book.id)
            }
            coordinator.stop()
        }
        .modifier(ReaderAlertsModifier(coordinator: coordinator, sync: sync, dismiss: dismiss))
    }

    private func flushReaderIfNeeded(for phase: ScenePhase) {
        guard phase != .active else { return }
        Task { await coordinator.flush() }
    }

    private var readerChrome: some View {
        let theme = settings.settings.theme
        return VStack(spacing: 0) {
            XLGlassToolbar(theme: theme, cornerRadius: 22, padding: 8) {
                HStack(spacing: 0) {
                    XLIconButton(
                        theme: theme,
                        size: 42,
                        foreground: theme.text,
                        action: { dismiss() }
                    ) {
                        Image(systemName: "chevron.left")
                    }
                    .accessibilityLabel("回到书架")
                    HStack(spacing: 0) {
                        chromeTextButton("A−", theme: theme) { settings.update { $0.fontSize -= 2 } }
                        chromeTextButton("A+", theme: theme) { settings.update { $0.fontSize += 2 } }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    XLNavigationIcon(theme: theme, size: 42, foreground: theme.text,
                                     destination: { SearchView(book: book, store: store, settings: settings) }) { Image(systemName: "magnifyingglass") }
                        .accessibilityLabel("搜索当前书籍")
                    XLIconButton(theme: theme, size: 42, foreground: theme.text, action: {
                        settings.update { $0.theme = $0.theme == .light ? .dark : .light }
                    }) { Image(systemName: theme == .dark ? "sun.max.fill" : "moon.fill") }.accessibilityLabel("切换主题")
                    XLIconButton(theme: theme, size: 42,
                                 foreground: settings.settings.keepScreenAwake ? theme.accent : theme.text,
                                 action: { settings.update { $0.keepScreenAwake.toggle() } }) {
                        Image(systemName: settings.settings.screenLockIconName)
                    }
                    .accessibilityLabel("阅读时锁屏")
                    XLIconButton(theme: theme, size: 42,
                                 foreground: coordinator.autoPaging ? theme.accent : theme.text,
                                 action: { coordinator.toggleAutoPaging(seconds: settings.settings.autoPageSeconds) }) {
                        Image(systemName: coordinator.autoPaging ? "pause.fill" : "play.fill")
                    }.accessibilityLabel("自动翻页")
                    XLNavigationIcon(theme: theme, size: 42, foreground: theme.text,
                                     destination: { SettingsView(store: settings) }) { Image(systemName: "gearshape") }
                        .accessibilityLabel("阅读设置")
                }
            }
            .shadow(color: .black.opacity(theme == .dark ? 0.45 : 0.16), radius: 8, y: 3)
            .padding(.horizontal, 8).padding(.top, 8)
            Spacer()
            XLGlassToolbar(theme: theme, cornerRadius: 24, padding: 10) {
                HStack(spacing: 4) {
                    XLNavigationIcon(theme: theme, size: 44, foreground: theme.text,
                                     destination: {
                        CatalogView(book: book, store: store, settings: settings, currentOffset: { coordinator.offset }, jump: { coordinator.rebuild(at: $0) })
                    }) { Image(systemName: "list.bullet") }.accessibilityLabel("目录")
                    XLIconButton(theme: theme, size: 44, foreground: theme.text, action: { saveBookmark() }) {
                        Image(systemName: "bookmark")
                    }.accessibilityLabel("添加书签")
                    Spacer(minLength: 4)
                    Button {
                        progressPanelVisible = true
                    } label: {
                        ReaderProgressLabel(progress: coordinator.progress, theme: theme)
                            .padding(.horizontal, 8)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("选择阅读进度")
                    .accessibilityValue(Text(coordinator.progress, format: .percent.precision(.fractionLength(2))))
                    .accessibilityIdentifier("reader.progressButton")
                }
            }
            .shadow(color: .black.opacity(theme == .dark ? 0.48 : 0.18), radius: 10, y: 4)
            .padding(.horizontal, 12).padding(.bottom, 8)
        }.tint(theme.accent)
    }

    private func chromeTextButton(_ title: String, theme: AppTheme, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(theme.text)
                .frame(width: 42, height: 42)
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
            .buttonStyle(.borderless)
    }

    private func saveBookmark() {
        let excerpt = coordinator.page?.text.prefix(36).replacingOccurrences(of: "\n", with: " ") ?? book.title
        Task {
            _ = try? await store.addBookmark(bookID: book.id, offset: coordinator.offset, excerpt: String(excerpt))
            withAnimation { toastMessage = "书签已保存" }
            try? await Task.sleep(for: .seconds(1.2))
            withAnimation { toastMessage = nil }
        }
    }

    private func pageGesture(width: CGFloat, height: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 18)
            .onChanged { value in
                guard !ReaderPageInteraction.isNavigationEdgeDrag(startX: value.startLocation.x) else {
                    return
                }
                guard ReaderPageInteraction.allowsPageGesture(
                    y: value.startLocation.y,
                    height: height,
                    menuVisible: coordinator.menuVisible
                ) else { return }
            }
            .onEnded { value in
                guard !ReaderPageInteraction.isNavigationEdgeDrag(startX: value.startLocation.x) else {
                    return
                }
                guard ReaderPageInteraction.allowsPageGesture(
                    y: value.startLocation.y,
                    height: height,
                    menuVisible: coordinator.menuVisible
                ) else {
                    return
                }
                let threshold = width * CGFloat(settings.settings.turnSensitivity)
                if value.translation.width < -threshold { coordinator.next() }
                if value.translation.width > threshold && value.startLocation.x > 24 { coordinator.previous() }
            }
    }
}

private struct ReaderAlertsModifier: ViewModifier {
    @Bindable var coordinator: ReaderCoordinator
    @Bindable var sync: ProgressSyncCoordinator
    let dismiss: DismissAction

    func body(content: Content) -> some View {
        content
            .alert(
                "无法打开书籍",
                isPresented: Binding(
                    get: { coordinator.errorMessage != nil },
                    set: { if !$0 { coordinator.errorMessage = nil } }
                )
            ) {
                Button("返回") { dismiss() }
            } message: {
                Text(coordinator.errorMessage ?? "未知错误")
            }
            .alert(
                "是否跳转到在“\(sync.jumpSuggestion?.remote.device.deviceName ?? "其他设备")”阅读的最新进度？",
                isPresented: jumpAlertIsPresented
            ) {
                Button("暂不跳转") { sync.resolveJump(useRemote: false) }
                Button("跳转") { applyRemoteProgress() }
            } message: {
                Text(sync.jumpSuggestion?.message() ?? "")
            }
    }

    private var jumpAlertIsPresented: Binding<Bool> {
        Binding(
            get: { sync.jumpSuggestion != nil },
            set: { isPresented in
                if !isPresented, sync.jumpSuggestion != nil {
                    sync.resolveJump(useRemote: false)
                }
            }
        )
    }

    private func applyRemoteProgress() {
        guard let accepted = sync.resolveJump(useRemote: true) else { return }
        let date = Date(timeIntervalSince1970: Double(accepted.remote.readAtMs) / 1_000)
        coordinator.applyRemoteProgress(offset: accepted.remote.offset, readAt: date)
    }
}

private struct ReaderProgressLabel: View {
    let progress: Double
    let theme: AppTheme

    var body: some View {
        Text(progress, format: .percent.precision(.fractionLength(2)))
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(theme.text)
    }
}

private struct ReaderProgressSelectionPanel: View {
    let theme: AppTheme
    let onGo: (Double) -> Void
    @State private var selection: ReaderProgressSelection

    init(progress: Double, theme: AppTheme, onGo: @escaping (Double) -> Void) {
        self.theme = theme
        self.onGo = onGo
        _selection = State(initialValue: ReaderProgressSelection(progress: progress))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                progressPicker(
                    title: "百分比整数",
                    selection: $selection.wholePercent,
                    values: Array(0...100),
                    identifier: "reader.progressWholePicker"
                ) { "\($0)" }

                Text(".")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(theme.text)

                progressPicker(
                    title: "百分比小数",
                    selection: $selection.fractionalPercent,
                    values: Array(0...99),
                    identifier: "reader.progressFractionPicker"
                ) { String(format: "%02d", $0) }
                .disabled(selection.wholePercent == 100)
                .opacity(selection.wholePercent == 100 ? 0.45 : 1)

                Text("%")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(theme.secondaryText)
            }
            .padding(.horizontal, 28)
            .frame(height: 250)

            Button("前往") {
                onGo(selection.progress)
            }
            .font(.title3.weight(.semibold))
            .foregroundStyle(theme.accent)
            .frame(maxWidth: .infinity, minHeight: 68)
            .contentShape(Rectangle())
            .buttonStyle(.plain)
            .accessibilityIdentifier("reader.progressGo")
        }
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial, in: UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .background(theme.surface.opacity(0.76), in: UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .contentShape(UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .tint(theme.accent)
        .onChange(of: selection.wholePercent) { _, value in
            if value == 100 { selection.fractionalPercent = 0 }
        }
    }

    private func progressPicker(
        title: String,
        selection: Binding<Int>,
        values: [Int],
        identifier: String,
        label: @escaping (Int) -> String
    ) -> some View {
        Picker(title, selection: selection) {
            ForEach(values, id: \.self) { value in
                Text(label(value)).tag(value)
            }
        }
        .pickerStyle(.wheel)
        .labelsHidden()
        .frame(maxWidth: .infinity, minHeight: 230)
        .clipped()
        .accessibilityIdentifier(identifier)
    }
}
