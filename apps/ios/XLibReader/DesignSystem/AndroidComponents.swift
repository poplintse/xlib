import SwiftUI
import UIKit

/// Restores the native interactive pop gesture for screens that hide the
/// system navigation bar in favor of custom chrome.
struct XLInteractivePopGestureEnabler: UIViewControllerRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> ResolverViewController {
        let controller = ResolverViewController()
        let coordinator = context.coordinator
        controller.onDidAppear = { navigationController in
            coordinator.install(on: navigationController)
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: ResolverViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: ResolverViewController, coordinator: Coordinator) {
        coordinator.uninstall()
    }

    final class ResolverViewController: UIViewController {
        var onDidAppear: ((UINavigationController?) -> Void)?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            onDidAppear?(navigationController)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var navigationController: UINavigationController?
        private weak var gestureRecognizer: UIGestureRecognizer?
        private weak var originalDelegate: UIGestureRecognizerDelegate?
        private var originalEnabled = false

        func install(on navigationController: UINavigationController?) {
            guard let navigationController,
                  let gestureRecognizer = navigationController.interactivePopGestureRecognizer else { return }
            if self.gestureRecognizer !== gestureRecognizer {
                uninstall()
                self.navigationController = navigationController
                self.gestureRecognizer = gestureRecognizer
                originalDelegate = gestureRecognizer.delegate
                originalEnabled = gestureRecognizer.isEnabled
            }
            gestureRecognizer.delegate = self
            gestureRecognizer.isEnabled = navigationController.viewControllers.count > 1
        }

        func uninstall() {
            if gestureRecognizer?.delegate === self {
                gestureRecognizer?.delegate = originalDelegate
                gestureRecognizer?.isEnabled = originalEnabled
            }
            navigationController = nil
            gestureRecognizer = nil
            originalDelegate = nil
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }
    }
}

struct XLIconButton<Label: View>: View {
    let theme: AppTheme
    var size: CGFloat = 48
    var foreground: Color?
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .font(.system(size: size * 0.43, weight: .semibold))
                .frame(width: size, height: size)
                .contentShape(Rectangle())
        }
        .foregroundStyle(foreground ?? theme.accent)
        .buttonStyle(.borderless)
    }
}

struct XLNavigationIcon<Destination: View, Label: View>: View {
    let theme: AppTheme
    var size: CGFloat = 48
    var foreground: Color?
    @ViewBuilder let destination: () -> Destination
    @ViewBuilder let label: () -> Label

    var body: some View {
        NavigationLink(destination: destination) {
            label()
                .font(.system(size: size * 0.43, weight: .semibold))
                .frame(width: size, height: size)
                .contentShape(Rectangle())
        }
        .foregroundStyle(foreground ?? theme.accent)
        .buttonStyle(.borderless)
    }
}

struct XLGlassToolbar<Content: View>: View {
    let theme: AppTheme
    var cornerRadius: CGFloat = 22
    var padding: CGFloat = 8
    @ViewBuilder let content: () -> Content

    @ViewBuilder var body: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 6) {
                content().padding(padding)
                    .glassEffect(.regular.tint(theme.surface.opacity(0.30)), in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            content().padding(padding)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .background(theme.surface.opacity(0.42), in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}

struct XLGlassActionGroup<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @ViewBuilder var body: some View {
        if #available(iOS 26, *) { GlassEffectContainer(spacing: 6) { content() } } else { content() }
    }
}

extension View {
    @ViewBuilder
    func xlGlassSurface(theme: AppTheme, cornerRadius: CGFloat, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            if interactive {
                glassEffect(.regular.tint(tint ?? theme.surface.opacity(0.30)).interactive(), in: .rect(cornerRadius: cornerRadius))
            } else {
                glassEffect(.regular.tint(tint ?? theme.surface.opacity(0.30)), in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .background((tint ?? theme.surface.opacity(0.42)), in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}

struct XLPageHeader: View {
    let title: String
    let subtitle: String
    let theme: AppTheme
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 30, weight: .bold)).foregroundStyle(theme.text)
            Text(subtitle).font(.system(size: 13)).foregroundStyle(theme.secondaryText).lineLimit(1)
        }
    }
}

struct XLPrimaryButtonStyle: ButtonStyle {
    let theme: AppTheme
    var destructive = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(destructive ? theme.danger : (theme == .dark ? AppTheme.dark.background : .white))
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(destructive ? theme.dangerContainer : theme.accent, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .opacity(configuration.isPressed ? 0.78 : 1)
    }
}

struct XLSegmentedControl<Option: Hashable>: View {
    let options: [(Option, String)]
    @Binding var selection: Option
    let theme: AppTheme
    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.0) { option, label in
                Button { selection = option } label: {
                    Text(label)
                        .font(.system(size: 13, weight: selection == option ? .bold : .medium))
                        .foregroundStyle(selection == option ? theme.accent : theme.text)
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background(selection == option ? theme.accentContainer : .clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                    .buttonStyle(.plain)
            }
        }
        .padding(4)
        .xlGlassSurface(theme: theme, cornerRadius: 18, tint: theme.surface.opacity(0.34))
    }
}

struct XLSettingCard<Control: View>: View {
    let title: String
    let detail: String
    let theme: AppTheme
    var controlWidth: CGFloat? = nil
    @ViewBuilder let control: () -> Control
    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 14, weight: .bold)).foregroundStyle(theme.text)
                Text(detail).font(.system(size: 10)).foregroundStyle(theme.secondaryText).lineLimit(3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            controlSurface
        }
        .padding(10)
        .xlGlassSurface(theme: theme, cornerRadius: 20)
        .shadow(color: .black.opacity(theme == .dark ? 0.24 : 0.06), radius: 2, y: 1)
    }

    @ViewBuilder private var controlSurface: some View {
        if let controlWidth {
            control()
                .frame(width: controlWidth)
                .padding(4)
                .xlGlassSurface(theme: theme, cornerRadius: 14, tint: theme.surfaceVariant.opacity(0.38), interactive: true)
                .frame(maxWidth: .infinity, alignment: .trailing)
        } else {
            control()
                .frame(maxWidth: .infinity)
                .padding(4)
                .xlGlassSurface(theme: theme, cornerRadius: 14, tint: theme.surfaceVariant.opacity(0.38), interactive: true)
        }
    }
}
