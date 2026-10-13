//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import PhotosUI
import SharedLogic

fileprivate enum MsgAction { case favorite, reasoning, copy, edit, select, delete }

/// Placeholder body stored for an image-only message: the chat hides it and renders just the
/// attachment. The send path writes it and the bubble matches it, so both sides must agree on
/// the exact literal — as must Android's `IMAGE_ONLY_MESSAGE`.
fileprivate let imageOnlyMessage = "[Image]"

/// Same contract for a document-only message: written by the send path when a document was
/// attached with no text, hidden by [MessageBubble.bodyText], matched by Android's
/// `DOCUMENT_ONLY_MESSAGE`.
fileprivate let documentOnlyMessage = "[Document]"

/// A document staged in the composer before sending: the picked file's display name plus its
/// raw bytes. Bytes stay in memory only for the staging window — on send they go to the shared
/// `LLMService`, which extracts the text and persists just that.
fileprivate struct PickedDocument {
    let fileName: String
    let data: Data
}

/// Chat screen. Mirrors Android's `MessagesScreen`:
/// - Top bar with thread title, "Thinking…" / disclaimer subtitle, overflow (Edit / Favorites / Clear)
/// - Lazy message list with streaming bubble + "Thinking…" indicator
/// - Composer with TextField, image attach button (PhotosPicker + camera), send/stop
/// - Long-press context menu (favorite / reasoning / copy / edit / select / delete)
struct MessagesView: View {
    let threadId: String
    /// A message to open on, from a search hit on the threads screen. The chat still loads the
    /// whole conversation — this only says where to land in it.
    var scrollToMessageId: String? = nil

    /// The row the pusher already had, used as the first value of ``currentThread``.
    ///
    /// `observeThread` needs a frame or two to deliver it, and until it did, the header
    /// rendered against nothing: an initials circle where the avatar belongs and the
    /// placeholder title, both replaced a moment later. Seeding from the caller's copy
    /// means the header is right on the first frame. The flow still owns every value
    /// after that, so an edit made on the profile screen lands here as before.
    ///
    /// Nil is still a legal start — a notification deep-link has no row to hand over —
    /// so the header must render sensibly without one.
    var initialThread: MessageThread? = nil

    /// Pushes the thread's profile editor onto the owning `ThreadsView` stack. Mirrors
    /// Android's `onNavigateToProfile`, which navigates the `"profile/{threadId}"` route —
    /// the profile is a back-stack destination, not a sheet. Nil in the preview, where
    /// there is no stack to push onto.
    var onOpenProfile: (() -> Void)? = nil

    @EnvironmentObject private var app: AppViewModel
    @EnvironmentObject private var litert: LiteRtService
    @Environment(\.dismiss) private var dismiss

    @State private var messages: [Message] = []
    @State private var currentThread: MessageThread?
    /// Seeds ``currentThread`` from ``initialThread`` on the first render — `@State`
    /// cannot take a parameter as its initial value without an explicit `init`, and one
    /// here would have to spell out every other property's default.
    @State private var didSeedThread = false
    @State private var inputText: String = ""
    /// Drives the composer's keyboard. Sending resigns it (Android hides the IME on
    /// both send paths), and so does starting a call: a `fullScreenCover` does not
    /// reliably take first-responder away from the field underneath, and a composer
    /// left focused under the cover re-raises the keyboard over the call.
    @FocusState private var composerFocused: Bool
    @State private var editingMessage: Message? = nil
    @State private var error: String?
    @State private var showModelAlert = false
    @State private var showClearAlert = false
    @State private var showThreadSettings = false
    @State private var showCall = false
    @State private var showMicPermissionAlert = false
    @State private var showFavorites = false
    @State private var reasoningText: String = ""
    @State private var showReasoning = false
    @State private var isSelectionMode = false
    @State private var selectedIds = Set<String>()
    @State private var pendingScrollTo: String?
    /// Marks the opening scroll as done. Without it every later change to the message
    /// count — a reply arriving, a delete — would haul the view back to
    /// [scrollToMessageId], and would do it with the un-animated jump that only the
    /// first positioning should use.
    @State private var didInitialScroll = false
    @State private var bufferedStreaming: String = ""
    @State private var showThinkingExitAlert = false
    /// What to run if the user confirms leaving while the model is thinking.
    /// Android keeps the same handle in `MainActivity.pendingNavAction`.
    @State private var pendingNavAction: (() -> Void)?

    // Image attachment state
    @State private var pendingImages: [Data] = []
    @State private var pendingDocuments: [PickedDocument] = []
    @State private var showPhotoPicker = false
    @State private var showAttachMenu = false
    @State private var showCamera = false
    @State private var showDocumentPicker = false
    // One-shot "Some models does not support multiple attachments" gate. Fires when a second
    // attachment is about to be staged and the user has not yet accepted multi-attach; while the
    // alert is up, `pendingAttachmentAppend` holds the staging that OK commits and Cancel drops.
    @State private var showMultiAttachGate = false
    @State private var pendingAttachmentAppend: (() -> Void)?

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                messageList
                // No rule between the list and the composer: the field is a floating
                // capsule on the same surface, as in Messages, not a bar bolted to the
                // bottom of the conversation.
                if isSelectionMode {
                    selectionBar
                } else {
                    composer
                }
            }
            // Last children, over the composer: the overlay must cover it, and the banner sits
            // above it. Mirrors where Android puts them in `MessagesScreen`'s root `Box`.
            LiteRtWarmUpOverlay()
            VStack {
                Spacer()
                LiteRtWarmUpFailureBanner(settings: effective)
            }
        }
        // `eager: false`: opening a conversation must not pull gigabytes off disk. The model
        // loads on the first send instead, which is where the wait has something to attach to.
        // A changed model or engine still unloads; leaving the screen does not.
        //
        // `resolvedSettings`, not `effective`: nil until the thread row has actually loaded, so
        // the sync never runs against the placeholder that resolves to the *global* model.
        .litertEngineSync(settings: resolvedSettings, eager: false)
        .navigationTitle(Text(verbatim: ""))
        .navigationBarTitleDisplayMode(.inline)
        // The system back stays available — button *and* swipe — while no request is
        // running; hiding it is what disables the swipe, so the hide is conditional.
        // While the LLM is mid-turn the custom chevron below replaces it and routes
        // through `navigateOrWarn`, matching Android's back interception.
        .navigationBarBackButtonHidden(app.isThinking)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button { openProfile() } label: { titleBlock }
                    .buttonStyle(.plain)
            }
            if app.isThinking {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        navigateOrWarn { dismiss() }
                    } label: {
                        Image(systemName: "chevron.backward")
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    startCall()
                } label: {
                    Image(systemName: "phone.fill")
                        .foregroundStyle(.primary)
                }
                .accessibilityLabel("Start call")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { openProfile() } label: {
                        Label("Profile", systemImage: "person.crop.circle")
                    }
                    Button { navigateOrWarn { showThreadSettings = true } } label: {
                        Label("Configure model", systemImage: "slider.horizontal.3")
                    }
                    Button { showFavorites = true } label: {
                        Label("Show favorites", systemImage: "star")
                    }
                    Button(role: .destructive) { showClearAlert = true } label: {
                        Label("Clear history", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
            }
        }
        .onAppear {
            guard !didSeedThread else { return }
            didSeedThread = true
            if currentThread == nil { currentThread = initialThread }
        }
        .task { await observe() }
        .task { await observeThread() }
        .task { await observeDiscarded() }
        .task {
            // Ask for notifications here, not during onboarding: this is the first
            // point where a completion notification could actually be useful, and
            // it is where Android asks. One-shot, gated by the shared flag.
            guard !app.hasSeenNotificationPrompt else { return }
            app.setHasSeenNotificationPrompt(true)
            app.requestNotificationAuthorization()
        }
        .alert("Error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: {
            // Server/API error text.
            Text(verbatim: error ?? "")
        }
        .alert("Model is not configured", isPresented: $showModelAlert) {
            // Android's `navigateBasedOnThread`: a thread that overrides its model is
            // fixed in its own settings; one that inherits the globals goes to the
            // global screen.
            Button("Go to Settings") {
                if currentThread?.useCustomSettings == true {
                    app.requestThreadSettings(threadId: threadId)
                } else {
                    app.requestSettings()
                }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Model is not configured. Go to settings to set up your AI provider.")
        }
        .alert(Text(verbatim: ""), isPresented: $showThinkingExitAlert) {
            Button("OK") {
                app.cancelLLM()
                let action = pendingNavAction
                pendingNavAction = nil
                action?()
            }
            Button("Cancel", role: .cancel) { pendingNavAction = nil }
        } message: {
            Text("Model is thinking, leaving this screen will cancel request")
        }
        // An alert, not an action sheet: this asks a question with a consequence and
        // wants an answer, where the sheets on this screen offer a choice between items.
        // The same shape Android gives it — `ConfirmDialog` in `MessagesScreen`.
        .alert("Clear history", isPresented: $showClearAlert) {
            Button("Delete", role: .destructive) {
                Task { await app.clearThreadMessages(threadId: threadId) }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Delete all messages in this thread?")
        }
        // Android reaches the per-thread model editor straight from this menu
        // (route "model/{threadId}"); presenting it here keeps it one tap away
        // instead of buried under Thread profile.
        .sheet(isPresented: $showThreadSettings) {
            if let currentThread {
                NavigationStack {
                    ThreadSettingsView(thread: currentThread)
                }
            }
        }
        // No `NavigationStack` around it: the call is a screen you are *in*, not one you
        // pushed, and it carries its own centred title the way Android's `AssistantScreen`
        // does. Wrapping it gave it a nav bar with a back chevron that Android has no
        // counterpart for — the red end-call button is the only way out of a call.
        .fullScreenCover(isPresented: $showCall) {
            AssistantView(
                threadId: threadId,
                initialThread: currentThread
            )
        }
        .alert("Permission required", isPresented: $showMicPermissionAlert) {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Microphone and speech recognition permission is required to make calls. You can enable them in app settings.")
        }
        // One-shot multi-attach gate: raised before staging a *second* attachment, exactly like
        // the Data Sharing alert — OK latches `hasAcceptedMultipleAttachments` (never asked
        // again), Cancel drops the pick that triggered it.
        .alert("Multiple attachments", isPresented: $showMultiAttachGate) {
            Button("OK") {
                app.setHasAcceptedMultipleAttachments(true)
                pendingAttachmentAppend?()
                pendingAttachmentAppend = nil
            }
            Button("Cancel", role: .cancel) { pendingAttachmentAppend = nil }
        } message: {
            Text("Some models does not support multiple attachments")
        }
        .sheet(isPresented: $showFavorites) {
            FavoritesSheet(threadId: threadId) { id in
                showFavorites = false
                pendingScrollTo = id
            }
        }
        .sheet(isPresented: $showReasoning) {
            ReasoningSheet(text: reasoningText)
        }
        .fullScreenCover(isPresented: $showCamera) {
            ZStack(alignment: .topLeading) {
                ImagePicker(source: .camera, autoDismiss: false) { uiImage in
                    if let data = AppViewModel.encodeImage(uiImage) {
                        addAttachment { pendingImages.append(data) }
                    }
                    showCamera = false
                }
                .ignoresSafeArea()

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
        .photosPicker(isPresented: $showPhotoPicker, selection: Binding(
            get: { nil },
            set: { item in
                if let item {
                    Task {
                        // Encode like the camera path above. The picker hands over the
                        // library's *original* bytes — a multi-megabyte HEIC/JPEG, which
                        // would then ride Base64-inline in the message row forever and be
                        // re-decoded by Room on every emission. `encodeImage` lands every
                        // attachment at the same 1024 px / 0.8 JPEG the camera produces.
                        // The decode and re-encode run here, off the main actor; bytes
                        // that are not an image (a corrupt asset) are dropped rather than
                        // staged to fail later.
                        if let data = try? await item.loadTransferable(type: Data.self),
                           let image = UIImage(data: data),
                           let encoded = AppViewModel.encodeImage(image) {
                            await MainActor.run {
                                addAttachment { pendingImages.append(encoded) }
                            }
                        }
                    }
                }
            }
        ), matching: .images)
        .sheet(isPresented: $showDocumentPicker) {
            DocumentPicker { url in
                // `asCopy: true` copies the file into our sandbox, and that copy is not a
                // security-scoped resource: `startAccessingSecurityScopedResource()` returns
                // false for it, so the guard written around "asCopy + scope" used to silently
                // drop every pick here. Only stop the scope when it was actually started — the
                // read works either way.
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard let data = try? Data(contentsOf: url) else { return }
                addAttachment {
                    pendingDocuments.append(PickedDocument(fileName: url.lastPathComponent, data: data))
                }
                showDocumentPicker = false
            }
            .ignoresSafeArea()
        }
    }

    // MARK: Header

    /// Avatar + title over a live subtitle, mirroring Android's two-line
    /// `TopAppBar` title. The subtitle carries the AI disclaimer when idle and
    /// turns into "Thinking…" while a response is generating.
    ///
    /// **Claims the full width between the toolbar's leading and trailing groups.**
    /// A principal item sized to its content is re-centred every time that content
    /// changes width, and this one changes twice on launch — the title starts as
    /// "Messages" until the thread row loads, and the subtitle swaps between the
    /// disclaimer, "Thinking…" and "Using a tool…" as a turn runs. The result was a
    /// header that slid left and right for the first second. Filling the space fixes
    /// the origin, so nothing below it can move. It also puts the title where
    /// Android's `TopAppBar` has always had it: leading, after the nav icon.
    private var titleBlock: some View {
        HStack(spacing: 8) {
            // Nothing rather than something wrong: with no row yet (a notification
            // deep-link, which has none to seed from) an `AvatarView` would draw an
            // initials circle for a name it does not know, and swap it for the real
            // avatar a frame later. A blank disc reads as "loading" and does not lie.
            if let thread = currentThread {
                AvatarView(
                    name: thread.title,
                    avatarBytes: thread.avatarBytes,
                    size: 28
                )
            } else {
                Circle()
                    .fill(.quaternary)
                    .frame(width: 28, height: 28)
            }
            VStack(alignment: .leading, spacing: 0) {
                // The user's own thread name, so it must never reach the catalog.
                Text(verbatim: currentThread?.title ?? String(localized: "Messages"))
                    .font(.headline)
                    .lineLimit(1)
                // A running tool takes priority: it is the one state where nothing is
                // streaming and the wait is not the model's. Mirrors Android's MessagesScreen.
                Text(verbatim: subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Resolved here rather than as a ternary inside `Text`, which would pick the
    /// non-localizing initializer for some branches and the catalog for others.
    private var subtitle: String {
        if app.isCallingTool { return String(localized: "Using a tool…") }
        if app.isThinking { return String(localized: "Thinking…") }
        return String(localized: "AI may be inaccurate")
    }

    private func openProfile() {
        navigateOrWarn { onOpenProfile?() }
    }

    // MARK: Streaming bubble state

    /// The live stream with leading whitespace dropped. Models routinely open with `" "` or
    /// `"\n"`, and testing the raw string for emptiness let that first delta replace the
    /// typing dots with a bubble holding nothing — an empty pill. Mirrors Android's
    /// `streamedText` in `MessagesScreen`.
    private var streamedText: String {
        String(app.streamingResponse.drop(while: { $0.isWhitespace }))
    }

    /// Whether a streaming bubble trails the last message. `bufferedStreaming` keeps it alive
    /// across the gap between the stream ending and the assistant row arriving from the
    /// database, so the reply does not blink out; it is shown only while that row is still
    /// missing (last message absent or still the user's).
    ///
    /// Suppressed for [suppressLiveStream]: that engine cannot stream honestly, so the hint
    /// is the typing dots and the message appears whole once it is persisted.
    private var showStreamingBubble: Bool {
        !suppressLiveStream &&
            (!streamedText.isEmpty || (!bufferedStreaming.isEmpty && (messages.last?.isUser ?? true)))
    }

    /// True while the chat must not reveal the stream mid-turn. LiteRT with thinking enabled
    /// sends the reasoning and the answer down the same `onChunk` stream and `extractThinking`
    /// only cuts them apart when the turn ends, so every live delta is at best provisional —
    /// reasoning the finished message then drops into the collapsible trace. Keep the dots
    /// bubble instead. Irrelevant to the remote engine, whose `reasoningContent` arrives on a
    /// separate channel `OAIService` never forwards to `streamingResponse`.
    private var suppressLiveStream: Bool {
        effective.useOnDevice && effective.litertEnableThinking
    }

    /// Id of whatever is currently last in the list. The branches mirror `messageList`'s
    /// own order, so they must stay in step with it.
    ///
    /// The typing bubble is the reason this exists: it is appended *below* the last
    /// message, so scrolling to `messages.last` left it just off the bottom of the
    /// screen — the list stopped following exactly when there was nothing else to look at.
    private var bottomAnchorId: String {
        if showStreamingBubble { return "streaming" }
        if app.isThinking || app.isCallingTool { return "thinking" }
        return messages.last?.id ?? "streaming"
    }

    /// Mirrors Android's buffer-maintaining `LaunchedEffect`: hold the last streamed text
    /// while streaming, release it once the persisted assistant row lands.
    private func updateStreamBuffer() {
        if app.isStreaming && !streamedText.isEmpty {
            bufferedStreaming = streamedText
        } else if !app.isStreaming && !bufferedStreaming.isEmpty {
            if let last = messages.last, !last.isUser {
                bufferedStreaming = ""
            }
        }
    }

    // MARK: Message list

    /// One persisted bubble. Pulled out of ``messageList``'s builder so the row
    /// expression in the (already huge) view builder stays small.
    private func bubble(for message: Message) -> some View {
        MessageBubble(
            message: message,
            isSelectionMode: isSelectionMode,
            isSelected: selectedIds.contains(message.id),
            menuEnabled: !bubblesLocked,
            viewerEnabled: !bubblesLocked,
            onToggleSelect: { toggleSelection(message.id) },
            onAction: { handle($0, for: message) }
        )
        .id(message.id)
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(messages, id: \.id) { message in
                        bubble(for: message)
                    }

                    if showStreamingBubble {
                        MessageBubble(
                            text: streamedText.isEmpty ? bufferedStreaming : streamedText,
                            isUser: false,
                            isStreaming: true
                        )
                        .id("streaming")
                    } else if app.isThinking || app.isCallingTool {
                        // Nothing has streamed yet. With tools that gap is long — the model's
                        // first round and the tool call both finish before a single token of
                        // the answer arrives — so the list would otherwise sit empty for tens
                        // of seconds after sending. Wordless on purpose: what we are waiting
                        // for is said once, in the header. Mirrors Android's TypingBubble.
                        TypingBubble()
                            .id("thinking")
                            .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 12)
            }
            .onChange(of: messages.count) { _, count in
                updateStreamBuffer()
                // The opening position, which is a starting point rather than a movement,
                // so it is set without animation — the same jump Android's
                // `scrollToItem` makes. Animating it meant opening a long conversation
                // visibly flew through every row on the way down, which on the debug
                // stress thread's 1000 messages is a scroll you can sit and watch.
                //
                // Both cases are handled in this one branch — landing on a search hit and
                // landing at the end — so the two cannot fire together and fight over the
                // position on the first load.
                if !didInitialScroll, count > 0 {
                    didInitialScroll = true
                    if let target = scrollToMessageId {
                        proxy.scrollTo(target, anchor: .center)
                    } else {
                        proxy.scrollTo(bottomAnchorId, anchor: .bottom)
                    }
                    return
                }
                // Everything after that is a message arriving while the user is watching,
                // where the movement is the point.
                withAnimation { proxy.scrollTo(bottomAnchorId, anchor: .bottom) }
            }
            .onChange(of: app.streamingResponse) { _, _ in
                updateStreamBuffer()
                withAnimation { proxy.scrollTo(bottomAnchorId, anchor: .bottom) }
            }
            .onChange(of: app.isStreaming) { _, _ in updateStreamBuffer() }
            .onChange(of: app.isThinking) { _, _ in
                // Covers both edges: the typing bubble appearing, which is the row that
                // needs bringing into view, and it going away again, which leaves the
                // conversation pinned to its end so the reply lands in view as it streams.
                withAnimation { proxy.scrollTo(bottomAnchorId, anchor: .bottom) }
            }
            .onChange(of: app.isCallingTool) { _, _ in
                // The bubble survives this transition but the row above it may not, so the
                // list still has to be re-pinned.
                withAnimation { proxy.scrollTo(bottomAnchorId, anchor: .bottom) }
            }
            .onChange(of: pendingScrollTo) { _, id in
                guard let id else { return }
                pendingScrollTo = nil
                withAnimation { proxy.scrollTo(id, anchor: .center) }
            }
        }
        .background(Color(.systemBackground))
    }

    // MARK: Composer

    private var composer: some View {
        VStack(spacing: 0) {
            if !pendingImages.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(pendingImages.enumerated()), id: \.offset) { idx, data in
                            ZStack(alignment: .topTrailing) {
                                if let uiImage = UIImage(data: data) {
                                    Image(uiImage: uiImage)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 60, height: 60)
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                }
                                Button {
                                    pendingImages.remove(at: idx)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(.white, .black.opacity(0.6))
                                        .font(.system(size: 18))
                                }
                                .offset(x: 6, y: -6)
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                }
            }

            // Staged document chips wrap onto rows (Android's `FlowRow`). A long
            // name ellipsizes at the screen edge — not Android's 180dp trim — so
            // the chip grows only as far as the row and the ✕ always stays on it.
            if !pendingDocuments.isEmpty {
                FlowLayout(spacing: 8) {
                    ForEach(Array(pendingDocuments.enumerated()), id: \.offset) { idx, doc in
                        HStack(spacing: 6) {
                            Image(systemName: "doc.text.fill")
                                .font(.system(size: 13))
                            Text(verbatim: doc.fileName)
                                .lineLimit(1)
                            Button {
                                pendingDocuments.remove(at: idx)
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 10, weight: .bold))
                            }
                        }
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 8))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
            }

            // Messages' own shape: a round (+) outside on the left, then one field that
            // carries the send control inside its trailing edge. Bottom-aligned so the
            // two buttons stay put as the field grows into its five lines.
            HStack(alignment: .bottom, spacing: 10) {
                // While an edit is in flight the (+)'s slot carries the cancel control
                // instead, exactly as Android swaps the composer's leading icon for a
                // Close — and the slot exists even on a thread that could never attach.
                if editingMessage != nil {
                    cancelEditButton
                } else {
                    // Documents need no vision model, so the (+) is always offered; the
                    // confirmationDialog below simply omits the image entries on a
                    // thread that cannot attach them. (Android greys the same entries
                    // out instead — same meaning, different presentation.)
                    attachButton
                }

                inputField
            }
            // The system bottom bar's own metrics, taken from the threads screen, which
            // gets them free from `DefaultToolbarItem`: 16pt from the screen edges, and a
            // 44pt control height — the field and the (+) are the same pair of shapes.
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    /// The text field and the send control as one floating capsule, the way Messages
    /// draws it — no rule above the row and no border around the field, so the composer
    /// sits on the same surface as the conversation rather than in a bar of its own.
    ///
    /// A continuous rounded rectangle rather than a `Capsule`: at five lines a capsule's
    /// ends bow out into a stadium, and this keeps the same corner at every height.
    ///
    /// Bottom-aligned, so as the field grows upward the send button stays on the last
    /// line where the cursor is, instead of riding up with the top of the text.
    private var inputField: some View {
        HStack(alignment: .bottom, spacing: 6) {
            TextField("Type a message", text: $inputText, axis: .vertical)
                .lineLimit(1...5)
                .padding(.leading, 16)
                .padding(.vertical, 11)
                .focused($composerFocused)
                .disabled(composerDisabled)

            // 32pt button inset 6pt leaves the 44pt field exactly as tall as the (+)
            // beside it, and holds that inset as the field grows.
            sendControl
                .padding(.trailing, 6)
                .padding(.bottom, 6)
        }
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }

    /// Send, save an edit, or stop while a reply is streaming — one slot, since they are
    /// the same question asked at different moments and Messages never shows two.
    @ViewBuilder
    private var sendControl: some View {
        if app.isStreaming && streamingEnabled {
            Button {
                app.cancelLLM()
            } label: {
                Image(systemName: "stop.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(Color.red, in: Circle())
            }
            .accessibilityLabel("Stop")
        } else if editingMessage != nil {
            // The send slot becomes the edit's Save — a checkmark, as on Android. Saving
            // touches only the database, so it asks nothing of the model configuration.
            Button {
                Task { await send() }
            } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(canSend ? Color.white : Color(.tertiaryLabel))
                    .frame(width: 32, height: 32)
                    .background(canSend ? Color.accentColor : Color(.tertiarySystemFill), in: Circle())
            }
            .disabled(!canSend)
            .accessibilityLabel("Save")
        } else {
            Button {
                Task { await send() }
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(canSend ? Color.white : Color(.tertiaryLabel))
                    .frame(width: 32, height: 32)
                    .background(canSend ? Color.accentColor : Color(.tertiarySystemFill), in: Circle())
            }
            .disabled(!canSend)
            .accessibilityLabel("Send")
        }
    }

    /// The edit-cancel control, drawn in the (+)'s own shape so swapping the two keeps
    /// the row's metrics unchanged — Android's Close lives in the clip's leading slot
    /// the same way.
    private var cancelEditButton: some View {
        Button { cancelEdit() } label: {
            Image(systemName: "xmark")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color(.label))
                .frame(width: 44, height: 44)
                .background(Color(.secondarySystemBackground), in: Circle())
        }
        .accessibilityLabel("Cancel edit")
    }

    private var attachButton: some View {
        Button {
            if composerLive { showAttachMenu = true }
        } label: {
            // A round (+), as in Messages, rather than a bare paperclip: it is the one
            // control on this row that is not the field, and it needs the same weight as
            // the send button opposite it.
            Image(systemName: "plus")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(composerLive ? Color.accentColor : Color(.tertiaryLabel))
                .frame(width: 44, height: 44)
                .background(Color(.secondarySystemBackground), in: Circle())
        }
        .disabled(!composerLive)
        .accessibilityLabel("Attach")
        .confirmationDialog("Attach", isPresented: $showAttachMenu, titleVisibility: .hidden) {
            if effective.visionAvailable {
                Button("Photo Library") { showPhotoPicker = true }
                Button("Take Photo") { showCamera = true }
            }
            Button("Document") { showDocumentPicker = true }
            Button("Cancel", role: .cancel) { }
        }
    }

    private var selectionBar: some View {
        HStack {
            Button("Cancel") { isSelectionMode = false; selectedIds.removeAll() }
            Spacer()
            Text("\(selectedIds.count) selected")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
            Button(role: .destructive) {
                Task {
                    for id in selectedIds {
                        if let msg = messages.first(where: { $0.id == id }) {
                            await app.deleteMessage(msg)
                        }
                    }
                    isSelectionMode = false
                    selectedIds.removeAll()
                }
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemBackground))
    }

    // MARK: Derived state

    /// This thread's Streaming mode choice: replies stream in as they're generated,
    /// or appear whole when a turn completes. With it off the composer stays live
    /// while a turn is in flight — sending cancels the in-flight turn and starts
    /// another (the thinking balloon simply reappears behind the new message) —
    /// and there is no Stop button, because there is nothing partial to stop.
    private var streamingEnabled: Bool {
        effective.streamingMode
    }

    private var canSend: Bool {
        let trimmed = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        return (!trimmed.isEmpty || !pendingImages.isEmpty || !pendingDocuments.isEmpty)
            && (!streamingEnabled || !app.isThinking)
    }

    /// Text input is locked while a turn is in progress in streaming mode, mirroring
    /// Android's `enabled = !isStreaming && !isPreparingModel`. `isStreaming` spans both the
    /// thinking phase and the streaming phase, and `isThinking` is held for the
    /// thinking-only window, so the field can neither be typed into mid-turn nor
    /// pre-filled while the model is still forming a reply. One-shot mode deliberately
    /// skips the lock: the composer must stay live so a new message can supersede the
    /// in-flight turn.
    private var composerDisabled: Bool {
        streamingEnabled && (app.isThinking || app.isStreaming)
    }

    /// Whether the composer is live right now. In streaming mode a turn in flight locks it —
    /// the text field, the (+), and the bubbles' long-press menus (edit/delete would rewrite
    /// history the reply is still being written into). In one-shot mode (Streaming mode off)
    /// it stays live so a new message can supersede the in-flight turn — sending cancels it
    /// and starts another. Mirrors Android's `composerLive`. Every gate is a transient busy
    /// state — documents never depend on vision, so the clip is always *offered* and only
    /// the image entries inside it are vision-gated (in the dialog). There is no
    /// attachment-count cap: the composer accepts any number of attachments, with the
    /// one-shot multi-attach gate guarding the second one (see ``addAttachment``).
    private var composerLive: Bool {
        !streamingEnabled || (!app.isStreaming && !app.isThinking)
    }

    /// Whether a reply is being generated right now — the same thinking/streaming pair the
    /// composer lock reads, but unconditional on Streaming mode: one-shot keeps the composer
    /// itself live so a new message can supersede the in-flight turn, yet the model is still
    /// generating. While it holds, the message bubbles are read-only: tapping an image does
    /// not open the full-size viewer and the long-press menu is withheld with the same guard.
    /// Mirrors Android's `bubblesLocked`.
    private var bubblesLocked: Bool {
        app.isThinking || app.isStreaming
    }

    /// Stages a picked attachment through the one-shot multi-attach gate. The first attachment
    /// stages freely; a *second* one (images and documents share one budget) stages directly
    /// once the gate has been accepted, and otherwise parks the staging behind the gate alert —
    /// OK latches `hasAcceptedMultipleAttachments` and commits it, Cancel drops the pick.
    private func addAttachment(_ append: @escaping () -> Void) {
        if (!pendingImages.isEmpty || !pendingDocuments.isEmpty) && !app.hasAcceptedMultipleAttachments {
            pendingAttachmentAppend = append
            showMultiAttachGate = true
        } else {
            append()
        }
    }

    /// This thread's resolved settings. Recomputed on every redraw rather than stored: the
    /// globals live in `AppViewModel`'s `@Published`s, so a settings change already redraws us.
    ///
    /// Falls back to a placeholder thread while the row loads, which resolves every override to
    /// the global value. That is fine for the things this drives synchronously (the send gate,
    /// the vision check) because they are re-read on the next redraw — but it must never reach
    /// the engine sync. See ``resolvedSettings``.
    private var effective: EffectiveSettings {
        EffectiveSettings.companion.resolve(
            thread: currentThread ?? app.container.threadForId(threadId: threadId),
            global: app.container.settingsManager
        )
    }

    /// [effective], but only once the thread row is really loaded — nil before that.
    private var resolvedSettings: EffectiveSettings? {
        guard let currentThread else { return nil }
        return EffectiveSettings.companion.resolve(
            thread: currentThread,
            global: app.container.settingsManager
        )
    }

    /// The on-device engine is only as configured as the weights on disk: a selection
    /// pointing at a model that was deleted is "Not configured" — the same answer the
    /// Model row and the picker give. One filesystem stat per send; nothing next to it.
    private var modelWeightsPresent: Bool {
        guard effective.useOnDevice else { return true }
        return LiteRtRegistry.model(withId: effective.litertModel)?.isDownloaded ?? false
    }

    /// The call's model half, over an explicit `EffectiveSettings` — the same answer
    /// `modelWeightsPresent` gives, but for the settings the caller resolved (the guard
    /// reads `resolvedSettings`, not the placeholder-backed [effective]).
    private func hasValidModel(_ settings: EffectiveSettings) -> Bool {
        guard settings.isConfigured else { return false }
        guard settings.useOnDevice else { return true }
        return LiteRtRegistry.model(withId: settings.litertModel)?.isDownloaded ?? false
    }

    /// The call's voice half: the external (OpenAI) engine is only as real as its server —
    /// a blank speech endpoint would open a call whose every reply died in "Voice is not
    /// configured". Every other mode — system, on-device — needs nothing here. The voice
    /// may stay empty; that means "the server picks", and only the server URL decides.
    /// Shares the model's one alert: the wording names the model either way, the fix (the
    /// Settings screen the button opens) reaches both.
    private func hasValidVoice(_ settings: EffectiveSettings) -> Bool {
        settings.ttsMode != TtsMode.external || !settings.externalTtsAPI.isEmpty
    }

    // MARK: Actions

    /// Mirrors Android's `navigateOrWarn`: if the LLM is mid-request, surface a
    /// confirmation dialog instead of navigating immediately. Confirming cancels
    /// the in-flight request and then performs *that* navigation — the requested
    /// one, not a hardcoded back, which matters now that Profile, Configure model
    /// and the call button all route through here.
    private func navigateOrWarn(_ action: @escaping () -> Void) {
        if app.isThinking {
            pendingNavAction = action
            showThinkingExitAlert = true
        } else {
            action()
        }
    }

    /// Mirrors Android's call-button gating: settle every permission the call needs
    /// before entering it, and surface an "open Settings" alert if any was denied.
    ///
    /// iOS needs two grants where Android's `RECORD_AUDIO` is one — the microphone and
    /// speech recognition. Both are asked for here, so the call screen never opens
    /// underneath a system prompt the way it did when it requested speech itself.
    /// A denial short-circuits inside `requestAuthorization`, which returns false without
    /// prompting, so this path also covers the already-refused case.
    private func startCall() {
        composerFocused = false
        Task {
            if await SpeechRecognizerService.requestAuthorization() {
                // Both call prerequisites gate here as one — one alert — mirroring
                // Android's `requestMic`. The model half re-checks what `presentCall`'s
                // sync also catches for on-device threads; the voice half has no later
                // checkpoint, because the call screen only surfaces a missing speech
                // server per reply, after the call has already opened. The alert keeps
                // the model's wording either way. Tolerating a nil `resolvedSettings`
                // as `presentCall` does: with no row there is nothing to check yet,
                // and the call screen re-checks the model itself.
                if let settings = resolvedSettings {
                    if !hasValidModel(settings) || !hasValidVoice(settings) {
                        showModelAlert = true
                        return
                    }
                }
                navigateOrWarn { presentCall() }
            } else {
                showMicPermissionAlert = true
            }
        }
    }

    /// Loads the on-device model *before* the call screen appears, mirroring Android's
    /// `startCall`.
    ///
    /// Warm-up belongs to this screen, on both paths that commit the user to an answer:
    /// sending (which loads on the way to the first turn) and starting a call. The call
    /// screen opens the mic as soon as it appears, so presenting it first meant the opening
    /// turn was spoken into a model that had not finished loading, with nothing there to
    /// answer it. Here the wait sits behind this screen's own warm-up overlay and a failure
    /// behind its retry banner.
    private func presentCall() {
        guard let settings = resolvedSettings, settings.useOnDevice else {
            showCall = true
            return
        }
        Task {
            await litert.syncTo(settings)
            if case .ready = litert.initState {
                showCall = true
            } else if litert.initState.failure == nil {
                // Nothing resident and nothing to retry: the model was never installed, which
                // the banner deliberately stays quiet about. Send them where that is fixed.
                showModelAlert = true
            }
        }
    }

    private func observe() async {
        let flow = app.container.database.messageDao().observeMessagesByThread(threadId: threadId)
        let collector = ClosureFlowCollector { value in
            if let list = value as? [Message] {
                Task { @MainActor in self.messages = list }
            }
        }
        _ = try? await flow.collect(collector: collector)
    }

    /// Keeps the header title/avatar in step with
    /// the row, the way Android's `observeThreadById(threadId).collect` does. The
    /// previous one-shot `getThreadById` needed a manual refetch every time the
    /// profile sheet closed, and still went stale on any other edit.
    private func observeThread() async {
        let flow = app.container.database.messageThreadDao().observeThreadById(threadId: threadId)
        let collector = ClosureFlowCollector { value in
            if let thread = value as? MessageThread {
                Task { @MainActor in self.currentThread = thread }
            }
        }
        _ = try? await flow.collect(collector: collector)
    }

    /// On a turn that never persists a row — stop button, error, empty completion — no
    /// assistant message is coming, so `updateStreamBuffer()` can never release the buffer
    /// and the bubble would be left showing partial text nothing backs. Android subscribes
    /// to the same flow in `MessagesScreen`.
    private func observeDiscarded() async {
        let flow = app.container.llmService.responseDiscarded
        let collector = ClosureFlowCollector { value in
            guard let discarded = value as? String, discarded == threadId else { return }
            Task { @MainActor in self.bufferedStreaming = "" }
        }
        _ = try? await flow.collect(collector: collector)
    }

    private func send() async {
        composerFocused = false
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !pendingImages.isEmpty || !pendingDocuments.isEmpty else { return }

        // Saving an edit touches only the database — no engine is involved, so none of
        // the configuration gates below apply (Android's `onSaveEdit` likewise bypasses
        // `checkModelAndSend`).
        if let editingMessage {
            self.editingMessage = nil
            inputText = ""
            await app.updateMessageContent(editingMessage, content: text)
            return
        }

        // `isConfigured`, not the OpenAI fields: it answers for whichever engine this thread
        // resolves to, so a working on-device thread — which has no endpoint and no token — is
        // not reported as unconfigured. Plus the weights: a stored selection whose model was
        // deleted reads as configured to the registry and would then die in the engine — the
        // guard catches it here instead, exactly as the Model row reports it.
        if !effective.isConfigured || !modelWeightsPresent {
            showModelAlert = true
            return
        }

        let images = pendingImages
        let documents = pendingDocuments
        let body = text.isEmpty
            ? (images.isEmpty ? documentOnlyMessage : imageOnlyMessage)
            : text

        inputText = ""
        if !app.keepLastAttachment {
            pendingImages.removeAll()
            pendingDocuments.removeAll()
        }

        do {
            // The picked bytes cross into Kotlin as `KotlinByteArray` (the shared
            // `DocumentInput` type); extraction to text happens inside `LLMService`.
            try await app.sendMessage(
                text: body,
                threadId: threadId,
                images: images,
                documents: documents.map {
                    DocumentInput(fileName: $0.fileName, data: $0.data.toKotlinByteArray())
                }
            )
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func cancelEdit() {
        editingMessage = nil
        inputText = ""
    }

    private func toggleSelection(_ id: String) {
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            selectedIds.insert(id)
        }
    }

    private func handle(_ action: MsgAction, for message: Message) {
        switch action {
        case .favorite:
            Task { await app.setMessageFavorite(message, favorite: !message.favorite) }
        case .reasoning:
            reasoningText = message.reasoning
            showReasoning = true
        case .copy:
            UIPasteboard.general.string = message.content
        case .edit:
            editingMessage = message
            // An image-only/document-only message stores the sentinel as its content,
            // and the bubble never renders it (`bodyText`) — the composer must not show
            // it either. Start from empty text instead; with nothing typed the Save
            // button stays disabled, so an untouched edit can never wipe the message.
            inputText = message.content == imageOnlyMessage || message.content == documentOnlyMessage
                ? "" : message.content
        case .select:
            isSelectionMode = true
            selectedIds.removeAll()
            selectedIds.insert(message.id)
        case .delete:
            Task { await app.deleteMessage(message) }
        }
    }
}

// MARK: - Message bubble

private struct MessageBubble: View {
    let message: Message?
    var text: String = ""
    var isUser: Bool = false
    var isStreaming: Bool = false
    var isSelectionMode: Bool = false
    var isSelected: Bool = false
    /// Whether the bubble's long-press menus (pill and images) may show — withheld while a
    /// reply is being generated, so history cannot be edited or deleted out from under a
    /// reply still arriving. An empty context menu presents nothing on long-press.
    var menuEnabled: Bool = false
    /// Whether tapping an image may open the full-size viewer — the same generating guard
    /// that withholds the menus, so the viewer cannot open over a reply still arriving.
    var viewerEnabled: Bool = false
    var onToggleSelect: () -> Void = {}
    var onAction: (MsgAction) -> Void = { _ in }

    /// The image currently open in the full-size viewer, if any.
    @State private var fullscreenImage: IdentifiableImage?

    /// The text to draw, with the image-only sentinel treated as no text. Android hides it
    /// on both paths (`content != IMAGE_ONLY_MESSAGE`); this used to strip it only when
    /// images were present, so a row whose attachment never persisted showed a literal
    /// "[Image]".
    /// Whitespace-only content collapses to empty too, matching Android's `isNotBlank()`, so
    /// every check below agrees on whether there is anything to draw.
    private var bodyText: String {
        let raw = text.isEmpty ? (message?.content ?? "") : text
        if raw == imageOnlyMessage || raw == documentOnlyMessage
            || raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ""
        }
        return raw
    }

    /// Whether the message has text worth sharing: the same rule as ``bodyText`` — the
    /// image/document sentinels and blank content are excluded, and images are never part
    /// of the share, only the message text is offered.
    private var hasShareableText: Bool {
        let raw = message?.content ?? text
        return raw != imageOnlyMessage && raw != documentOnlyMessage
            && !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// The attachments that actually decode, as thumbnails; nil until the decode has run.
    /// Bytes that fail are dropped, so the filled-in array — not `imageData.count` — is what
    /// ends up on screen.
    ///
    /// Filled in by ``loadThumbnails`` rather than computed in `body`: decoding at 120-point
    /// size inside `body` is what made a thread with photos in it crawl, first because the
    /// decode repeated on every streamed token (fixed by the `MessageImages` cache) and then
    /// because even the once-per-row decode stalled the first frame on the main actor.
    /// Cached by message id in ``MessageImages``, so a row scrolled back into view fills in
    /// from the cache instantly.
    @State private var thumbnails: [(index: Int, image: UIImage)]?

    /// The message's documents, rendered as filename chips under the image grid. Nothing here
    /// can "fail to decode" the way image bytes can — extraction already happened on the way in.
    private var documentChips: [DocumentAttachment] {
        guard let message else { return [] }
        return message.documents
    }

    init(message: Message,
         isSelectionMode: Bool,
         isSelected: Bool,
         menuEnabled: Bool = false,
         viewerEnabled: Bool = false,
         onToggleSelect: @escaping () -> Void,
         onAction: @escaping (MsgAction) -> Void) {
        self.message = message
        self.text = message.content
        self.isUser = message.isUser
        self.isStreaming = false
        self.isSelectionMode = isSelectionMode
        self.isSelected = isSelected
        self.menuEnabled = menuEnabled
        self.viewerEnabled = viewerEnabled
        self.onToggleSelect = onToggleSelect
        self.onAction = onAction
    }

    init(text: String, isUser: Bool, isStreaming: Bool) {
        self.message = nil
        self.text = text
        self.isUser = isUser
        self.isStreaming = isStreaming
    }

    var body: some View {
        // The chrome below (background + padding) is drawn unconditionally, so a bubble with
        // neither text, nor a decodable image, nor a document would render as an empty rounded
        // pill. Draw nothing instead. Mirrors the same guard in Android's BubbleMessageContent.
        //
        // The task hangs off the whole conditional, not either branch: an EmptyView branch
        // must not strand it. An attachment row renders its bubble on the first frame with the
        // grid still empty (`thumbnails` is nil until the decode lands) and fills in a beat
        // later — the decode itself is off the main actor, in ``MessageImages``.
        content
            .task(id: thumbnailsTaskKey) { await loadThumbnails() }
    }

    @ViewBuilder
    private var content: some View {
        if rendersNothing {
            EmptyView()
        } else {
            bubble(thumbnails ?? [])
        }
    }

    /// Whether the row has nothing to draw at all: no text, no documents, and either no
    /// attachments or attachments whose thumbnails all failed to decode. Attachments still
    /// decoding keep the row alive so the grid has somewhere to land.
    private var rendersNothing: Bool {
        guard bodyText.isEmpty && documentChips.isEmpty else { return false }
        guard let message else { return true }
        return message.imageData.isEmpty || thumbnails?.isEmpty == true
    }

    /// Re-runs ``loadThumbnails`` when the attachments behind the row change (an edit can
    /// add or remove one) — the id alone cannot see that, the cache is keyed by index.
    private var thumbnailsTaskKey: String {
        "\(message?.id ?? "")-\(message?.imageData.count ?? 0)"
    }

    /// Fills ``thumbnails`` off the main thread. The synchronous version decoded inside
    /// `body`, so the first frame of a conversation paid an interop copy plus an ImageIO
    /// decode per image row the opening scroll realized — stacked, on the main actor.
    private func loadThumbnails() async {
        guard let message, !message.imageData.isEmpty else { return }
        let decoded = await MessageImages.thumbnails(
            messageId: message.id,
            attachments: message.imageData
        )
        // The row can have scrolled back out of the lazy list while decoding; its state is
        // gone either way, and a fresh appearance restarts the task from the cache.
        if !Task.isCancelled {
            thumbnails = decoded
        }
    }

    private func bubble(_ images: [(index: Int, image: UIImage)]) -> some View {
        // `.center` cross-axis alignment: the star sits beside the bubble, vertically
        // centered — not resting at the bottom next to the timestamp, which is where
        // `.bottom` put it. Side matching Android's `BubbleMessageContent`: the star
        // leads a user bubble (which is right-aligned) and trails an assistant one.
        HStack(alignment: .center, spacing: 4) {
            if isSelectionMode {
                // Leading checkmark circle visible for every bubble in selection mode.
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                    .font(.system(size: 20))
                    .onTapGesture { onToggleSelect() }
                    .padding(.bottom, 2)
            } else if isUser {
                // Push the (star + bubble) group to the trailing edge, star first.
                Spacer(minLength: 0)
                if let message, message.favorite {
                    Image(systemName: "star.fill")
                        .foregroundStyle(.yellow)
                        .font(.system(size: 12))
                }
            }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 2) {
                bubbleContent(images)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 18)
                            .fill(isUser ? Color.accentColor : Color(.secondarySystemBackground))
                    )
                    .foregroundStyle(isUser ? Color.white : Color.primary)
                    .onTapGesture {
                        if isSelectionMode { onToggleSelect() }
                    }
                    .contextMenu {
                        if menuEnabled {
                            messageMenu()
                        }
                    }

                if let message, !isStreaming {
                    Text(verbatim: formatTimestamp(message.createdAt))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if !isUser && !isSelectionMode {
                // Assistant bubbles hug the leading edge: the star trails the bubble,
                // then the spacer absorbs the rest of the row. Nothing at all is drawn
                // for a plain, non-favorited assistant bubble, as on Android.
                if let message, message.favorite {
                    Image(systemName: "star.fill")
                        .foregroundStyle(.yellow)
                        .font(.system(size: 12))
                }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 12)
        .fullScreenCover(item: $fullscreenImage) { item in
            FullscreenImageViewer(image: item.image) { fullscreenImage = nil }
        }
    }

    @ViewBuilder
    private func bubbleContent(_ images: [(index: Int, image: UIImage)]) -> some View {
        let documents = documentChips
        if !images.isEmpty || !documents.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                imageGrid(images)
                documentList(documents)
                if !bodyText.isEmpty {
                    renderedText(bodyText)
                }
            }
        } else {
            renderedText(bodyText)
        }
    }

    /// Filename chips for the message's documents. The extracted text never renders here —
    /// the engine already read it — only the file's identity, mirroring Android's chips.
    private func documentList(_ documents: [DocumentAttachment]) -> some View {
        Group {
            if !documents.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(documents.enumerated()), id: \.offset) { _, doc in
                        HStack(spacing: 6) {
                            Image(systemName: "doc.text.fill")
                                .font(.system(size: 12))
                            Text(verbatim: doc.fileName)
                                .font(.footnote)
                                .lineLimit(2)
                        }
                        .foregroundStyle(isUser ? Color.white : Color.primary)
                    }
                }
            }
        }
    }

    /// Decodes the attachment at full size for the viewer. One decode per tap — the grid itself
    /// never holds anything bigger than a thumbnail.
    private func openFullscreen(_ index: Int) {
        guard let attachments = message?.imageData, index < attachments.count,
              let image = MessageImages.full(attachments[index].toData()) else { return }
        fullscreenImage = IdentifiableImage(image: image)
    }

    /// The bubble's long-press menu, shared by the pill and every image inside it. The
    /// image's own tap gesture used to win the touches over the pill's context menu, so
    /// an image-only message's menu was reachable only from the surrounding border —
    /// each image carries the menu itself now.
    @ViewBuilder
    private func messageMenu() -> some View {
        if let message {
            Button { onAction(.favorite) } label: {
                // Two literals rather than a ternary, so the catalog
                // extractor sees both strings.
                if message.favorite {
                    Label("Unmark favorite", systemImage: "star.slash")
                } else {
                    Label("Mark favorite", systemImage: "star")
                }
            }
            if !message.reasoning.isEmpty {
                Button { onAction(.reasoning) } label: {
                    Label("Show reasoning", systemImage: "brain.head.profile")
                }
            }
            Button { onAction(.copy) } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }
            if hasShareableText {
                ShareLink(item: message.content) {
                    Label("Share...", systemImage: "square.and.arrow.up")
                }
            }
            Button { onAction(.edit) } label: {
                Label("Edit", systemImage: "square.and.pencil")
            }
            Button { onAction(.select) } label: {
                Label("Select", systemImage: "checkmark.circle")
            }
            Button(role: .destructive) { onAction(.delete) } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    private func imageGrid(_ images: [(index: Int, image: UIImage)]) -> some View {
        HStack(spacing: 4) {
            ForEach(images, id: \.index) { item in
                Image(uiImage: item.image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 120, height: 120)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("Photo")
                    .accessibilityAddTraits(.isButton)
                    // Tap opens the full-size viewer, as on Android. Selection
                    // mode keeps its bubble-wide tap-to-select behaviour instead.
                    // The menu rides on the image itself (see `messageMenu`), so a
                    // long press lands here rather than dying against the tap
                    // gesture before the pill's context menu can see it.
                    .onTapGesture {
                        if isSelectionMode { onToggleSelect() }
                        else if viewerEnabled { openFullscreen(item.index) }
                    }
                    .contextMenu {
                        if menuEnabled {
                            messageMenu()
                        }
                    }
            }
        }
    }

    /// The message text, drawn without native selection: the long-press belongs to the
    /// context menu (Copy/Share cover it), and `.textSelection(.enabled)` would fight it
    /// for the gesture — raising the selection loupe even while `menuEnabled` has the
    /// menu withheld mid-turn.
    @ViewBuilder
    private func renderedText(_ content: String) -> some View {
        if isUser || isStreaming {
            // The message body: whatever the user typed or the model streamed back.
            // `verbatim` is what guarantees it is never looked up in the catalog.
            Text(verbatim: content)
        } else {
            let attributed: AttributedString = {
                if let parsed = try? AttributedString(
                    markdown: content,
                    options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
                ) {
                    return parsed
                }
                return AttributedString(content)
            }()
            Text(attributed)
        }
    }
}

// MARK: - Favorites sheet

/// The chat's timestamp format (`MMM d, HH:mm`), shared by the bubbles and the
/// favorites sheet.
fileprivate func formatTimestamp(_ millis: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    let formatter = DateFormatter()
    formatter.dateFormat = "MMM d, HH:mm"
    return formatter.string(from: date)
}

private struct FavoritesSheet: View {
    let threadId: String
    let onScrollTo: (String) -> Void

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var messages: [Message] = []
    @State private var searchText = ""

    /// The favorites as filtered by ``searchText``. `localizedStandardContains` rather
    /// than `contains`: the case- and diacritic-insensitive comparison the user expects
    /// from a search field, as in the threads list.
    private var visibleMessages: [Message] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return messages }
        return messages.filter { $0.content.localizedStandardContains(query) }
    }

    private var isSearching: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            Group {
                if messages.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "star")
                            .font(.system(size: 44))
                            .foregroundStyle(.secondary)
                        Text("No favorites yet").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if visibleMessages.isEmpty {
                    // A search that matched nothing is not an empty sheet: this is the
                    // standard "No Results for …", not the star and its promise.
                    ContentUnavailableView.search(text: searchText)
                } else {
                    List {
                        ForEach(visibleMessages, id: \.id) { message in
                            Button {
                                onScrollTo(message.id)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: message.content)
                                        .foregroundStyle(.primary)
                                        .lineLimit(4)
                                        .multilineTextAlignment(.leading)
                                    // The chat rows carry this too — Android's sheet reuses
                                    // the whole bubble, timestamp included.
                                    Text(verbatim: formatTimestamp(message.createdAt))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) {
                                    Task { await app.setMessageFavorite(message, favorite: false) }
                                } label: {
                                    Label("Unmark favorite", systemImage: "star.slash")
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Favorites")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(role: .confirm) { dismiss() } }
            }
            .searchable(text: $searchText, prompt: Text("Search"))
        }
        .task { await observe() }
    }

    private func observe() async {
        let flow = app.container.database.messageDao().observeFavoriteMessages()
        let collector = ClosureFlowCollector { value in
            if let list = value as? [Message] {
                Task { @MainActor in
                    self.messages = list.filter { $0.threadId == threadId }
                }
            }
        }
        _ = try? await flow.collect(collector: collector)
    }
}

// MARK: - Reasoning sheet

private struct ReasoningSheet: View {
    let text: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                // The model's raw reasoning trace.
                Text(verbatim: text).padding()
            }
            .navigationTitle("Reasoning")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(role: .confirm) { dismiss() } }
            }
        }
    }
}

// MARK: - Image picker (camera or library via UIImagePicker)

struct ImagePicker: UIViewControllerRepresentable {
    enum Source { case camera, library }
    let source: Source
    /// When `true` (default) the picker dismisses itself after a pick/cancel — the
    /// historical behavior used by callers that present it inside a `.sheet`. Set to
    /// `false` when the caller drives dismissal itself (e.g. a `.fullScreenCover`
    /// that needs to dismiss *and then* present a follow-on sheet, avoiding a
    /// double-dismissal).
    var autoDismiss: Bool = true
    let onPick: (UIImage) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.sourceType = source == .camera ? .camera : .photoLibrary
        picker.allowsEditing = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {
        context.coordinator.autoDismiss = autoDismiss
    }

    func makeCoordinator() -> Coordinator { Coordinator(autoDismiss: autoDismiss, onPick: onPick) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        var autoDismiss: Bool
        let onPick: (UIImage) -> Void
        init(autoDismiss: Bool, onPick: @escaping (UIImage) -> Void) {
            self.autoDismiss = autoDismiss
            self.onPick = onPick
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]
        ) {
            if let image = info[.originalImage] as? UIImage {
                onPick(image)
            }
            if autoDismiss { picker.dismiss(animated: true) }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            if autoDismiss { picker.dismiss(animated: true) }
        }
    }
}

/// A wrapping row layout — SwiftUI's missing counterpart of Compose's `FlowRow`,
/// which Android's composer uses for the staged-document chips. Children run left
/// to right; one that doesn't fit the remaining width starts a new row. Children
/// are measured against the row's width, so an over-long child (a long filename)
/// truncates to the row instead of sizing past it — fixed-size siblings beside it
/// (a delete button) keep their place.
private struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let childProposal: ProposedViewSize = proposal.width != nil
            ? ProposedViewSize(width: maxWidth, height: nil)
            : .unspecified
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(childProposal)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            totalWidth = max(totalWidth, x - spacing)
        }
        // Report the full proposed width when there is one: the composer's VStack
        // centers narrower children, which left a single row of chips floating
        // mid-screen instead of hugging the leading edge.
        return CGSize(width: proposal.width ?? totalWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let childProposal = ProposedViewSize(width: bounds.width, height: nil)
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(childProposal)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: childProposal)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

#Preview {
    NavigationStack {
        MessagesView(threadId: "preview")
    }
    .environmentObject(AppViewModel())
}
