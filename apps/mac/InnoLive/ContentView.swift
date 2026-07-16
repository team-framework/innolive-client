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

    var body: some View {
        StudioWorkspaceView(
            studio: studio,
            cameraManager: cameraManager,
            broadcastManager: broadcastManager
        )
            .frame(minWidth: 1180, minHeight: 760)
            .task {
                cameraManager.start()
            }
            .onDisappear {
                if !studio.isBeforeFilterWindowOpen && !studio.isAfterFilterWindowOpen {
                    cameraManager.stop()
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
