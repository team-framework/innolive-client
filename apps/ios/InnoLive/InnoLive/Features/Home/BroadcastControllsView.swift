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
        // 가까운 Liquid Glass 버튼들의 유리 효과를 하나의 그룹처럼 보이게 함
        GlassEffectContainer {
            HStack(spacing: 12) {
                NavigationLink {
                    SettingsView()
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.title3)
                        .frame(width: 48, height: 48)
                        .contentShape(Circle())
                }
                .buttonStyle(.glass)
                // VoiceOver가 아이콘 대신 "설정"이라고 읽도록 지정
                .accessibilityLabel("설정")

                Button {
                    // true와 false를 서로 바꿔 방송 시작/종료 상태를 전환
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
