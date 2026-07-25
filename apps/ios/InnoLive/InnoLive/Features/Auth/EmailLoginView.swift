//
//  EmailLoginView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct EmailLoginView: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button {
            // 이메일 로그인 화면 연결
        } label: {
            Label("이메일로 로그인", systemImage: "envelope.fill")
                .font(.callout.weight(.semibold))
                .imageScale(.small)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(.plain)
        .foregroundStyle(colorScheme == .dark ? .white : .black)
        .frame(maxWidth: .infinity)
        .frame(height: 44)
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(
                    colorScheme == .dark ? .white : .black,
                    lineWidth: 1
                )
        }
    }
}
