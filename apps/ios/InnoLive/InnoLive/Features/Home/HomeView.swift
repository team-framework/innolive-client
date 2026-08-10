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
    @State private var isShowingCameraPermissionAlert = false
    let onSignOut: () -> Void
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    private var isCameraAccessDenied: Bool {
        cameraManager.authorizationStatus == .denied
            || cameraManager.authorizationStatus == .restricted
    }

    var body: some View {
        ZStack {
            RemoteStreamView()
                .ignoresSafeArea()

            LocalPreviewView(
                session: cameraManager.session,
                // 카메라 전환 시 프리뷰 회전 기준도 함께 갱신
                cameraID: cameraManager.currentCameraID
            )
                .frame(width: 132, height: 176)
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .topLeading
                )

            VStack(alignment: .trailing, spacing: 12) {
                Button(action: onSignOut) {
                    Label("로그아웃", systemImage: "rectangle.portrait.and.arrow.right")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: Capsule())
                }

                if isCameraAccessDenied {
                    Button {
                        isShowingCameraPermissionAlert = true
                    } label: {
                        Label("카메라 권한 필요", systemImage: "camera.fill")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.regularMaterial, in: Capsule())
                    }
                }
            }
            .padding(.top, 8)
            .padding(.horizontal, 24)
            .frame(
                maxWidth: .infinity,
                maxHeight: .infinity,
                alignment: .topTrailing
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
            switch cameraManager.authorizationStatus {
            case .authorized:
                cameraManager.startDefaultCamera()

            case .notDetermined:
                cameraManager.requestCameraAccess()

            case .denied, .restricted:
                // 이미 거부된 권한은 우측 상단 버튼으로 다시 안내함
                break

            @unknown default:
                break
            }
        }
        .onChange(of: cameraManager.authorizationStatus) { _, status in
            if status == .authorized {
                // 권한 팝업에서 허용을 누른 직후 첫 번째 카메라를 시작함
                cameraManager.startDefaultCamera()
            } else if status == .denied || status == .restricted {
                isShowingCameraPermissionAlert = true
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else {
                return
            }

            // 설정 앱에서 권한을 바꾼 뒤 돌아오면 안내 UI 상태를 즉시 갱신함
            cameraManager.refreshAuthorizationStatus()
        }
        .alert("카메라 권한이 필요합니다", isPresented: $isShowingCameraPermissionAlert) {
            Button("설정 열기") {
                openCameraSettings()
            }

            Button("취소", role: .cancel) { }
        } message: {
            Text("카메라를 사용하려면 설정에서 카메라 접근을 허용해 주세요.")
        }
    }

    // 앱 설정 화면을 열어 사용자가 카메라 권한을 직접 변경할 수 있게 함
    private func openCameraSettings() {
        guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else {
            return
        }

        openURL(settingsURL)
    }
}

#Preview {
    NavigationStack {
        HomeView(onSignOut: { })
    }
    .environment(CameraManager())
}
