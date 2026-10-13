//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI

/// A selectable model card — ported from the shipping SwiftUI build's
/// `RolesWitch/App/SupportingViews/ModelRow.swift`.
///
/// Icon in a tinted circle, name, description, an optional footnote, and a radio-style tick
/// on the right; the whole card is one button. Unlike the original, [title] and
/// [modelDescription] are plain `String`s rendered `verbatim`: a model name must never be
/// run through the string catalogue (AGENTS.md), and the description is already localized
/// by the registry that built it. Downloads are never shown here — they take the screen
/// over with the blocking download sheet, as on Android.
struct ModelRow: View {
    let icon: String?
    let title: String
    let modelDescription: String
    let color: Color
    let isSelected: Bool
    /// Trailing note under the description — "Installed", "Not installed", whatever the
    /// caller needs to say about this card. Empty hides it. The model's size is not
    /// offered here: it belongs to the description line, which already carries it.
    let footnote: String
    let onTap: () -> Void

    init(
        icon: String? = nil,
        title: String,
        modelDescription: String,
        color: Color = .blue,
        isSelected: Bool,
        footnote: String = "",
        onTap: @escaping () -> Void
    ) {
        self.icon = icon
        self.title = title
        self.modelDescription = modelDescription
        self.color = color
        self.isSelected = isSelected
        self.footnote = footnote
        self.onTap = onTap
    }

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 12) {
                HStack(spacing: 16) {
                    if let icon {
                        ZStack {
                            Circle()
                                .fill(color.opacity(0.15))
                                .frame(width: 56, height: 56)

                            Image(systemName: icon)
                                .font(.system(size: 24))
                                .foregroundStyle(color)
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: title)
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.primary)

                        Text(verbatim: modelDescription)
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.leading)
                            .lineLimit(4)

                        if !footnote.isEmpty {
                            Text(verbatim: footnote)
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 24))
                        .foregroundStyle(isSelected ? color : Color.gray.opacity(0.3))
                }
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color(UIColor.secondarySystemGroupedBackground))
                    .shadow(color: .black.opacity(0.05), radius: 4, y: 2)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(isSelected ? color : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }
}

/// Remote vs on-device, as a sheet. Mirrors Android's `EnginePickerSheet`. Engine-agnostic —
/// both headings and descriptions are fixed chrome, so either engine can present this.
struct EnginePickerSheet: View {
    let current: Bool
    let onSelect: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    ModelRow(
                        icon: "iphone",
                        title: String(localized: "On device"),
                        modelDescription: String(localized: "Run a downloaded model on this device. Works offline; nothing is sent anywhere."),
                        isSelected: current
                    ) {
                        onSelect(true)
                    }
                    ModelRow(
                        icon: "cloud",
                        title: String(localized: "Remote"),
                        modelDescription: String(localized: "Use an OpenAI-compatible API. Requires a connection and a key."),
                        isSelected: !current
                    ) {
                        onSelect(false)
                    }
                }
                .padding(20)
            }
            .navigationTitle("Engine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
            }
        }
    }
}