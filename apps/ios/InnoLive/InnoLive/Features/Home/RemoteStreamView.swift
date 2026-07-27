//
//  RemoteStreamView.swift
//  InnoLive
//

import SwiftUI

struct RemoteStreamView: View {
    var body: some View {
        ZStack {
            Color.black

            ContentUnavailableView(
                "방송 송출 화면을 기다리는 중",
                systemImage: "dot.radiowaves.left.and.right",
                description: Text("방송 송출 영상이 이 영역에 표시됩니다.")
            )
            .foregroundStyle(.white.opacity(0.8))
        }
    }
}

#Preview {
    RemoteStreamView()
}
