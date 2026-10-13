//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import SharedLogic

/// Manages the MCP endpoints the assistant may call, mirroring Android's
/// `ToolsScreen.kt`.
///
/// One row is one endpoint, which usually exposes several callable functions —
/// those are discovered per request, never stored. This screen is purely a registry:
/// adding a row does not let anything call it. That is decided per chat, in the Tools
/// section of the chat's model settings.
struct ToolsView: View {
    @EnvironmentObject private var app: AppViewModel

    @State private var tools: [McpTool] = []
    /// Nil while closed. Carries the row being edited, or a blank draft for "Add".
    @State private var editing: ToolDraft?
    /// Set by the row's swipe action; the confirm alert deletes and clears it.
    @State private var toolToDelete: McpTool?

    var body: some View {
        Form {
            Section {
                Text("A registry of MCP servers the assistant can call. Adding one here does not switch it on \u{2014} enable it for a chat in that chat\u{2019}s model settings. Everything runs on this device: the endpoint and token never leave it except to reach the server you name.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            // Floats the paragraph on the background instead of the grouped row, the
            // Acknowledgements header's look.
            .listRowBackground(Color.clear)

            if !tools.isEmpty {
                Section {
                    ForEach(tools, id: \.id) { tool in
                        // Tapping anywhere opens the editor — there is no switch to
                        // share the row with, so the whole row is one hit target.
                        // Delete lives on the swipe, the threads screen's gesture,
                        // keeping destructive actions out of the editing form.
                        Button {
                            editing = ToolDraft(tool)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: tool.name).foregroundStyle(.primary)
                                    Text(verbatim: tool.url)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            // Not `role: .destructive`: inside a List/Form that role also
                            // plays the system's row-removal animation, which would slide
                            // the row away before the confirm dialog appears. A plain
                            // action with a red tint keeps the look and leaves the row
                            // where it is until Delete is confirmed.
                            Button {
                                toolToDelete = tool
                            } label: { Label("Delete", systemImage: "trash") }
                            .tint(.red)
                        }
                    }
                }
            }

            Section {
                Button {
                    editing = ToolDraft()
                } label: {
                    Label("Add tool", systemImage: "plus")
                }
            }
        }
        .navigationTitle("Tools")
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .sheet(item: $editing) { draft in
            ToolEditorSheet(draft: draft) { await reload() }
                .environmentObject(app)
                .presentationDetents([.medium, .large])
        }
        .alert("Delete this tool?", isPresented: Binding(
            get: { toolToDelete != nil },
            set: { if !$0 { toolToDelete = nil } })
        ) {
            Button("Delete", role: .destructive) {
                if let tool = toolToDelete {
                    delete(tool)
                }
                toolToDelete = nil
            }
            Button("Cancel", role: .cancel) { toolToDelete = nil }
        }
    }

    private func reload() async {
        let loaded = await app.loadTools()
        await MainActor.run { tools = loaded }
    }

    private func delete(_ tool: McpTool) {
        Task {
            await app.deleteTool(tool)
            await reload()
        }
    }
}

/// Editable copy of an endpoint. `id == nil` means "not saved yet".
struct ToolDraft: Identifiable {
    /// Identity for `.sheet(item:)` — distinct from the persisted [toolId], which is
    /// nil for a new draft and would otherwise collapse every "Add" into one item.
    let id = UUID()
    let toolId: String?
    var name: String
    var url: String
    var token: String

    init() {
        toolId = nil
        name = ""
        url = ""
        token = ""
    }

    init(_ tool: McpTool) {
        toolId = tool.id
        name = tool.name
        url = tool.url
        token = tool.token
    }

    var isNew: Bool { toolId == nil }
}

/// Add/edit sheet. Saving runs a real `tools/list` against the entered address first, so
/// a row can only be stored once it is known to work — the user finds out here rather than
/// mid-conversation. This used to be a separate "Test connection" button, which was easy
/// to skip and left the save path unchecked; folding it into Save means it always happens.
private struct ToolEditorSheet: View {
    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State var draft: ToolDraft
    let onSaved: () async -> Void

    @State private var checking = false
    @State private var error: String?

    init(draft: ToolDraft, onSaved: @escaping () async -> Void) {
        _draft = State(initialValue: draft)
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $draft.name)
                    // An example address, not translatable copy — `verbatim` keeps it out
                    // of the string catalog.
                    TextField(text: $draft.url, prompt: Text(verbatim: "https://example.com/mcp")) { EmptyView() }
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onChange(of: draft.url) { error = nil }
                    SecureField("Optional", text: $draft.token)
                        .onChange(of: draft.token) { error = nil }
                }

                // The two states of the check, matching the model picker: a spinner with
                // a label while the request is out, the failure in red once it comes back.
                if checking {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Checking connection…").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }

                if let error {
                    Section {
                        Text(verbatim: error).font(.footnote).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(draft.isNew ? Text("Add tool") : Text("Edit tool"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .confirm) { save() }
                        .disabled(draft.name.isEmpty || draft.url.isEmpty || checking)
                }
            }
        }
    }

    /// Verifies the endpoint answers `tools/list`, and only then writes the row.
    private func save() {
        checking = true
        error = nil
        let url = Self.normalized(draft.url)
        let token = draft.token.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let names = try await app.discoverToolNames(url: url, token: token)
                // An endpoint that lists nothing is reachable but useless to the model,
                // and the sheet closing on save is the last chance to say so.
                guard !names.isEmpty else {
                    await MainActor.run {
                        error = String(localized: "Connected, but the server offers no tools.")
                        checking = false
                    }
                    return
                }
            } catch {
                await MainActor.run {
                    self.error = "\(String(localized: "Failed:")) \(Self.message(for: error))"
                    checking = false
                }
                return
            }

            await app.saveTool(
                id: draft.toolId,
                name: draft.name.trimmingCharacters(in: .whitespaces),
                url: url,
                token: token
            )
            await onSaved()
            await MainActor.run {
                checking = false
                dismiss()
            }
        }
    }

    /// A Kotlin exception reaches Swift as `NSError(domain: "KotlinException")`, whose
    /// `localizedDescription` is the generic "The operation couldn’t be completed" string —
    /// the message we actually want is the Kotlin throwable hanging off `userInfo`.
    private static func message(for error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.userInfo["KotlinException"] as? KotlinThrowable,
           let message = throwable.message, !message.isEmpty {
            return message
        }
        return ns.localizedDescription
    }

    /// Trims and assumes `https://` when no scheme was typed. Deliberately *not* the
    /// shared `normalizeApiUrl`, which is tuned for local LM Studio endpoints (default
    /// port 1234, appends `/v1`) and would corrupt an MCP address.
    private static func normalized(_ input: String) -> String {
        let raw = input.trimmingCharacters(in: .whitespaces)
        guard !raw.isEmpty else { return raw }
        if raw.hasPrefix("http://") || raw.hasPrefix("https://") { return raw }
        return "https://\(raw)"
    }
}
