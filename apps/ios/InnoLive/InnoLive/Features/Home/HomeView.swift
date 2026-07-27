//
//  HomeView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct HomeView: View {
    @State private var isBroadcasting = false

    var body: some View {
        ZStack {
            RemoteStreamView()
                .ignoresSafeArea()

            LocalPreviewView()
                .frame(width: 132, height: 176)
                .padding(24)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .topLeading
                )

            BroadcastControllsView(isBroadcasting: $isBroadcasting)
                .padding(.horizontal, 24)
                .padding(.trailing, 8)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .bottomTrailing
                )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .toolbar(.hidden, for: .navigationBar) // 네비게이션 바를 숨김
    }
}

#Preview {
    NavigationStack {
        HomeView()
    }
}
