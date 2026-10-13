//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import SharedLogic

/// How much of a message the card preview considers, before it is wrapped and ellipsized.
fileprivate let previewChars = 200

/// Threads list screen. Mirrors the Android `ThreadsScreen` layout:
/// - NavigationStack with a gear button to Settings (which is where Contact /
///   Privacy / Terms live on both platforms)
/// - List of threads with avatar, title, last-message preview
/// - Swipe-to-delete with confirmation
///
/// The chrome is Reminders' on modern iOS: the gear has the navigation bar to itself, and
/// compose is a floating `plus.circle.fill` pill over the bottom-right of the list, opening
/// the create sheet directly — there are no role templates to choose from. Android has a FAB
/// and no search at all.
struct ThreadsView: View {
    @EnvironmentObject private var app: AppViewModel

    /// The one template left — the Virtual Character default, resolved once in the app's
    /// seed language so the create sheet pre-fills the same values on every open and they
    /// cannot drift from Android's (`NewThreadDefaults` in shared code).
    private static let defaults =
        NewThreadDefaults.shared.forLanguage(locale: AppViewModel.resolvedLocale)

    @State private var threads: [MessageThread] = []
    /// Whether the thread flow has delivered anything yet. The list starts out empty and
    /// is filled a frame or two later by the database, so without knowing whether that has
    /// happened the empty-state note flashes on every launch — including for users who
    /// have threads. Mirrors Android's `threadsLoaded` in `ThreadsScreen`.
    @State private var threadsLoaded = false
    /// Drives the rows' relative timestamps. They are derived from "now", so without a
    /// ticker a row keeps reading "1 min. ago" until something unrelated redraws it. One
    /// minute is the resolution `RelativeDateTimeFormatter` renders at here — anything
    /// finer would redraw without changing a single label. Mirrors Android's `now` in
    /// `ThreadsScreen`.
    @State private var now = Date()
    @State private var lastMessages: [String: Message] = [:]
    @State private var threadToDelete: MessageThread?
    /// Whether the create sheet (pre-filled with the Virtual Character default) is up.
    @State private var showCreateSheet = false
    /// A thread created by the sheet, waiting for its dismissal to finish before the
    /// push — recorded here because the sheet's own callback runs before `onDismiss`.
    @State private var pendingCreatedThreadId: String?
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            content
                // The floating compose pill, Reminders-style: laid over the list's
                // bottom-right corner rather than parked in a toolbar, so it stays a
                // thumb's reach away and the navigation bar keeps only the gear.
                .overlay(alignment: .bottomTrailing) {
                    composeButton
                        .padding(.trailing, 16)
                        .padding(.bottom, 8)
                }
                .navigationTitle("Messages")
                .toolbar {
                    // Trailing, and the only thing in the navigation bar — compose no
                    // longer takes a toolbar slot at all.
                    ToolbarItem(placement: .topBarTrailing) {
                        NavigationLink(value: "settings") {
                            Image(systemName: "gearshape.fill")
                        }
                    }
                }
                .navigationDestination(for: String.self) { route in
                    if route == "settings" {
                        SettingsView()
                    } else {
                        // The row is already in hand here; handing it over spares the chat
                        // header a frame of placeholder avatar and title.
                        MessagesView(
                            threadId: route,
                            initialThread: threadsById[route],
                            // Mirrors Android's `onNavigateToProfile` → `navigate("profile/$threadId")`:
                            // the profile editor is a pushed destination on this same stack, not a sheet.
                            onOpenProfile: { path.append(ThreadProfileRoute(threadId: route)) }
                        )
                    }
                }
                .navigationDestination(for: ThreadProfileRoute.self) { route in
                    ThreadProfileView(
                        threadId: route.threadId,
                        initialThread: threadsById[route.threadId]
                    )
                }
                .navigationDestination(for: ThreadSettingsRoute.self) { route in
                    // The routing target of the "Model is not configured" alert for a
                    // thread that overrides its model — pushed here because neither the
                    // chat nor the call screen owns the navigation stack.
                    if let thread = threadsById[route.threadId] {
                        ThreadSettingsView(thread: thread)
                    }
                }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                now = Date()
            }
        }
        .task { await observeThreads() }
        .task { await observeLastMessages() }
        .onChange(of: app.pendingDeepLinkThreadId) { _, _ in
            handlePendingDeepLink()
        }
        .onChange(of: app.pendingSettingsRequest) { _, requested in
            guard requested else { return }
            app.pendingSettingsRequest = false
            // Append rather than reset: Settings lands *on top of* whatever the user
            // was doing, matching Android's `navController.navigate("settings")`, so
            // backing out returns to the conversation.
            path.append("settings")
        }
        .onChange(of: app.pendingThreadSettingsRequest) { _, threadId in
            guard let threadId else { return }
            app.pendingThreadSettingsRequest = nil
            path.append(ThreadSettingsRoute(threadId: threadId))
        }
        .onAppear {
            // Cold-start path: if the app was launched from a notification tap,
            // AppDelegate sets `pendingDeepLinkThreadId` before this view appears.
            handlePendingDeepLink()
        }
        // The created thread is pushed from `onDismiss`, not from the callback: pushing
        // while the sheet's dismissal is still animating is what made the pushed chat
        // show the system back button next to its own — the back-hidden flag races the
        // transition. `onDismiss` fires only once dismissal has actually completed, so
        // no timed delay is needed either.
        .sheet(isPresented: $showCreateSheet, onDismiss: {
            guard let id = pendingCreatedThreadId else { return }
            pendingCreatedThreadId = nil
            path.append(id)
        }) {
            CreateThreadSheet(
                initialTitle: Self.defaults.name,
                initialPrompt: Self.defaults.prompt,
                greeting: Self.defaults.greeting,
                existingTitles: Set(threads.map { $0.title.lowercased() })
            ) { newThreadId in
                pendingCreatedThreadId = newThreadId
            }
            .presentationDetents([.medium, .large])
        }
        .alert("Delete?", isPresented: Binding(
            get: { threadToDelete != nil },
            set: { if !$0 { threadToDelete = nil } })
        ) {
            Button("Delete", role: .destructive) {
                if let thread = threadToDelete {
                    Task { await app.deleteThread(thread) }
                    threadToDelete = nil
                }
            }
            Button("Cancel", role: .cancel) { threadToDelete = nil }
        } message: {
            Text("Are you sure you want to delete this thread and all its messages?")
        }
    }

    /// Compose button: the one way in, on an empty screen as much as a full one — the
    /// empty state is a note, not a second entry point. There are no role templates to
    /// choose from any more; the button opens `CreateThreadSheet` directly, already
    /// pre-filled with the Virtual Character default.
    ///
    /// Reminders' "New Reminder" pill, floating over the list's bottom-right corner:
    /// `plus.circle.fill` + label in the accent tint, on a material capsule so it stays
    /// legible over scrolling rows.
    ///
    /// **A plain screen-owned `Bool`, not a toolbar `Menu`.** A toolbar `Menu` hands UIKit a
    /// deferred menu element that SwiftUI resolves on tap; an item that has lived
    /// through a push and a pop (create a thread, land in the chat, come back) resolves
    /// against the torn-down hierarchy, so the provider never answers. UIKit waits its three
    /// seconds — `Result accumulator timeout: 3.0, exceeded` in the console — and then
    /// presents the menu anchored to the top of the screen instead of to the button. A sheet
    /// owned by the screen, driven by a plain `Bool`, has no anchor to lose; `MessagesView`'s
    /// attach button already works this way.
    private var composeButton: some View {
        Button {
            showCreateSheet = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 20))
                Text("New Conversation")
                    .fontWeight(.medium)
            }
            .foregroundStyle(Color.accentColor)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.regularMaterial, in: Capsule())
            .shadow(color: .black.opacity(0.15), radius: 8, y: 2)
        }
        .accessibilityLabel("New Conversation")
    }

    @ViewBuilder
    private var content: some View {
        if !threadsLoaded {
            // Nothing at all until the first delivery: "you have no conversations" is a
            // statement of fact, and until the flow has answered we do not have the fact.
            Color(.systemBackground)
        } else if threads.isEmpty {
            emptyState
        } else {
            List {
                Section { threadRows }
            }
            .listStyle(.plain)
        }
    }

    /// The conversations themselves, headerless — there is nothing to tell them apart
    /// from any more.
    @ViewBuilder
    private var threadRows: some View {
        ForEach(Array(threads.enumerated()), id: \.element.threadId) { index, thread in
            // A `Button` into `path`, not a `NavigationLink`: the link owns its disclosure
            // chevron and centres it vertically, and this row wants the chevron up on the
            // title line beside the timestamp. `ThreadRow` draws its own; navigation is the
            // same push either way, and the deep-link handler already appends to `path` by
            // hand.
            Button {
                path.append(thread.threadId)
            } label: {
                ThreadRow(thread: thread, lastMessage: lastMessages[thread.threadId], now: now)
            }
            .buttonStyle(.plain)
            // Only the *first* row's top edge, which is the line that sat between the
            // "Messages" title and the list — Messages has nothing there either.
            //
            // Applying this to every row hid every separator: the line between two rows is
            // the lower one's top edge, so hiding `.top` throughout leaves a list with no
            // rules at all rather than one missing its first.
            .listRowSeparator(index == 0 ? .hidden : .automatic, edges: .top)
            // Protected threads are deletable too — protection covers editing the
            // name/prompt/avatar, not keeping the thread around. Reset re-seeds the default one.
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                // Not `role: .destructive`: inside a List that role also plays the
                // system's row-removal animation, which would slide the row away
                // before the confirm alert appears. A plain action with a red tint
                // keeps the look and leaves the row where it is until Delete is
                // confirmed.
                Button {
                    threadToDelete = thread
                } label: { Label("Delete", systemImage: "trash") }
                .tint(.red)
            }
        }
    }

    /// Threads by id, for putting a hit's thread behind its row.
    private var threadsById: [String: MessageThread] {
        Dictionary(uniqueKeysWithValues: threads.map { ($0.threadId, $0) })
    }

    /// Shown in place of the thread list while there is nothing to list. Mirrors
    /// Android's `EmptyThreadsNote`: a tonal circle holding a pair of speech bubbles,
    /// and one line saying what would go here.
    ///
    /// No call-to-action button. Android has none, and the compose control is already
    /// in the bottom bar a thumb's width away — a second entry point that exists only
    /// while the list is empty is one the user never sees again.
    private var emptyState: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 96, height: 96)
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 40))
                    .foregroundStyle(Color.accentColor)
            }
            Text("Once you start a new conversation, you'll see it listed here")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }

    private func observeThreads() async {
        let flow = app.container.database.messageThreadDao().observeAllThreads()
        let collector = ClosureFlowCollector { value in
            if let list = value as? [MessageThread] {
                Task { @MainActor in
                    self.threads = list
                    self.threadsLoaded = true
                }
            }
        }
        _ = try? await flow.collect(collector: collector)
    }

    private func observeLastMessages() async {
        let flow = app.container.database.messageDao().observeLastMessagePerThread()
        let collector = ClosureFlowCollector { value in
            if let list = value as? [Message] {
                Task { @MainActor in
                    self.lastMessages = Dictionary(
                        uniqueKeysWithValues: list.compactMap {
                            guard let tid = $0.threadId else { return nil }
                            return (tid, $0)
                        }
                    )
                }
            }
        }
        _ = try? await flow.collect(collector: collector)
    }

    /// Routes a notification tap to the matching thread. Replaces the entire
    /// navigation stack so back-button always returns to the threads list, not
    /// whatever the user had open before the tap. Mirrors Android's
    /// `Intent.FLAG_ACTIVITY_CLEAR_TOP` semantics in `LlmForegroundService`.
    private func handlePendingDeepLink() {
        guard let threadId = app.pendingDeepLinkThreadId else { return }
        app.pendingDeepLinkThreadId = nil
        path = NavigationPath()
        path.append(threadId)
    }
}

// MARK: - Thread row

private struct ThreadRow: View {
    let thread: MessageThread
    let lastMessage: Message?
    /// The moment to measure ``timeAgo`` against, ticked once a minute by the screen.
    /// Passed in rather than read here so every row names the same "now", and so the
    /// label actually changes as time passes — `Date()` inside the row is only ever
    /// correct at the instant it is drawn.
    let now: Date

    /// Every run of whitespace collapses to one space. The preview is two lines now, and a
    /// message's own newlines would spend one of them on a blank — the text should wrap
    /// because it is long, not because it was formatted. Mirrors Android's `WHITESPACE_RUN`.
    private var preview: String {
        let flattened = (lastMessage?.content ?? "")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        return flattened.isEmpty ? String(localized: "No messages") : String(flattened.prefix(previewChars))
    }

    /// "5 min. ago" beside the title, as in Messages. Nil for a thread whose only content is
    /// the greeting-less empty state, where there is no moment to name.
    ///
    /// Relative rather than a clock time: these conversations are read in terms of how long ago
    /// they were, and a relative string needs no separate "today vs earlier" rule. The formatter
    /// localizes itself, so the row carries no date strings of its own.
    private var timeAgo: String? {
        guard let createdAt = lastMessage?.createdAt else { return nil }
        let date = Date(timeIntervalSince1970: Double(createdAt) / 1000)
        return Self.relativeFormatter.localizedString(for: date, relativeTo: now)
    }

    /// Built once: `RelativeDateTimeFormatter` is expensive to create and every row wants one.
    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        // "5 min. ago" rather than `.full`'s "5 minutes ago" — the title has to fit beside it.
        formatter.unitsStyle = .short
        return formatter
    }()

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(
                name: thread.title,
                avatarBytes: thread.avatarBytes,
                size: 44
            )
            VStack(alignment: .leading, spacing: 2) {
                // Title and timestamp share the first line, the preview gets the next two —
                // Messages' arrangement. Baseline-aligned so the smaller timestamp sits on the
                // title's line rather than floating in the middle of it.
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    // Both are user/model content: the thread's name and the last
                    // message. `preview` already resolved its own empty-state string.
                    Text(verbatim: thread.title)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    if let timeAgo {
                        Text(verbatim: timeAgo)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            // Never truncated: a title long enough to crowd it is the thing
                            // that should give way.
                            .layoutPriority(1)
                    }
                    // Hand-drawn, because the row is a `Button` rather than a `NavigationLink`
                    // — see the call site. Sized and coloured to read as the system disclosure
                    // indicator it replaces.
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .layoutPriority(1)
                }
                Text(verbatim: preview)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Create thread sheet

/// A profile push: which thread's profile editor to open on top of its chat. Its own
/// route type so the pushed `ThreadProfileView` is told apart from a thread push in
/// the path — Android gives it one of its own too (`"profile/{threadId}"`).
private struct ThreadProfileRoute: Hashable {
    let threadId: String
}

/// A thread's model settings, pushed on top of its chat — the target the not-configured
/// alert routes to when the thread overrides its model. Android's `"model/{threadId}"`.
private struct ThreadSettingsRoute: Hashable {
    let threadId: String
}

/// Name + Prompt editor for a new thread. Every open comes pre-filled with the Virtual
/// Character default (from `NewThreadDefaults`, via `ThreadsView.defaults`); both fields
/// are editable.
struct CreateThreadSheet: View {
    let initialTitle: String
    let initialPrompt: String
    let greeting: String
    let existingTitles: Set<String>
    let onCreated: (String) -> Void

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var title: String = ""
    @State private var promptText: String = ""
    @State private var isCreating = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Enter the name of the role", text: $title)
                        .textInputAutocapitalization(.words)
                    if nameAlreadyExists {
                        Text("This name already exists")
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }

                Section("Prompt") {
                    TextEditor(text: $promptText)
                        .frame(minHeight: 160)
                }
            }
            .navigationTitle("New Conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .confirm) {
                        Task { await create() }
                    }
                    .disabled(!canCreate || isCreating)
                }
            }
            .onAppear {
                if title.isEmpty { title = initialTitle }
                if promptText.isEmpty { promptText = initialPrompt }
            }
        }
    }

    private var canCreate: Bool {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = promptText.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && !p.isEmpty && !nameAlreadyExists
    }

    private var nameAlreadyExists: Bool {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !t.isEmpty else { return false }
        return existingTitles.contains(t)
    }

    private func create() async {
        guard !isCreating else { return }
        isCreating = true
        defer { isCreating = false }

        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = promptText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !p.isEmpty else { return }

        let threadId = await app.insertThread(title: t, prompt: p)
        if !greeting.isEmpty {
            // The role's own greeting, frozen in the language it was seeded in. It used
            // to be resolved against the live system locale, so changing the system
            // language after onboarding left a Russian character greeting in English.
            await app.insertGreeting(threadId: threadId, text: greeting)
        }
        await MainActor.run {
            onCreated(threadId)
            dismiss()
        }
    }
}

#Preview {
    ThreadsView().environmentObject(AppViewModel())
        .environmentObject(AppViewModel.shared.litert)
}

// MARK: - Create thread sheet
