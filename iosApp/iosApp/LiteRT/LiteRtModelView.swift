//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SharedLogic
import SwiftUI

/// Model picker and installer for on-device inference — Android's `LitertModelScreen`,
/// as a pushed screen rather than the sheet it used to be.
///
/// - Tap a downloaded card: the selection is written **immediately** (no Save to press —
///   Android writes on tap too; the tick moves and the user backs out whenever they like).
/// - Tap a card without weights: "Download required", then the download takes over with
///   ``LiteRtDownloadSheet`` — blocking, with progress, speed and a Cancel that is a pause
///   (the bytes stay, the next attempt resumes). A finished download selects the model
///   itself, exactly as on Android.
/// - "+" in the toolbar opens ``AddModelByUrlSheet``; the list itself is only the catalogue.
/// - Delete is a swipe, offered for a downloaded model and for any URL-added one — a
///   failed or cancelled custom download must not strand its card (the same rule the
///   Android screen applies to its long-press menu).
struct LiteRtModelView: View {
    /// The persisted selection this screen opens on.
    let selectedModelId: String
    /// The picked model, written on every tap of a downloaded card — never staged. `nil`
    /// clears the selection, which happens when the selected model's weights are deleted:
    /// Android's `onSelect("")` behind the same seam.
    let onSelect: (LiteRtModel?) -> Void

    @EnvironmentObject private var app: AppViewModel
    @EnvironmentObject private var litert: LiteRtService

    @State private var pendingModel: LiteRtModel?
    @State private var showDownloadAlert = false
    @State private var showAddUrlSheet = false
    /// The model whose transfer owns the screen, while the blocking download sheet is up.
    @State private var downloadSheetModel: LiteRtModel?
    /// Why the transfer ended, when it ended badly. Nil while it runs.
    @State private var downloadError: String?
    /// The model whose gated download was refused, when it was: the sheet closes and this
    /// alert opens instead — the same OK-only notice Android raises.
    @State private var gatedNotice: String?
    /// A gated refusal that arrived while the download sheet was up. The sheet has to
    /// finish dismissing before the alert can present — UIKit drops an alert aimed at a
    /// controller that is still dismissing a sheet ("already presenting … cannot add
    /// handler") — so it waits here and is raised from the sheet's `onDismiss`.
    @State private var pendingGatedNotice: String?
    /// Which models have weights on disk. State, not a `body` filesystem read: the answer comes
    /// from disk, which SwiftUI cannot observe, and statting a directory per card per redraw is
    /// not free either.
    @State private var downloadedIds: Set<String> = []
    /// The user's own URL-added models. State alongside the built-ins because Kotlin's
    /// `LitertModels` object is not observable from SwiftUI — adding or removing one has to
    /// recompose the list, so the list is built from this rather than from `all` directly.
    @State private var customModels: [LiteRtModel] = []

    private var models: [LiteRtModel] {
        // The RAM gate applies to the built-in catalogue only — a model the user added
        // by URL is their own decision, so it is always listed.
        LiteRtRegistry.builtinModels.filter(fitsDeviceMemory) + customModels
    }
    private var isDownloading: Bool { litert.downloadState != nil }

    /// The device's memory in MB as `ProcessInfo` reports it. The total physical
    /// memory, which is always under the marketed figure (an 8 GB iPhone reports
    /// ~7.67 GB) — the "RAM minus the system" budget the picker compares against.
    private var deviceMemoryMB: Int {
        Int(ProcessInfo.processInfo.physicalMemory / 1_048_576)
    }

    /// Whether this checkpoint can run on the device.
    ///
    /// Consulted for the built-in catalogue only: a model the user added by URL is their
    /// own call and is always listed, whatever its estimate says. `minDeviceMemoryGb`
    /// speaks in marketed-GB, while `deviceMemoryMB` is usable memory, so the requirement
    /// is checked one step down: a model that "needs 4 GB" must find more than 3 GB + 1 MB
    /// free — which is exactly what a real 4 GB phone reports — and is hidden below that.
    /// A "needs 6 GB" model therefore wants more than 5 GB + 1 MB (a real 6 GB phone
    /// reports ~5856 MB); the `+ 1` stops a phone that reports exactly the requirement
    /// from passing.
    private func fitsDeviceMemory(_ model: LiteRtModel) -> Bool {
        deviceMemoryMB > (model.minDeviceMemoryGb - 1) * 1024 + 1
    }

    var body: some View {
        // A `List`, purely so the cards can carry `swipeActions` — swipe-to-delete only
        // exists inside a list. The card look is kept by hiding the separators and the row
        // background, so the rows still read as free-standing cards rather than table rows.
        List {
            ForEach(models) { model in
                card(for: model)
                    .modifier(PlainRow(top: 8, bottom: 8))
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        // Downloaded: the weights go. URL-added without weights: the card
                        // goes (and any partial file with it). Never mid-download — removing
                        // the directory mid-transfer leaves the runtime writing into nothing.
                        //
                        // Not `role: .destructive`: inside a List that role also plays the
                        // system's row-removal animation, so a built-in card — which stays,
                        // only its weights go — vanished and snapped back on the next
                        // render. A plain action with a red tint keeps the look and leaves
                        // the row where it is.
                        if downloadedIds.contains(model.id) || model.isCustom, litert.downloadState == nil {
                            Button {
                                delete(model)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                            .tint(.red)
                        }
                    }
            }

            Text("Models run entirely on this device — nothing is sent to a server. By using a model you agree to its license; see huggingface.co for its terms.")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .modifier(PlainRow(top: 20, bottom: 20))
        }
        .listStyle(.plain)
        .navigationTitle("On-device model")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Adding a model by URL is the exception, not a catalogue entry, so it lives
            // in the top bar rather than the list — the "+" of Android's top bar.
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showAddUrlSheet = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(Text("Download from URL"))
            }
        }
        // A multi-gigabyte download outlasts the display timeout, and the transfer does not
        // survive the app being suspended.
        .keepScreenAwake(isDownloading)
        // A download can finish while the app is suspended — the transfer belongs to
        // `nsurlsessiond`, not to us — so the card has to re-read the disk on the way back in
        // rather than only when it first appears.
        .onChange(of: app.isAppForegrounded) { _, foregrounded in
            if foregrounded { refreshDownloaded() }
        }
        .onAppear {
            customModels = LiteRtModelsInterop.customModels
            refreshDownloaded()
        }
        .sheet(isPresented: $showAddUrlSheet) {
            AddModelByUrlSheet(token: app.huggingFaceToken) { model in
                // Persisted first, so the card exists even if the download is refused or
                // fails — it stays in the list, ready for its own retry.
                app.container.settingsManager.addCustomLitertModel(model: model)
                customModels = LiteRtModelsInterop.customModels
                showAddUrlSheet = false
                // Straight into the download, as on Android: the sheet's Download button
                // already carried the confirmation a card tap raises.
                startDownload(LiteRtModel(model))
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $downloadSheetModel, onDismiss: {
            // The dismissal has finished, so an alert can present now. This is the only
            // route a gated refusal takes to the screen — see `pendingGatedNotice`.
            if let pending = pendingGatedNotice {
                pendingGatedNotice = nil
                gatedNotice = pending
            }
        }) { model in
            LiteRtDownloadSheet(
                model: model,
                failure: downloadError,
                onCancel: {
                    // Pause, not discard: the bytes that landed stay, and the next
                    // attempt resumes from them.
                    litert.cancelDownload()
                    downloadSheetModel = nil
                    downloadError = nil
                    refreshDownloaded()
                },
                onRetry: {
                    downloadError = nil
                    startDownload(model)
                },
                onClose: {
                    downloadSheetModel = nil
                    downloadError = nil
                    refreshDownloaded()
                }
            )
            // The compact sheet's detents, where every sibling on this screen sets them —
            // plus a small one (`.fraction`, the SDK's smallest step), which a progress
            // sheet earns: it is a status readout, and the less of the catalogue it covers
            // the better. The drag they enable is what `interactiveDismissDisabled` below
            // then vetoes mid-transfer, so the sheet may be resized but not dropped.
            .presentationDetents([.fraction(0.3), .medium, .large])
            // Dismissable exactly when the transfer is not running — the mirror of
            // Android's sheet, where back and scrim are no-ops mid-download and act as
            // Close once it has failed.
            .interactiveDismissDisabled(downloadError == nil)
        }
        .alert("Download required", isPresented: $showDownloadAlert) {
            Button("Cancel", role: .cancel) { pendingModel = nil }
            Button("Download") { confirmDownload() }
        } message: {
            if let pendingModel {
                Text(verbatim: downloadPrompt(for: pendingModel))
            }
        }
        // A gated model's refusal is not a transfer failure: nothing to retry and no
        // error text to read — the sheet closes and this OK-only notice opens, exactly
        // the pair Android performs for its `needsToken` state.
        .alert(
            gatedNotice.map { _ in gatedAlertMessage } ?? "",
            isPresented: Binding(
                get: { gatedNotice != nil },
                set: { if !$0 { gatedNotice = nil } }
            )
        ) {
            Button("OK", role: .cancel) { gatedNotice = nil }
        }
    }

    // MARK: Cards

    @ViewBuilder
    private func card(for model: LiteRtModel) -> some View {
        let downloaded = downloadedIds.contains(model.id)
        ModelRow(
            // Every registry model is vision-capable, so the old eye/bolt split said
            // nothing; the icon stands for the model itself, as "cloud"/"iphone" do
            // for the engines in `EnginePickerSheet`.
            icon: "globe",
            title: model.displayName,
            modelDescription: modelDescription(for: model),
            // Ticked only while the stored selection is backed by weights on disk: a
            // model deleted after being selected is "Not configured", not selected —
            // the row above says the same.
            isSelected: selectedModelId == model.id && downloaded,
            footnote: downloaded
                ? String(localized: "Installed")
                : String(localized: "Not installed")
        ) {
            handleSelection(model)
        }
    }

    /// Android's row line verbatim: download size and the RAM it wants, nothing else —
    /// the two facts that decide whether the model is worth starting on this phone.
    /// The unit is passed in separately so the size line keeps the locale's own "ГБ",
    /// exactly as Android's `formatGb` does.
    private func modelDescription(for model: LiteRtModel) -> String {
        let size = String(
            format: "%.1f %@",
            locale: .current,
            Double(model.sizeBytes) / 1_000_000_000,
            String(localized: "GB")
        )
        return size + " · " + String(localized: "needs \(model.minDeviceMemoryGb) GB RAM")
    }

    private func downloadPrompt(for model: LiteRtModel) -> String {
        let size = String(format: "%.1f GB", Double(model.sizeBytes) / 1_000_000_000)
        return String(localized: "\(model.displayName) is about \(size) and downloads from the internet. Keep this screen open until it finishes.")
    }

    // MARK: Actions

    /// Tapping a card. A downloaded model is selected — written on the tap, no Save; anything
    /// else asks first, because downloading costs gigabytes.
    private func handleSelection(_ model: LiteRtModel) {
        guard !isDownloading else { return }
        if downloadedIds.contains(model.id) {
            onSelect(model)
        } else {
            pendingModel = model
            showDownloadAlert = true
        }
    }

    private func confirmDownload() {
        guard let model = pendingModel else { return }
        pendingModel = nil
        startDownload(model)
    }

    private func startDownload(_ model: LiteRtModel) {
        downloadError = nil
        downloadSheetModel = model
        // Checked before the transfer rather than reported by its failure: running the disk dry
        // part-way through a 3 GB download is a much worse way to learn this. The 1.5× headroom
        // matches Android's `LitertModelStore.missingSpaceBytes`. The failure shows *in the
        // sheet*, like every other one — the list never sees it.
        if let missing = missingSpaceGB(for: model) {
            downloadError = String(localized: "Not enough free space — about \(missing) more is needed.")
            return
        }

        Task {
            do {
                try await litert.download(model, token: app.huggingFaceToken)
                refreshDownloaded()
                downloadSheetModel = nil
                // Written straight away, as on Android: the model the user just waited
                // ten minutes for is the one they meant to use.
                onSelect(model)
            } catch is CancellationError {
                // The Cancel button has already closed the sheet and cleared the error.
                refreshDownloaded()
            } catch {
                refreshDownloaded()
                if isTokenRequired(error) {
                    // A gated repo's refusal: the sheet goes and the OK-only notice
                    // opens, as on Android — the sheet's Retry/Close pair has nothing
                    // to offer a missing token. The notice is *queued*, not raised
                    // here: the sheet is still dismissing, and an alert presented at
                    // the same instant is the one UIKit drops.
                    downloadError = nil
                    pendingGatedNotice = model.displayName
                    downloadSheetModel = nil
                } else {
                    downloadError = error.localizedDescription
                }
            }
        }
    }

    /// True for the refusals that mean "this repo is gated": no token to send, or a
    /// 401/403 back — the same pair Android folds into `GatedModelException`.
    private func isTokenRequired(_ error: any Error) -> Bool {
        if case LiteRtDownloadError.needsToken = error { return true }
        if case let LiteRtDownloadError.fileFailed(code) = error {
            return code == 401 || code == 403
        }
        return false
    }

    /// Android's `litert_token_required_message`, verbatim in wording and OK-only in shape.
    private var gatedAlertMessage: String {
        guard let name = gatedNotice else { return "" }
        return String(localized: "\(name) is hosted in a gated repository. Add a HuggingFace access token in Settings → Intelligence, and make sure your account has accepted the model's terms on huggingface.co.")
    }

    private func delete(_ model: LiteRtModel) {
        litert.deleteModel(model)
        // A built-in stays on screen ready to be downloaded again; a custom one has nowhere
        // to come back from, so deleting it removes the card too (and its saved entry).
        if model.isCustom {
            app.container.settingsManager.removeCustomLitertModel(id: model.id)
            customModels = LiteRtModelsInterop.customModels
        }
        refreshDownloaded()
        // A deleted selection cannot stay: the chat would point at weights that no longer
        // exist. Cleared here rather than left to the caller to notice — Android's screen
        // does the same on its delete confirm.
        if selectedModelId == model.id { onSelect(nil) }
    }

    private func refreshDownloaded() {
        downloadedIds = Set(LiteRtRegistry.allModels.filter(\.isDownloaded).map(\.id))
    }

    /// A human-readable shortfall, or nil when there is room.
    private func missingSpaceGB(for model: LiteRtModel) -> String? {
        let needed = Int64(Double(model.sizeBytes) * 1.5)
        let values = try? LiteRtStorage.baseDirectory.resourceValues(
            forKeys: [.volumeAvailableCapacityForImportantUsageKey]
        )
        guard let available = values?.volumeAvailableCapacityForImportantUsage else { return nil }
        guard available < needed else { return nil }
        return String(format: "%.1f GB", Double(needed - available) / 1_000_000_000)
    }
}

/// The download in progress, as a blocking sheet — the port of Android's
/// `LitertDownloadSheet`.
///
/// While the transfer runs there is no way out: the swipe-down is disabled
/// (`interactiveDismissDisabled` at the presentation site), a sheet has no back button, and
/// the screen underneath is unreachable. The only exit is the Cancel button (a pause: the
/// partial file stays). A failure swaps the progress for the error text and Retry/Close —
/// and only then may the sheet be swiped away, matching Android's back/scrim-as-Close.
private struct LiteRtDownloadSheet: View {
    let model: LiteRtModel
    /// The transfer's failure text; nil while it runs.
    let failure: String?
    let onCancel: () -> Void
    let onRetry: () -> Void
    let onClose: () -> Void

    @EnvironmentObject private var litert: LiteRtService

    var body: some View {
        VStack(spacing: 16) {
            if let failure {
                Text("Download failed")
                    .font(.system(size: 18, weight: .bold))
                Text(verbatim: failure)
                    .font(.system(size: 13))
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                Spacer(minLength: 8)
                Button(action: onRetry) {
                    Text("Retry")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderless)
                Button(action: onClose) {
                    Text("Close")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderless)
            } else {
                Text(verbatim: String(localized: "Downloading \(model.displayName)…"))
                    .font(.system(size: 18, weight: .bold))

                if let state = litert.downloadState, state.modelId == model.id {
                    ProgressView(value: state.fraction)
                    // Percent, speed, time left — the three things a bar alone does not
                    // say over a ten-minute transfer. Same line Android renders.
                    Text(verbatim: progressLine(state))
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Text(verbatim: "\(state.downloadedBytes / 1_000_000) / \(state.totalBytes / 1_000_000) \(String(localized: "MB"))")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }

                Spacer(minLength: 8)
                Button(action: onCancel) {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
    }

    /// `% · speed · time left`, each part resolved before the line is assembled — a
    /// concatenation never goes through the string catalogue (AGENTS.md).
    private func progressLine(_ state: LiteRtService.DownloadState) -> String {
        let percent = Int((state.fraction * 100).rounded())
        return String(localized: "\(percent)% · \(speedLabel(state.bytesPerSecond)) · \(etaLabel(state.remainingMs)) left")
    }

    /// `MB/s` over a million bytes per second, `KB/s` below, `—` while nothing is measured.
    private func speedLabel(_ bytesPerSecond: Int64) -> String {
        if bytesPerSecond <= 0 { return "—" }
        if bytesPerSecond >= 1_000_000 {
            let value = String(format: "%.1f", Double(bytesPerSecond) / 1_000_000)
            return String(localized: "\(value) \(String(localized: "MB"))/s")
        }
        return String(localized: "\(bytesPerSecond / 1000) \(String(localized: "KB"))/s")
    }

    /// Remaining time as `m:ss`, growing to `h:mm:ss` once there is an hour left — the
    /// same shape as Android's `formatEta`, for the same reason: `95:12` is
    /// indistinguishable from minutes-and-seconds at a glance.
    private func etaLabel(_ remainingMs: Int64) -> String {
        if remainingMs <= 0 { return "—" }
        let totalSeconds = remainingMs / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

/// Strips a `List` row back to bare content: no separator, no row background, and the horizontal
/// inset the cards were laid out with before this was a list.
///
/// One modifier rather than four lines on every row, and the reason it exists is `swipeActions`
/// — which only works inside a `List`, and a `List` is otherwise a table.
private struct PlainRow: ViewModifier {
    var top: CGFloat = 0
    var bottom: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: top, leading: 20, bottom: bottom, trailing: 20))
    }
}
