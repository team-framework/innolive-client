////
////  DelayedFramePreview.swift
////  InnoLive
////
////  Created by chaeyn on 5/28/26.
////
//
//import CoreGraphics
//import SwiftUI
//
//struct DelayedFramePreview: View {
//    let frame: CGImage?
//    let isEnabled: Bool
//
//    var body: some View {
//        ZStack {
//            Color.black
//
//            if isEnabled, let frame {
//                Image(decorative: frame, scale: 1, orientation: .up)
//                    .resizable()
//                    .scaledToFill()
//                    .transition(.opacity)
//            } else if isEnabled {
//                ContentUnavailableView(
//                    "응답 영상 준비 중",
//                    systemImage: "clock.arrow.circlepath",
//                    description: Text("현재 카메라를 3초 지연해서 표시합니다.")
//                )
//                .foregroundStyle(.secondary)
//            } else {
//                ContentUnavailableView(
//                    "응답 영상 꺼짐",
//                    systemImage: "server.rack",
//                    description: Text("응답 영상 버튼을 눌러 미리보기를 켤 수 있습니다.")
//                )
//                .foregroundStyle(.secondary)
//            }
//        }
//        .animation(.easeInOut(duration: 0.18), value: frame != nil)
//        .clipped()
//    }
//}
