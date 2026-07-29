//
//  HomeView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import AVFoundation
import SwiftUI

struct HomeView: View {
    @State private var isBroadcasting = false
    @Environment(CameraManager.self) private var cameraManager

    var body: some View {
        ZStack {
            RemoteStreamView()
                .ignoresSafeArea()

            LocalPreviewView(session: cameraManager.session)
                .frame(width: 132, height: 176)
                .padding(24)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .topLeading
                )

            BroadcastControllsView(isBroadcasting: $isBroadcasting)
                .padding(.horizontal, 24)
                .padding(.trailing, 8)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .bottomTrailing
                )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .toolbar(.hidden, for: .navigationBar) // 네비게이션 바를 숨김
        .onAppear {
            if cameraManager.authorizationStatus == .authorized {
                cameraManager.startDefaultCamera()
            } else {
                cameraManager.requestCameraAccess()
            }
        }
        .onChange(of: cameraManager.authorizationStatus) { _, status in
            // 권한 팝업에서 허용을 누른 직후 첫 번째 카메라를 시작함
            if status == .authorized {
                cameraManager.startDefaultCamera()
            }
        }
    }
}

#Preview {
    NavigationStack {
        HomeView()
    }
}
