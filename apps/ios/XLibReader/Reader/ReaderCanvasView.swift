import CoreText
import SwiftUI
import UIKit

@MainActor
final class ReaderCanvasView: UIView {
    var page: ReaderPageDescriptor? {
        didSet {
            guard page != oldValue else { return }
            accessibilityValue = page?.text
            setNeedsDisplay()
        }
    }
    var layoutSpec = ReaderLayoutSpec(width: 1, height: 1) {
        didSet { if layoutSpec != oldValue { setNeedsDisplay() } }
    }
    var textColor = UIColor.label {
        didSet { if textColor != oldValue { setNeedsDisplay() } }
    }
    var onPreviousPage: (() -> Void)?
    var onNextPage: (() -> Void)?
    var onToggleMenu: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        isAccessibilityElement = true
        accessibilityLabel = "阅读正文"
        accessibilityTraits = .staticText
        accessibilityCustomActions = [
            UIAccessibilityCustomAction(name: "上一页") { [weak self] _ in
                self?.onPreviousPage?()
                return self != nil
            },
            UIAccessibilityCustomAction(name: "下一页") { [weak self] _ in
                self?.onNextPage?()
                return self != nil
            },
            UIAccessibilityCustomAction(name: "显示阅读菜单") { [weak self] _ in
                self?.onToggleMenu?()
                return self != nil
            },
        ]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func draw(_ rect: CGRect) {
        guard let page, let context = UIGraphicsGetCurrentContext() else { return }
        context.saveGState()
        context.textMatrix = .identity
        context.translateBy(x: 0, y: bounds.height)
        context.scaleBy(x: 1, y: -1)

        let attributed = NSMutableAttributedString(
            attributedString: ReaderLayoutService.makeAttributedString(text: page.text, spec: layoutSpec)
        )
        attributed.addAttribute(
            NSAttributedString.Key(kCTForegroundColorAttributeName as String),
            value: textColor.cgColor,
            range: NSRange(location: 0, length: attributed.length)
        )
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let path = CGPath(rect: bounds, transform: nil)
        let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: 0), path, nil)
        CTFrameDraw(frame, context)
        context.restoreGState()
    }
}

@MainActor
final class ReaderPageContentViewController: UIViewController {
    let pageID: Int64
    private let canvas = ReaderCanvasView()

    init(
        page: ReaderPageDescriptor,
        spec: ReaderLayoutSpec,
        backgroundColor: UIColor,
        textColor: UIColor,
        previous: @escaping () -> Void,
        next: @escaping () -> Void,
        toggleMenu: @escaping () -> Void
    ) {
        pageID = page.id
        super.init(nibName: nil, bundle: nil)
        configure(
            page: page,
            spec: spec,
            backgroundColor: backgroundColor,
            textColor: textColor,
            previous: previous,
            next: next,
            toggleMenu: toggleMenu
        )
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        view = canvas
    }

    func configure(
        page: ReaderPageDescriptor,
        spec: ReaderLayoutSpec,
        backgroundColor: UIColor,
        textColor: UIColor,
        previous: @escaping () -> Void,
        next: @escaping () -> Void,
        toggleMenu: @escaping () -> Void
    ) {
        canvas.page = page
        canvas.layoutSpec = spec
        canvas.backgroundColor = backgroundColor
        canvas.textColor = textColor
        canvas.onPreviousPage = previous
        canvas.onNextPage = next
        canvas.onToggleMenu = toggleMenu
    }
}

enum ReaderSoftPageTurnStyle {
    static let duration: TimeInterval = 0.40
    static let perspectiveDistance: CGFloat = 900
    static let maximumAngle: CGFloat = .pi * 0.48

    static func anchorPoint(for direction: ReaderDirection) -> CGPoint {
        direction == .forward
            ? CGPoint(x: 0, y: 0.5)
            : CGPoint(x: 1, y: 0.5)
    }

    static func foldedAngle(for direction: ReaderDirection) -> CGFloat {
        direction == .forward ? -maximumAngle : maximumAngle
    }
}

@MainActor
final class ReaderPageFoldShadowView: UIView {
    override class var layerClass: AnyClass { CAGradientLayer.self }

    init(direction: ReaderDirection) {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true

        let gradient = layer as? CAGradientLayer
        gradient?.startPoint = CGPoint(x: 0, y: 0.5)
        gradient?.endPoint = CGPoint(x: 1, y: 0.5)
        if direction == .forward {
            gradient?.colors = [
                UIColor.clear.cgColor,
                UIColor.black.withAlphaComponent(0.03).cgColor,
                UIColor.black.withAlphaComponent(0.18).cgColor,
            ]
            gradient?.locations = [0, 0.72, 1]
        } else {
            gradient?.colors = [
                UIColor.black.withAlphaComponent(0.16).cgColor,
                UIColor.black.withAlphaComponent(0.03).cgColor,
                UIColor.clear.cgColor,
            ]
            gradient?.locations = [0, 0.28, 1]
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

@MainActor
final class ReaderSoftPageTurnController: UIViewController {
    private var currentPageController: ReaderPageContentViewController?
    private var animator: UIViewPropertyAnimator?
    private weak var animatingLeaf: UIView?

    var currentPageID: Int64? { currentPageController?.pageID }
    private(set) var activeTurnDirection: ReaderDirection?
    var activeTurnAnchorPoint: CGPoint? { animatingLeaf?.layer.anchorPoint }

    override func loadView() {
        let root = UIView()
        root.clipsToBounds = true
        view = root
    }

    func configureCurrent(
        page: ReaderPageDescriptor,
        spec: ReaderLayoutSpec,
        backgroundColor: UIColor,
        textColor: UIColor,
        previous: @escaping () -> Void,
        next: @escaping () -> Void,
        toggleMenu: @escaping () -> Void
    ) {
        currentPageController?.configure(
            page: page,
            spec: spec,
            backgroundColor: backgroundColor,
            textColor: textColor,
            previous: previous,
            next: next,
            toggleMenu: toggleMenu
        )
    }

    func present(
        _ target: ReaderPageContentViewController,
        direction: ReaderDirection,
        animated: Bool,
        completion: @escaping () -> Void
    ) {
        guard animated, let currentPageController else {
            replaceCurrent(with: target)
            completion()
            return
        }

        view.layoutIfNeeded()
        target.view.frame = view.bounds
        target.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        target.view.accessibilityElementsHidden = true
        addChild(target)

        // Keep the destination page underneath and animate the current page
        // away in both directions, matching a real page turn.
        view.insertSubview(target.view, belowSubview: currentPageController.view)
        let leaf: UIView = currentPageController.view
        target.didMove(toParent: self)

        let foldShadow = ReaderPageFoldShadowView(direction: direction)
        foldShadow.frame = leaf.bounds
        foldShadow.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        leaf.addSubview(foldShadow)

        prepareLeaf(leaf, direction: direction)
        animatingLeaf = leaf
        activeTurnDirection = direction
        let foldedTransform = makeFoldedTransform(direction: direction)
        foldShadow.alpha = 0.2

        let timing = UICubicTimingParameters(
            controlPoint1: CGPoint(x: 0.22, y: 0),
            controlPoint2: CGPoint(x: 0.30, y: 1)
        )
        let animator = UIViewPropertyAnimator(
            duration: ReaderSoftPageTurnStyle.duration,
            timingParameters: timing
        )
        self.animator = animator
        animator.addAnimations {
            leaf.layer.transform = foldedTransform
            foldShadow.alpha = 1
        }
        animator.addCompletion { [weak self, weak target, weak currentPageController] _ in
            guard let self, let target, let currentPageController else { return }
            foldShadow.removeFromSuperview()
            self.normalize(target.view)
            currentPageController.willMove(toParent: nil)
            currentPageController.view.removeFromSuperview()
            currentPageController.removeFromParent()
            target.view.accessibilityElementsHidden = false
            self.currentPageController = target
            self.animator = nil
            self.animatingLeaf = nil
            self.activeTurnDirection = nil
            completion()
        }
        animator.startAnimation()
    }

    private func replaceCurrent(with target: ReaderPageContentViewController) {
        animator?.stopAnimation(true)
        animator = nil
        animatingLeaf = nil
        activeTurnDirection = nil
        currentPageController?.willMove(toParent: nil)
        currentPageController?.view.removeFromSuperview()
        currentPageController?.removeFromParent()

        addChild(target)
        target.view.frame = view.bounds
        target.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(target.view)
        target.didMove(toParent: self)
        target.view.accessibilityElementsHidden = false
        currentPageController = target
    }

    private func prepareLeaf(_ leaf: UIView, direction: ReaderDirection) {
        normalize(leaf)
        leaf.layer.anchorPoint = ReaderSoftPageTurnStyle.anchorPoint(for: direction)
        leaf.frame = view.bounds
        leaf.layer.shadowColor = UIColor.black.cgColor
        leaf.layer.shadowOpacity = direction == .forward ? 0.22 : 0.16
        leaf.layer.shadowRadius = 10
        leaf.layer.shadowOffset = CGSize(width: direction == .forward ? 7 : -5, height: 0)
        leaf.layer.shadowPath = UIBezierPath(rect: leaf.bounds).cgPath
    }

    private func normalize(_ pageView: UIView) {
        pageView.layer.transform = CATransform3DIdentity
        pageView.layer.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        pageView.layer.shadowOpacity = 0
        pageView.layer.shadowRadius = 0
        pageView.layer.shadowOffset = .zero
        pageView.layer.shadowPath = nil
        pageView.frame = view.bounds
    }

    private func makeFoldedTransform(direction: ReaderDirection) -> CATransform3D {
        var perspective = CATransform3DIdentity
        perspective.m34 = -1 / ReaderSoftPageTurnStyle.perspectiveDistance
        let angle = ReaderSoftPageTurnStyle.foldedAngle(for: direction)
        return CATransform3DRotate(perspective, angle, 0, 1, 0)
    }
}

struct ReaderSoftPageTurnRepresentable: UIViewControllerRepresentable {
    let page: ReaderPageDescriptor?
    let spec: ReaderLayoutSpec
    let backgroundColor: UIColor
    let textColor: UIColor
    let direction: ReaderDirection
    let completedPageTurns: Int
    let reduceMotion: Bool
    let previous: () -> Void
    let next: () -> Void
    let toggleMenu: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIViewController(context: Context) -> ReaderSoftPageTurnController {
        Self.makePageTurnController(backgroundColor: backgroundColor)
    }

    static func makePageTurnController(backgroundColor: UIColor) -> ReaderSoftPageTurnController {
        let controller = ReaderSoftPageTurnController()
        controller.view.backgroundColor = backgroundColor
        return controller
    }

    func updateUIViewController(_ controller: ReaderSoftPageTurnController, context: Context) {
        controller.view.backgroundColor = backgroundColor
        context.coordinator.update(
            controller: controller,
            page: page,
            spec: spec,
            backgroundColor: backgroundColor,
            textColor: textColor,
            direction: direction,
            completedPageTurns: completedPageTurns,
            reduceMotion: reduceMotion,
            previous: previous,
            next: next,
            toggleMenu: toggleMenu
        )
    }

    @MainActor
    final class Coordinator {
        private struct Presentation {
            let page: ReaderPageDescriptor
            let spec: ReaderLayoutSpec
            let backgroundColor: UIColor
            let textColor: UIColor
            let direction: ReaderDirection
            let animated: Bool
            let previous: () -> Void
            let next: () -> Void
            let toggleMenu: () -> Void
        }

        private var lastCompletedPageTurns: Int?
        private(set) var isAnimating = false
        private var pendingPresentation: Presentation?

        func update(
            controller: ReaderSoftPageTurnController,
            page: ReaderPageDescriptor?,
            spec: ReaderLayoutSpec,
            backgroundColor: UIColor,
            textColor: UIColor,
            direction: ReaderDirection,
            completedPageTurns: Int,
            reduceMotion: Bool,
            previous: @escaping () -> Void,
            next: @escaping () -> Void,
            toggleMenu: @escaping () -> Void
        ) {
            let isCompletedTurn = lastCompletedPageTurns.map { $0 != completedPageTurns } ?? false
            lastCompletedPageTurns = completedPageTurns
            guard let page else { return }

            let presentation = Presentation(
                page: page,
                spec: spec,
                backgroundColor: backgroundColor,
                textColor: textColor,
                direction: direction,
                animated: isCompletedTurn && !reduceMotion,
                previous: previous,
                next: next,
                toggleMenu: toggleMenu
            )
            present(presentation, on: controller)
        }

        private func present(_ presentation: Presentation, on controller: ReaderSoftPageTurnController) {
            if isAnimating {
                pendingPresentation = presentation
                return
            }

            if controller.currentPageID == presentation.page.id {
                controller.configureCurrent(
                    page: presentation.page,
                    spec: presentation.spec,
                    backgroundColor: presentation.backgroundColor,
                    textColor: presentation.textColor,
                    previous: presentation.previous,
                    next: presentation.next,
                    toggleMenu: presentation.toggleMenu
                )
                return
            }

            let target = ReaderPageContentViewController(
                page: presentation.page,
                spec: presentation.spec,
                backgroundColor: presentation.backgroundColor,
                textColor: presentation.textColor,
                previous: presentation.previous,
                next: presentation.next,
                toggleMenu: presentation.toggleMenu
            )
            guard presentation.animated else {
                controller.present(
                    target,
                    direction: presentation.direction,
                    animated: false,
                    completion: {}
                )
                return
            }

            isAnimating = true
            controller.present(
                target,
                direction: presentation.direction,
                animated: true
            ) { [weak self, weak controller] in
                guard let self, let controller else { return }
                self.isAnimating = false
                guard let pending = self.pendingPresentation else { return }
                self.pendingPresentation = nil
                self.present(pending, on: controller)
            }
        }
    }
}
