//
//  InnoLiveApp.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

@main
struct InnoLiveApp: App {
    // 홈과 설정 화면이 함께 사용할 카메라 관리자 한 개를 만든다.
    @State private var cameraManager = CameraManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                // 하위 화면은 @Environment로 같은 CameraManager를 꺼내 쓴다.
                .environment(cameraManager)
        }
    }
}
