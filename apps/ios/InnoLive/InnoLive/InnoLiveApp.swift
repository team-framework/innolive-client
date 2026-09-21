//
//  InnoLiveApp.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import GoogleSignIn
import SwiftUI

@main
struct InnoLiveApp: App {
    @UIApplicationDelegateAdaptor(InnoLiveAppDelegate.self) private var appDelegate
    @State private var cameraManager = CameraManager()

    var body: some Scene {
        WindowGroup {
            rootView
                .environment(cameraManager)
                .background(BroadcastOrientationSceneBridge())
                .onOpenURL { url in _ = GIDSignIn.sharedInstance.handle(url) }
        }
    }

    @ViewBuilder
    private var rootView: some View {
        #if DEBUG
        if Bundle.main.bundleIdentifier?.hasSuffix(".privacy-lab") == true
            || ProcessInfo.processInfo.arguments.contains("--on-device-privacy") {
            OnDevicePrivacyView()
        } else {
            ContentView()
        }
        #else
        ContentView()
        #endif
    }
}
