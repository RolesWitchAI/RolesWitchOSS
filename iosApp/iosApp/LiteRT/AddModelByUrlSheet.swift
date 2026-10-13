//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SharedLogic
import SwiftUI

/// Asks for a HuggingFace link and resolves it to a model before letting the caller add it.
///
/// Port of Android's `AddModelByUrlDialog`. Resolution is a network round trip, so the sheet
/// stays open with a spinner and reports failure in place: a link that turns out to be a
/// GGUF, or a repo that needs a token, is something the user can fix by editing the text
/// they already typed, and dismissing the sheet to say so would throw that text away.
struct AddModelByUrlSheet: View {
    /// The HuggingFace token from Settings, sent with both resolver requests.
    let token: String
    /// The resolved model, as the Kotlin object `SettingsManager.addCustomLitertModel` takes.
    let onResolved: (LitertModel) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var urlText = ""
    @State private var errorText: String?
    @State private var resolving = false
    @FocusState private var urlFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Link", text: $urlText, prompt: Text(verbatim: "https://huggingface.co/…"))
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($urlFocused)
                        .disabled(resolving)
                        .onChange(of: urlText) { _, _ in errorText = nil }
                        .onSubmit { resolve() }
                    if resolving {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                    } else if let errorText {
                        Text(verbatim: errorText)
                            .font(.system(size: 14))
                            .foregroundStyle(.red)
                    }
                } footer: {
                    Text("Paste a link to a HuggingFace repository holding a LiteRT (.litertlm) model.")
                }
            }
            .navigationTitle("Download from URL")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                        .disabled(resolving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Download", role: .confirm) { resolve() }
                        .disabled(urlText.trimmingCharacters(in: .whitespaces).isEmpty || resolving)
                }
            }
            .interactiveDismissDisabled(resolving)
            .onAppear { urlFocused = true }
        }
    }

    private func resolve() {
        let url = urlText.trimmingCharacters(in: .whitespaces)
        guard !url.isEmpty, !resolving else { return }
        resolving = true
        errorText = nil
        Task {
            do {
                let model = try await HuggingFaceModelResolver.shared.resolve(url: url, accessToken: token)
                resolving = false
                onResolved(model)
                dismiss()
            } catch {
                resolving = false
                errorText = Self.message(for: error)
            }
        }
    }

    /// A Kotlin exception reaches Swift as `NSError(domain: "KotlinException")`, whose
    /// `localizedDescription` is the generic "The operation couldn’t be completed" string —
    /// the resolver's own reason ("That is not a HuggingFace model link.") is the Kotlin
    /// throwable hanging off `userInfo`. Same helper as `ToolsView`.
    private static func message(for error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.userInfo["KotlinException"] as? KotlinThrowable,
           let message = throwable.message, !message.isEmpty {
            return message
        }
        return ns.localizedDescription
    }
}
