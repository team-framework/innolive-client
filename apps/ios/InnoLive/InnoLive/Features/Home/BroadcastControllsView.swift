//
//  BroadcastControllsView.swift
//  InnoLive
//
//  Created by chaeyn on 7/26/26.
//

import SwiftUI

struct BroadcastControllsView: View {
    @Binding var isBroadcasting: Bool

    var body: some View {
        GlassEffectContainer {
            HStack(spacing: 12) {
                Button {
                    print("설정 버튼 선택")
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.title3)
                        .frame(width: 48, height: 48)
                        .contentShape(Circle())
                }
                .buttonStyle(.glass)
                .accessibilityLabel("설정")

                Button {
                    isBroadcasting.toggle()
                } label: {
                    Label(
                        isBroadcasting ? "방송 종료" : "방송 시작",
                        systemImage: isBroadcasting ? "stop.fill" : "play.fill"
                    )
                    .font(.body.weight(.semibold))
                    .frame(height: 48)
                    .padding(.horizontal, 16)
                    .contentShape(Capsule())
                }
                .buttonStyle(.glassProminent)
                .tint(isBroadcasting ? .red : .blue)
            }
        }
    }
}
