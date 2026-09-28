import CoreText
import OSLog
import SwiftUI
import UIKit

@MainActor
enum ReaderPerformanceDiagnostics {
    private static let signposter = OSSignposter(
        subsystem: Bundle.main.bundleIdentifier ?? "XLibReader",
        category: "ReaderPerformance"
    )
    private static var openInterval: OSSignpostIntervalState?

    static func beginOpen() {
        openInterval = signposter.beginInterval("OpenToFirstTextDraw")
    }

    static func didDrawText() {
        guard let openInterval else { return }
        signposter.endInterval("OpenToFirstTextDraw", openInterval)
        self.openInterval = nil
    }

    static func cancelOpen() {
        openInterval = nil
    }
}

@MainActor
enum ReaderTurnDiagnostics {
#if XLIB_P8_DEVICE_PERFORMANCE
    private static let signposter = OSSignposter(
        subsystem: Bundle.main.bundleIdentifier ?? "XLibReader",
        category: "ReaderPerformance"
    )
    private static var forwardIntervals: [Int: OSSignpostIntervalState] = [:]
    private static var backwardIntervals: [Int: OSSignpostIntervalState] = [:]
#endif

#if DEBUG
    static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "XLibReader",
        category: "ReaderPageTurn"
    )
#endif

    static func directionName(_ direction: ReaderDirection) -> String {
        direction == .forward ? "forward" : "backward"
    }

    static func log(_ message: @autoclosure () -> String) {
#if DEBUG
        let value = message()
        logger.notice("\(value, privacy: .public)")
#endif
    }

    static func begin(direction: ReaderDirection, sequence: Int) {
#if XLIB_P8_DEVICE_PERFORMANCE
        switch direction {
        case .forward:
            forwardIntervals[sequence] = signposter.beginInterval("PageTurnForward")
        case .backward:
            backwardIntervals[sequence] = signposter.beginInterval("PageTurnBackward")
        }
#endif
    }

    static func end(direction: ReaderDirection, sequence: Int) {
#if XLIB_P8_DEVICE_PERFORMANCE
        switch direction {
        case .forward:
            guard let interval = forwardIntervals.removeValue(forKey: sequence) else { return }
            signposter.endInterval("PageTurnForward", interval)
        case .backward:
            guard let interval = backwardIntervals.removeValue(forKey: sequence) else { return }
            signposter.endInterval("PageTurnBackward", interval)
        }
#endif
    }
}

struct ReaderCopySelectionEvent {
    let active: Bool
    let selectedText: String?
    let anchor: CGPoint?
}

@MainActor
final class ReaderCanvasView: UIView, UIGestureRecognizerDelegate {
    var page: ReaderPageDescriptor? {
        didSet {
            guard page != oldValue else { return }
            clearSelection()
            accessibilityValue = page?.text
            setNeedsDisplay()
        }
    }
    var layoutSpec = ReaderLayoutSpec(width: 1, height: 1) {
        didSet {
            if layoutSpec != oldValue {
                clearSelection()
                setNeedsDisplay()
            }
        }
    }
    var textColor = UIColor.label {
        didSet { if textColor != oldValue { setNeedsDisplay() } }
    }
    var onPreviousPage: (() -> Void)?
    var onNextPage: (() -> Void)?
    var onToggleMenu: (() -> Void)?
    var onSelectionChanged: ((ReaderCopySelectionEvent) -> Void)?
    var selectionEnabled = true {
        didSet { if !selectionEnabled { clearSelection() } }
    }
    private var selection: NSRange?
    private var initialWord: NSRange?
    private var selectionFinalized = false
    private enum SelectionHandle { case start, end }
    private var draggedHandle: SelectionHandle?
    private weak var selectionPressGesture: UILongPressGestureRecognizer?
    private weak var selectionHandleGesture: UIPanGestureRecognizer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        isAccessibilityElement = true
        accessibilityLabel = "阅读正文"
        accessibilityIdentifier = "reader.textCanvas"
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
        let press = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        press.minimumPressDuration = 0.5
        press.delegate = self
        addGestureRecognizer(press)
        selectionPressGesture = press
        let handleDrag = UIPanGestureRecognizer(target: self, action: #selector(handleSelectionHandleDrag(_:)))
        handleDrag.delegate = self
        addGestureRecognizer(handleDrag)
        selectionHandleGesture = handleDrag
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
        if let selection { drawHighlight(selection, frame: frame, in: context) }
        CTFrameDraw(frame, context)
        if let selection, selectionFinalized {
            drawSelectionHandles(selection, frame: frame, in: context)
        }
        context.restoreGState()
        ReaderPerformanceDiagnostics.didDrawText()
    }

    func clearSelection() {
        guard selection != nil else { return }
        selection = nil
        initialWord = nil
        selectionFinalized = false
        draggedHandle = nil
        onSelectionChanged?(ReaderCopySelectionEvent(active: false, selectedText: nil, anchor: nil))
        setNeedsDisplay()
    }

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
        guard selectionEnabled, let page, !page.text.isEmpty else { return }
        let point = recognizer.location(in: self)
        switch recognizer.state {
        case .began:
            guard let index = stringIndex(at: point, requiresTextHit: true),
                  let word = ReaderTextSelection.word(in: page.text, at: index) else {
                return
            }
            selection = word
            initialWord = word
            selectionFinalized = false
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onSelectionChanged?(ReaderCopySelectionEvent(active: true, selectedText: nil, anchor: nil))
            setNeedsDisplay()
        case .changed:
            guard let initialWord, let index = stringIndex(at: point, requiresTextHit: false) else { return }
            let next = ReaderTextSelection.draggedRange(in: page.text, initial: initialWord, to: index)
            if selection != next {
                selection = next
                setNeedsDisplay()
            }
        case .ended:
            guard let selection else { return }
            selectionFinalized = true
            setNeedsDisplay()
            let value = (page.text as NSString).substring(with: selection)
            onSelectionChanged?(ReaderCopySelectionEvent(
                active: true, selectedText: value, anchor: selectionAnchor()
            ))
        case .cancelled, .failed:
            clearSelection()
        default:
            break
        }
    }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === selectionPressGesture {
            return selectionEnabled && selection == nil
        }
        guard gestureRecognizer === selectionHandleGesture else { return true }
        guard selectionFinalized,
              let selection, let frame = makeTextFrame(),
              let start = handlePoint(at: selection.location, frame: frame),
              let end = handlePoint(at: NSMaxRange(selection), frame: frame) else { return false }
        let point = gestureRecognizer.location(in: self)
        return min(hypot(point.x - start.x, point.y - start.y),
                   hypot(point.x - end.x, point.y - end.y)) <= 28
    }

    @objc private func handleSelectionHandleDrag(_ recognizer: UIPanGestureRecognizer) {
        guard let page, let selection else { return }
        let point = recognizer.location(in: self)
        switch recognizer.state {
        case .began:
            guard let frame = makeTextFrame(),
                  let start = handlePoint(at: selection.location, frame: frame),
                  let end = handlePoint(at: NSMaxRange(selection), frame: frame) else { return }
            let startDistance = hypot(point.x - start.x, point.y - start.y)
            let endDistance = hypot(point.x - end.x, point.y - end.y)
            draggedHandle = startDistance <= endDistance ? .start : .end
            onSelectionChanged?(ReaderCopySelectionEvent(active: true, selectedText: nil, anchor: nil))
        case .changed:
            guard let draggedHandle, let index = stringIndex(at: point, requiresTextHit: false) else { return }
            let next = ReaderTextSelection.movedHandle(
                in: page.text, selection: selection, start: draggedHandle == .start, to: index
            )
            if self.selection != next {
                self.selection = next
                setNeedsDisplay()
            }
        case .ended, .cancelled:
            draggedHandle = nil
            guard let selected = self.selection else { return }
            onSelectionChanged?(ReaderCopySelectionEvent(
                active: true,
                selectedText: (page.text as NSString).substring(with: selected),
                anchor: selectionAnchor()
            ))
        default:
            break
        }
    }

    private func makeTextFrame() -> CTFrame? {
        guard let page else { return nil }
        let attributed = ReaderLayoutService.makeAttributedString(text: page.text, spec: layoutSpec)
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        return CTFramesetterCreateFrame(
            framesetter, CFRange(location: 0, length: 0),
            CGPath(rect: bounds, transform: nil), nil
        )
    }

    private func stringIndex(at point: CGPoint, requiresTextHit: Bool) -> Int? {
        guard let frame = makeTextFrame(), let page else { return nil }
        let lines = CTFrameGetLines(frame) as! [CTLine]
        guard !lines.isEmpty else { return nil }
        var origins = [CGPoint](repeating: .zero, count: lines.count)
        CTFrameGetLineOrigins(frame, CFRange(location: 0, length: 0), &origins)
        let y = bounds.height - point.y
        let index = origins.indices.min { abs(origins[$0].y - y) < abs(origins[$1].y - y) }!
        let line = lines[index]
        let origin = origins[index]
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        let width = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, nil))
        if requiresTextHit,
           (y < origin.y - descent - 10 || y > origin.y + ascent + 10 ||
            point.x < origin.x || point.x > origin.x + width) { return nil }
        let lineRange = CTLineGetStringRange(line)
        let x = min(max(0, point.x - origin.x), width)
        let candidate = CTLineGetStringIndexForPosition(line, CGPoint(x: x, y: 0))
        let result = candidate == kCFNotFound ? lineRange.location : candidate
        return min(max(lineRange.location, result), min(page.text.utf16.count, lineRange.location + lineRange.length))
    }

    private func selectionAnchor() -> CGPoint? {
        guard let frame = makeTextFrame(), let selection else { return nil }
        return handlePoint(at: selection.location, frame: frame)
    }

    private func handlePoint(at offset: Int, frame: CTFrame) -> CGPoint? {
        let lines = CTFrameGetLines(frame) as! [CTLine]
        var origins = [CGPoint](repeating: .zero, count: lines.count)
        CTFrameGetLineOrigins(frame, CFRange(location: 0, length: 0), &origins)
        for index in lines.indices {
            let range = CTLineGetStringRange(lines[index])
            if offset >= range.location && offset <= range.location + range.length {
                let x = origins[index].x + CTLineGetOffsetForStringIndex(lines[index], offset, nil)
                return CGPoint(x: x, y: bounds.height - origins[index].y)
            }
        }
        return nil
    }

    private func drawSelectionHandles(_ range: NSRange, frame: CTFrame, in context: CGContext) {
        context.setStrokeColor(UIColor.systemTeal.cgColor)
        context.setFillColor(UIColor.systemTeal.cgColor)
        for (offset, start) in [(range.location, true), (NSMaxRange(range), false)] {
            guard let point = handlePoint(at: offset, frame: frame) else { continue }
            let coreY = bounds.height - point.y
            let stem = CGRect(x: point.x - 1, y: coreY - 10, width: 2, height: 20)
            context.fill(stem)
            let dotY = start ? coreY + 10 : coreY - 10
            context.fillEllipse(in: CGRect(x: point.x - 7, y: dotY - 7, width: 14, height: 14))
        }
    }

    private func drawHighlight(_ range: NSRange, frame: CTFrame, in context: CGContext) {
        let lines = CTFrameGetLines(frame) as! [CTLine]
        var origins = [CGPoint](repeating: .zero, count: lines.count)
        CTFrameGetLineOrigins(frame, CFRange(location: 0, length: 0), &origins)
        context.setFillColor(UIColor.systemTeal.withAlphaComponent(0.42).cgColor)
        for index in lines.indices {
            let lineRange = CTLineGetStringRange(lines[index])
            let lower = max(range.location, lineRange.location)
            let upper = min(NSMaxRange(range), lineRange.location + lineRange.length)
            guard lower < upper else { continue }
            let line = lines[index]
            let start = CTLineGetOffsetForStringIndex(line, lower, nil)
            let end = CTLineGetOffsetForStringIndex(line, upper, nil)
            var ascent: CGFloat = 0
            var descent: CGFloat = 0
            _ = CTLineGetTypographicBounds(line, &ascent, &descent, nil)
            context.fill(CGRect(
                x: origins[index].x + min(start, end), y: origins[index].y - descent,
                width: abs(end - start), height: ascent + descent
            ))
        }
    }
}

@MainActor
final class ReaderPageContentViewController: UIViewController {
    let pageID: Int64
    private let canvas = ReaderCanvasView()
    var onSelectionChanged: ((ReaderCopySelectionEvent) -> Void)? {
        didSet { canvas.onSelectionChanged = onSelectionChanged }
    }
    var selectionEnabled = true {
        didSet { canvas.selectionEnabled = selectionEnabled }
    }

    func clearSelection() { canvas.clearSelection() }

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
    static let duration: TimeInterval = 0.36
    static let perspectiveDistance: CGFloat = 900
    static let maximumAngle: CGFloat = .pi / 2

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
    var onSelectionChanged: ((ReaderCopySelectionEvent) -> Void)?
    var selectionEnabled = true {
        didSet { currentPageController?.selectionEnabled = selectionEnabled }
    }

    func clearCurrentSelection() { currentPageController?.clearSelection() }

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
        currentPageController?.onSelectionChanged = onSelectionChanged
        currentPageController?.selectionEnabled = selectionEnabled
    }

    func present(
        _ target: ReaderPageContentViewController,
        direction: ReaderDirection,
        animated: Bool,
        completion: @escaping () -> Void
    ) {
        target.onSelectionChanged = onSelectionChanged
        target.selectionEnabled = selectionEnabled
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
            controlPoint1: CGPoint(x: 0.28, y: 0.08),
            controlPoint2: CGPoint(x: 0.72, y: 0.92)
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
        target.onSelectionChanged = onSelectionChanged
        target.selectionEnabled = selectionEnabled
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
        leaf.layer.isDoubleSided = false
    }

    private func normalize(_ pageView: UIView) {
        pageView.layer.transform = CATransform3DIdentity
        pageView.layer.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        pageView.layer.isDoubleSided = true
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
    let selectionEnabled: Bool
    let clearSelectionGeneration: Int
    let selectionChanged: (ReaderCopySelectionEvent) -> Void
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
        controller.onSelectionChanged = { event in
            Task { @MainActor in selectionChanged(event) }
        }
        controller.selectionEnabled = selectionEnabled
        if context.coordinator.lastClearSelectionGeneration != clearSelectionGeneration {
            context.coordinator.lastClearSelectionGeneration = clearSelectionGeneration
            controller.clearCurrentSelection()
        }
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
        var lastClearSelectionGeneration = 0
        private struct Presentation {
            let page: ReaderPageDescriptor
            let spec: ReaderLayoutSpec
            let backgroundColor: UIColor
            let textColor: UIColor
            let direction: ReaderDirection
            let turnSequence: Int
            let animated: Bool
            let previous: () -> Void
            let next: () -> Void
            let toggleMenu: () -> Void
        }

        private var lastCompletedPageTurns: Int?
        private(set) var isAnimating = false
        private var pendingPageTurns: [Presentation] = []
        private var pendingRefresh: Presentation?
        var pendingPageTurnCount: Int { pendingPageTurns.count }

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
                turnSequence: completedPageTurns,
                animated: isCompletedTurn && !reduceMotion,
                previous: previous,
                next: next,
                toggleMenu: toggleMenu
            )
            present(presentation, on: controller)
        }

        private func present(_ presentation: Presentation, on controller: ReaderSoftPageTurnController) {
            if isAnimating {
                if presentation.animated {
                    pendingPageTurns.append(presentation)
                    ReaderTurnDiagnostics.log(
                        "animation queued sequence=\(presentation.turnSequence) direction=\(ReaderTurnDiagnostics.directionName(presentation.direction)) page=\(presentation.page.id) queue=\(self.pendingPageTurns.count)"
                    )
                } else {
                    pendingRefresh = presentation
                }
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
                if presentation.animated {
                    ReaderTurnDiagnostics.end(
                        direction: presentation.direction,
                        sequence: presentation.turnSequence
                    )
                }
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
                    completion: {
                        ReaderTurnDiagnostics.end(
                            direction: presentation.direction,
                            sequence: presentation.turnSequence
                        )
                    }
                )
                return
            }

            isAnimating = true
            ReaderTurnDiagnostics.log(
                "animation started sequence=\(presentation.turnSequence) direction=\(ReaderTurnDiagnostics.directionName(presentation.direction)) from=\(controller.currentPageID ?? -1) to=\(presentation.page.id)"
            )
            controller.present(
                target,
                direction: presentation.direction,
                animated: true
            ) { [weak self, weak controller] in
                guard let self, let controller else { return }
                self.isAnimating = false
                ReaderTurnDiagnostics.log(
                    "animation completed sequence=\(presentation.turnSequence) page=\(presentation.page.id) queue=\(self.pendingPageTurns.count)"
                )
                ReaderTurnDiagnostics.end(
                    direction: presentation.direction,
                    sequence: presentation.turnSequence
                )
                if !self.pendingPageTurns.isEmpty {
                    let pending = self.pendingPageTurns.removeFirst()
                    self.present(pending, on: controller)
                } else if let refresh = self.pendingRefresh {
                    self.pendingRefresh = nil
                    self.present(refresh, on: controller)
                }
            }
        }
    }
}
