//
//  ContentView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct ContentView: View {
    @State private var isLoggedIn = false

    var body: some View {
        if isLoggedIn {
            HomeView()
        } else {
            LoginView(isLoggedIn: $isLoggedIn)
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
