//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import UIKit

/// `UIImage` is not `Identifiable`, so wrap it to drive `fullScreenCover(item:)`.
struct IdentifiableImage: Identifiable {
    let id = UUID()
    let image: UIImage
}

/// Full-screen photo viewer opened by tapping an image in a message bubble — iOS
/// counterpart of Android's `FullscreenImageViewer` in `ui/components/BubbleMessage.kt`.
///
/// Matches the Android behaviour: black backdrop, image fitted, pinch to zoom between
/// 1× and 5×, pan only while zoomed in (and the offset snaps back when you zoom out),
/// and a 44 pt close button in the top-trailing corner. Android's dialog also closes
/// on back press / tap outside; the iOS equivalents are a tap on the backdrop and a
/// downward swipe.
struct FullscreenImageViewer: View {
    let image: UIImage
    let onDismiss: () -> Void

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    private static let minScale: CGFloat = 1
    private static let maxScale: CGFloat = 5

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
                .onTapGesture { onDismiss() }

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale)
                .offset(offset)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("Full size photo")
                .gesture(magnification.simultaneously(with: drag))
                .onTapGesture(count: 2) { toggleZoom() }
                .ignoresSafeArea()

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(Color.black.opacity(0.5), in: Circle())
            }
            .padding(16)
            .accessibilityLabel("Close")
        }
        // A downward flick dismisses, but only at 1× — while zoomed in, dragging pans.
        .gesture(
            DragGesture(minimumDistance: 30)
                .onEnded { value in
                    if scale <= Self.minScale, value.translation.height > 100 { onDismiss() }
                }
        )
    }

    private var magnification: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                scale = min(max(lastScale * value.magnification, Self.minScale), Self.maxScale)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= Self.minScale { resetPan() }
            }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > Self.minScale else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in
                guard scale > Self.minScale else { resetPan(); return }
                lastOffset = offset
            }
    }

    private func toggleZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            if scale > Self.minScale {
                scale = Self.minScale
                lastScale = Self.minScale
                resetPan()
            } else {
                scale = 2
                lastScale = 2
            }
        }
    }

    private func resetPan() {
        offset = .zero
        lastOffset = .zero
    }
}
