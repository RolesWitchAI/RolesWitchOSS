//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// SwiftUI wrapper around the system Files picker (`UIDocumentPickerViewController`),
/// opening the PDF content type. Mirrors Android's `ActivityResultContracts.OpenDocument`
/// with `arrayOf("application/pdf")`.
///
/// `asCopy: true` copies the picked file into our sandbox, so the caller reads the bytes
/// straight off the returned URL. That copy is **not** a security-scoped resource, so the
/// caller must not bail when `startAccessingSecurityScopedResource()` returns `false` — a
/// false return just means there is no scope to start (and none to stop).
struct DocumentPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf], asCopy: true)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let parent: DocumentPicker

        init(_ parent: DocumentPicker) {
            self.parent = parent
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            parent.onPick(url)
        }
    }
}