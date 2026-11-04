import SwiftUI
import UIKit

/// 바깥쪽 네이티브 페이지 스크롤 뷰만 잠근다. 버튼과 안쪽 설정 스크롤은 그대로 동작한다.
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
            // 최신 SwiftUI 페이지 컨테이너는 isPagingEnabled 없이 가로 컬렉션 레이아웃을 쓴다.
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
