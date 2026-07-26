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
        ZStack(alignment: .bottomTrailing) {
            Color.clear

            BroadcastControllsView(isBroadcasting: $isBroadcasting)
                .padding(.horizontal, 24)
                .padding(.trailing, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    NavigationStack {
        HomeView()
    }
}
