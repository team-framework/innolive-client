import SwiftUI
import UIKit

/// Locks only the enclosing native page scroll view, leaving buttons and inner settings scrolls active.
struct StudioPagerScrollLock: UIViewRepresentable {
    let isLocked: Bool

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: ProbeView, context: Context) {
        uiView.isLocked = isLocked
        uiView.applyLock()
    }

    final class ProbeView: UIView {
        var isLocked = false
        private(set) weak var pagingScrollView: UIScrollView?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyLock()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            applyLock()
        }

        private static func isHorizontalPager(_ scrollView: UIScrollView) -> Bool {
            if scrollView.isPagingEnabled { return true }
            // Newer SwiftUI page containers use a horizontal collection layout without isPagingEnabled.
            guard let collection = scrollView as? UICollectionView else { return false }
            if let layout = collection.collectionViewLayout as? UICollectionViewFlowLayout {
                return layout.scrollDirection == .horizontal
            }
            if let layout = collection.collectionViewLayout as? UICollectionViewCompositionalLayout {
                return layout.configuration.scrollDirection == .horizontal
            }
            return false
        }

        func applyLock() {
            var ancestor = superview
            while let view = ancestor {
                if let scrollView = view as? UIScrollView,
                   Self.isHorizontalPager(scrollView) {
                    pagingScrollView = scrollView
                    scrollView.isScrollEnabled = !isLocked
                    return
                }
                ancestor = view.superview
            }
        }
    }
}
