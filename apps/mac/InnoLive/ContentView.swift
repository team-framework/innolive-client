//
//  ContentView.swift
//  InnoLive
//
//  Created by chaeyn on 5/22/26.
//

import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var cameraManager: CameraManager
    @EnvironmentObject private var broadcastManager: BroadcastManager
    @EnvironmentObject private var studio: StudioViewModel
    @StateObject private var authentication = GoogleAuthenticationViewModel()
    @StateObject private var appleAuthentication = AppleAuthenticationViewModel()
    @StateObject private var emailAuthentication = EmailAuthenticationViewModel()

    private var isAuthenticated: Bool {
        authentication.isSignedIn
            || appleAuthentication.isSignedIn
            || emailAuthentication.isSignedIn
    }

    var body: some View {
        Group {
            if isAuthenticated {
                StudioWorkspaceView(
                    studio: studio,
                    cameraManager: cameraManager,
                    broadcastManager: broadcastManager
                )
                    .frame(minWidth: 1180, minHeight: 760)
                    .toolbar {
                        ToolbarItem(placement: .automatic) {
                            Button("로그아웃", systemImage: "rectangle.portrait.and.arrow.right") {
                                authentication.signOut()
                                appleAuthentication.signOut()
                                emailAuthentication.signOut()
                            }
                        }
                    }
                    .task {
                        cameraManager.start()
                    }
                    .onDisappear {
                        if !studio.isBeforeFilterWindowOpen && !studio.isAfterFilterWindowOpen {
                            cameraManager.stop()
                        }
                    }
            } else {
                AuthenticationView(
                    authentication: authentication,
                    appleAuthentication: appleAuthentication,
                    emailAuthentication: emailAuthentication
                )
                    .frame(minWidth: 520, minHeight: 620)
                    .task {
                        authentication.restorePreviousSignIn()
                        appleAuthentication.restorePreviousSignIn()
                        emailAuthentication.restorePreviousSignIn()
                    }
                }
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(CameraManager())
        .environmentObject(BroadcastManager(enableMediaUplink: false))
        .environmentObject(StudioViewModel())
        .frame(width: 1280, height: 820)
}
