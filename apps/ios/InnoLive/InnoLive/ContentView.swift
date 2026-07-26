//
//  ContentView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct ContentView: View {
    @State private var isSignedIn = false

    var body: some View {
        if isSignedIn {
            NavigationStack {
                HomeView()
            }
        } else {
            NavigationStack {
                SignInView(isSignedIn: $isSignedIn)
            }
        }
    }
}

#Preview("Dark") {
    ContentView()
        .preferredColorScheme(.dark)
}

#Preview("Light") {
    ContentView()
        .preferredColorScheme(.light)
}
