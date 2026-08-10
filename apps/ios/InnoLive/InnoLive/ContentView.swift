//
//  ContentView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct ContentView: View {
    @StateObject private var authentication = AuthSession()

    var body: some View {
        Group {
            if authentication.isAuthenticated {
                NavigationStack {
                    HomeView(onSignOut: authentication.signOut)
                }
            } else {
                NavigationStack {
                    SignInView(authentication: authentication)
                }
            }
        }
        .task { authentication.restore() }
    }
}

#Preview("Dark") {
    ContentView()
        .environment(CameraManager())
        .preferredColorScheme(.dark)
}

#Preview("Light") {
    ContentView()
        .environment(CameraManager())
        .preferredColorScheme(.light)
}
