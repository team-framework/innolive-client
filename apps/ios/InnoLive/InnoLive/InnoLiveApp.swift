//
//  InnoLiveApp.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

@main
struct InnoLiveApp: App {
    @State private var cameraManager = CameraManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(cameraManager)
        }
    }
}
