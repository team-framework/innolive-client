//
//  InnoLiveApp.swift
//  InnoLive
//
//  Created by chaeyn on 5/22/26.
//

import SwiftUI

@main
struct InnoLiveApp: App {
    @StateObject private var cameraManager = CameraManager()
    @StateObject private var broadcastManager = BroadcastManager(enableMediaUplink: true)
    @StateObject private var studio = StudioViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(cameraManager)
                .environmentObject(broadcastManager)
                .environmentObject(studio)
        }
    }
}
