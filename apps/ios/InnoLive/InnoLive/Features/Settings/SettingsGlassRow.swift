//
//  SettingsGlassRow.swift
//  InnoLive
//

import SwiftUI

struct SettingsGlassRow<Content: View>: View {
    private let content: Content

    // @ViewBuilder를 사용하면 HStack처럼 여러 뷰를 하나의 행 내용으로 전달할 수 있음
    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .font(.subheadline)
            .padding(.horizontal, InnoLiveSettingsLayout.cardInset)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .frame(minHeight: InnoLiveSettingsLayout.minimumControlHeight)
            .innoLiveGlassBackground(cornerRadius: InnoLiveSettingsLayout.cornerRadius)
            .contentShape(RoundedRectangle(cornerRadius: InnoLiveSettingsLayout.cornerRadius))
    }
}
