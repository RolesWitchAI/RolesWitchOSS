//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI

/// Searchable model picker for OpenAI-compatible endpoints, ported from the
/// shipped app's `OpenAIModelPickerView`. Fetches the model list from the given
/// endpoint and reports the selection.
///
/// On-device (LiteRT-LM) models use `LiteRtModelView` instead — this one is API-only.
struct RemoteModelPickerSheet: View {
    let endpoint: String
    let token: String
    let currentModel: String
    let onPick: (String) -> Void

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var models: [String] = []
    @State private var isFetching = false
    @State private var errorMessage: String?
    @State private var searchText = ""

    private var filteredModels: [String] {
        if searchText.isEmpty {
            return models
        }
        return models.filter { $0.localizedCaseInsensitiveContains(searchText) }
    }

    var body: some View {
        NavigationStack {
            List {
                // Models section
                Section {
                    content
                }
            }
            .searchable(text: $searchText, prompt: Text("Search models"))
            .navigationTitle("Select Model")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
        .task { await fetch() }
    }

    @ViewBuilder
    private var content: some View {
        if endpoint.trimmingCharacters(in: .whitespaces).isEmpty {
            placeholder(Text("Configure an API URL first."))
        } else if isFetching {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Loading models…").foregroundStyle(.secondary)
            }
        } else if let errorMessage {
            Text(verbatim: errorMessage)
                .font(.footnote)
                .foregroundStyle(.red)
        } else if filteredModels.isEmpty {
            placeholder(Text(searchText.isEmpty ? "No models found." : "No matching models"))
        } else {
            ForEach(filteredModels, id: \.self) { model in
                Button {
                    onPick(model)
                } label: {
                    HStack {
                        // The model id the endpoint reported.
                        Text(verbatim: model)
                            .foregroundStyle(.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if currentModel == model {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                }
            }
        }
    }

    private func placeholder(_ text: Text) -> some View {
        text
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    private func fetch() async {
        isFetching = true
        errorMessage = nil
        defer { isFetching = false }
        let trimmed = endpoint
            .trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !trimmed.isEmpty else { return }
        let tok = token.isEmpty ? "none" : token
        do { models = try await app.fetchModels(endpoint: trimmed, token: tok) }
        catch { errorMessage = error.localizedDescription }
    }
}
