//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import PhotosUI
import SharedLogic

/// Thread profile editor. Mirrors Android `ThreadProfileScreen`:
/// - 160pt avatar (tap "Change image" → gallery / camera / remove)
/// - Name + Prompt editors with explicit Save (disabled when unchanged/invalid)
/// - Discard-confirm alert when backing out with unsaved name/prompt changes
///
/// The avatar is persisted immediately on crop (independent of name/prompt Save),
/// matching Android's split save model. Pushed as a destination — Android's
/// `"profile/{threadId}"` route — from the `MessagesView` header or overflow menu.
struct ThreadProfileView: View {
    let threadId: String

    /// The row the pusher already had — used to seed the avatar so it never renders a
    /// placeholder "?" for a frame while the fresh copy is fetched in `.task`.
    let initialThread: MessageThread?

    init(threadId: String, initialThread: MessageThread? = nil) {
        self.threadId = threadId
        self.initialThread = initialThread
        // Seed the editable fields so the first body render has the row's bytes and
        // text instead of the fallback "?" circle.
        _thread = State(initialValue: initialThread)
        _title = State(initialValue: initialThread?.title ?? "")
        _promptText = State(initialValue: initialThread?.prompt ?? "")
        _originalTitle = State(initialValue: initialThread?.title ?? "")
        _originalPrompt = State(initialValue: initialThread?.prompt ?? "")
    }

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var thread: MessageThread?
    @State private var title: String = ""
    @State private var promptText: String = ""
    @State private var originalTitle: String = ""
    @State private var originalPrompt: String = ""
    @State private var existingTitles: Set<String> = []

    @State private var showSourcePicker = false
    @State private var showDiscardAlert = false

    // Image capture pipeline. Both the camera and the crop step are presented as
    // full-screen covers so the UI is edge-to-edge. There is no stacked-sheet
    // conflict because the camera cover is dismissed before the crop cover opens.
    @State private var showCamera = false
    @State private var showPhotoPicker = false
    @State private var photoItem: PhotosPickerItem?
    @State private var cropImage: CroppingImage?

    private var avatarImage: UIImage? {
        guard let bytes = thread?.avatarBytes else { return nil }
        return UIImage(data: bytes.toData())
    }

    @ViewBuilder
    private var avatarContent: some View {
        if let image = avatarImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 140, height: 140)
                .clipShape(Circle())
        } else {
            AvatarView(
                name: title.isEmpty ? (thread?.title ?? "") : title,
                avatarBytes: thread?.avatarBytes,
                size: 140
            )
        }
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    avatarContent
                        .frame(maxWidth: .infinity)

                    Button("Change image") { showSourcePicker = true }
                }
            }
            .listRowBackground(Color.clear)

            Section("Name") {
                TextField("Name", text: $title)
                if title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("Name cannot be empty").foregroundStyle(.red).font(.footnote)
                } else if nameAlreadyExists {
                    Text("This name already exists").foregroundStyle(.red).font(.footnote)
                }
            }

            Section("Prompt") {
                TextEditor(text: $promptText)
                    .frame(minHeight: 160)
            }

            // No model-settings row here, matching Android's `ThreadProfileScreen`:
            // this screen edits who the assistant *is* (avatar, name, prompt). How it
            // runs is reached from the chat's overflow menu ("Configure model"), which
            // is the single entry point on both platforms.
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        // The system back stays available — button *and* swipe — while nothing is
        // dirty; hiding it is what disables the swipe, so the hide is conditional.
        // While dirty, the custom chevron below replaces it and runs the discard
        // check, the pushed counterpart of Android's `BackHandler` in
        // `ThreadProfileScreen.handleBack`.
        .navigationBarBackButtonHidden(isDirty)
        .toolbar {
            if isDirty {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showDiscardAlert = true
                    } label: {
                        Image(systemName: "chevron.backward")
                    }
                    .accessibilityLabel("Back")
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(role: .confirm) { save() }
                    .disabled(!canSave)
            }
        }
        .alert("Discard changes?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { dismiss() }
            Button("Cancel", role: .cancel) {}
        }
        .task { await load() }
        .confirmationDialog("Change image", isPresented: $showSourcePicker, titleVisibility: .hidden) {
            Button("Photo Library") { showPhotoPicker = true }
            Button("Take Photo") { showCamera = true }
            if thread?.avatarBytes != nil {
                Button("Remove avatar", role: .destructive) { removeAvatar() }
            }
            Button("Cancel", role: .cancel) {}
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                // Decode off the main thread, then route into the crop sheet.
                var next: CroppingImage?
                if let data = try? await item.loadTransferable(type: Data.self),
                   let uiImage = UIImage(data: data) {
                    next = CroppingImage(image: uiImage)
                }
                await MainActor.run {
                    photoItem = nil
                    cropImage = next
                }
            }
        }
        // Camera: full-screen, edge-to-edge. `autoDismiss: false` so we drive the
        // dismissal ourselves (dismiss cover → present crop sheet), avoiding a
        // double-dismissal from the picker's own `dismiss(animated:)`.
        .fullScreenCover(isPresented: $showCamera) {
            ZStack(alignment: .topLeading) {
                ImagePicker(source: .camera, autoDismiss: false) { uiImage in
                    cropImage = CroppingImage(image: uiImage)
                    showCamera = false
                }
                .ignoresSafeArea()

                // fullScreenCover can't be swipe-dismissed, so expose an explicit
                // close affordance (the native camera UI has no cancel button).
                Button {
                    showCamera = false
                } label: {
                    Image(systemName: "xmark")
                        .font(.title2.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Color.black.opacity(0.45), in: Circle())
                }
                .padding(.top, 8)
                .padding(.leading, 16)
                .accessibilityLabel("Cancel")
            }
        }
        .fullScreenCover(item: $cropImage) { wrapper in
            NavigationStack {
                ImageCropView(image: wrapper.image) { cropped in
                    cropImage = nil
                    persist(cropped)
                } onCancel: {
                    cropImage = nil
                }
            }
        }
    }

    // MARK: Derived

    /// True when the name or prompt differs from what was loaded. Drives both the
    /// Save button and the discard confirmation, so the two can never disagree
    /// about whether there is anything to lose.
    private var isDirty: Bool {
        guard thread != nil else { return false }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = promptText.trimmingCharacters(in: .whitespacesAndNewlines)
        return t != originalTitle.trimmingCharacters(in: .whitespacesAndNewlines)
            || p != originalPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSave: Bool {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = promptText.trimmingCharacters(in: .whitespacesAndNewlines)
        return isDirty && !t.isEmpty && !p.isEmpty && !nameAlreadyExists
    }

    private var nameAlreadyExists: Bool {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !t.isEmpty else { return false }
        // Matches Android's ThreadProfileScreen: another thread (different id) already
        // uses this title (case-insensitive).
        return existingTitles.contains(t)
    }

    // MARK: Actions

    private func load() async {
        let dao = app.container.database.messageThreadDao()
        async let loadedTask = try? await dao.getThreadById(threadId: threadId)
        async let allTask = try? await app.container.database.getAllThreads()
        let loaded = await loadedTask
        let all = await allTask ?? []
        await MainActor.run {
            thread = loaded
            title = loaded?.title ?? ""
            promptText = loaded?.prompt ?? ""
            originalTitle = loaded?.title ?? ""
            originalPrompt = loaded?.prompt ?? ""
            existingTitles = Set(
                all
                    .filter { $0.threadId != threadId }
                    .map { $0.title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            )
        }
    }

    private func save() {
        guard let thread else { return }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = promptText.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            await app.updateThread(thread, title: t, prompt: p)
            await MainActor.run {
                originalTitle = t
                originalPrompt = p
                dismiss()
            }
        }
    }

    /// Compresses the cropped square image (512px / JPEG 0.85, matching Android's
    /// `uriToCompressedBytes(…, maxDimension = 512)`) and persists it immediately.
    private func persist(_ image: UIImage) {
        guard let thread, let data = AppViewModel.encodeImage(image, maxDimension: 512, quality: 0.85) else { return }
        Task {
            await app.updateThreadAvatar(thread, data: data)
            await reloadThread()
        }
    }

    private func removeAvatar() {
        guard let thread else { return }
        Task {
            await app.updateThreadAvatar(thread, data: nil)
            await reloadThread()
        }
    }

    /// Re-fetches the thread so the avatar preview reflects the just-written bytes
    /// (`MessageThread.copy` isn't available in Swift, so we reload from Room).
    private func reloadThread() async {
        guard let updated = try? await app.container.database.messageThreadDao().getThreadById(threadId: threadId) else { return }
        await MainActor.run { self.thread = updated }
    }
}

extension ThreadProfileView {
    /// Identifiable `UIImage` wrapper so `.fullScreenCover(item:)` can present the crop step.
    struct CroppingImage: Identifiable {
        let id = UUID()
        let image: UIImage
    }
}
