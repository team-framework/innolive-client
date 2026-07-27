//
//  LocalPreviewView.swift
//  InnoLive
//

import SwiftUI

struct LocalPreviewView: View {
    var body: some View {
        ZStack {
            Color.black
            
            VStack(spacing: 8) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 22))
                
                Text("내 화면")
                    .font(.body)
            }                 .foregroundStyle(.white.opacity(0.8))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.gray.opacity(0.6), lineWidth: 1)
        }
    }
}

#Preview {
    LocalPreviewView()
        .frame(width: 132, height: 176)
}
