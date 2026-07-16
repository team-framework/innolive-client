//
//  StudioStatusBar.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import SwiftUI

struct StudioStatusBar: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager

    var body: some View {
        HStack(spacing: 14) {

            Spacer()

            Label("\(broadcastManager.metrics.bitrateKbps) kbps", systemImage: "speedometer")
                .foregroundStyle(broadcastManager.state == .live ? .primary : .secondary)
                .monospacedDigit()

            Label("\(broadcastManager.metrics.latencyMilliseconds) ms", systemImage: "clock")
                .foregroundStyle(broadcastManager.state == .live ? .primary : .secondary)
                .monospacedDigit()

            if broadcastManager.state == .live {
                Label(broadcastManager.durationText(now: studio.now), systemImage: "dot.radiowaves.left.and.right")
                    .foregroundStyle(.red)
                    .monospacedDigit()
            }

            if studio.isRecording {
                Label(studio.recordingDurationText, systemImage: "record.circle")
                    .foregroundStyle(.red)
                    .monospacedDigit()
            }

            if cameraManager.lastRecordingURL != nil {
                Button("녹화 보기") {
                    cameraManager.revealLastRecording()
                }
                .font(.caption)
            }
        }
        .font(.caption)
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(.bar)
    }
}
