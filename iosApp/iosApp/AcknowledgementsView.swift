//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI

/// Where a component's licence text comes from.
///
/// ``bundled`` entries ship in `Licenses/` and open in-app. That is not a stylistic
/// choice: Apache 2.0 §4(a) obliges a redistributor to give recipients *a copy* of the
/// licence, and the MIT terms require the notice to travel with the software — a
/// hyperlink to apache.org satisfies neither if the device is offline.
///
/// ``external`` is for terms that are not redistributable licences at all, where
/// pointing at the canonical, always-current document is correct. Nothing needs it
/// today; it is kept so adding such a component cannot quietly become a bundled lie.
private enum LicenseRef {
    case bundled(String)
    case external(URL)
}

private struct ThirdPartyComponent: Identifiable {
    let name: String
    let owner: String
    let license: String
    let ref: LicenseRef

    var id: String { name }
}

private let apacheLicense = "apache-2.0"
private let mitOpenAIKotlin = "mit-openai-kotlin"

/// Hand-maintained, because there is no licence-collector in either build. Every entry
/// was read off the dependency's own `LICENSE` file in the resolved SwiftPM checkout or
/// its artifact's POM — not assumed. Keep in step with the package references in
/// `iosApp.xcodeproj` and with `sharedLogic/build.gradle.kts`.
///
/// This is the iOS list, not Android's. The two apps link different things — no Vosk, no
/// ML Kit, no Compose here — and copying Android's entries across would be a false
/// notice, which is the one thing an attribution screen must never be.
///
/// **No model weights.** iOS ships none: LiteRT-LM downloads a checkpoint only if the
/// user picks one, and the terms that then apply are the publisher's, shown on the model's
/// own page. Listing them here would claim a distribution that does not happen.
private let thirdPartyComponents: [ThirdPartyComponent] = [
    ThirdPartyComponent(
        name: "LiteRT-LM",
        owner: "Google",
        license: "Apache 2.0",
        ref: .bundled(apacheLicense)
    ),
    ThirdPartyComponent(
        name: "Kotlin, Coroutines, Serialization, DateTime",
        owner: "JetBrains",
        license: "Apache 2.0",
        ref: .bundled(apacheLicense)
    ),
    ThirdPartyComponent(
        name: "Ktor",
        owner: "JetBrains",
        license: "Apache 2.0",
        ref: .bundled(apacheLicense)
    ),
    ThirdPartyComponent(
        name: "Room, DataStore, SQLite",
        owner: "Google",
        license: "Apache 2.0",
        ref: .bundled(apacheLicense)
    ),
    ThirdPartyComponent(
        name: "Kermit",
        owner: "Touchlab",
        license: "Apache 2.0",
        ref: .bundled(apacheLicense)
    ),
    ThirdPartyComponent(
        name: "OpenAI Kotlin client",
        owner: "Mouaad Aallam",
        license: "MIT",
        ref: .bundled(mitOpenAIKotlin)
    )
]

/// Third-party attribution screen, reached from the Settings overflow menu. iOS
/// counterpart of Android's `AcknowledgementsScreen`.
struct AcknowledgementsView: View {
    @State private var licenseFile: LicenseFile?

    var body: some View {
        List {
            Section {
                Text("RolesWitch is built on open-source work by other people. Thank you to everyone below.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .listRowBackground(Color.clear)

            Section {
                ForEach(thirdPartyComponents) { component in
                    Button {
                        switch component.ref {
                        case .bundled(let file): licenseFile = LicenseFile(id: file)
                        case .external(let url): UIApplication.shared.open(url)
                        }
                    } label: {
                        row(component)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("Acknowledgements")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $licenseFile) { file in
            LicenseSheet(fileName: file.id)
        }
    }

    private func row(_ component: ThirdPartyComponent) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                // Library names and the people behind them: never translated.
                Text(verbatim: component.name)
                    .foregroundStyle(.primary)
                Text(verbatim: component.owner)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Text(verbatim: component.license)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// Shows a licence shipped in the app bundle. The read is off the main thread — the
/// Apache text is ~11 KB, small enough that it would probably go unnoticed, but there
/// is no reason to block a frame on file I/O.
private struct LicenseSheet: View {
    let fileName: String

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                // The licence itself, which is not ours to translate or reflow.
                Text(verbatim: text)
                    .font(.system(size: 12, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding(16)
            }
            .navigationTitle("License")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
        .task {
            text = await Self.load(fileName)
        }
    }

    private static func load(_ fileName: String) async -> String {
        await Task.detached(priority: .userInitiated) {
            guard let url = Bundle.main.url(forResource: fileName, withExtension: "txt"),
                  let contents = try? String(contentsOf: url, encoding: .utf8) else { return "" }
            return contents
        }.value
    }
}

/// `sheet(item:)` wants an `Identifiable`, and a retroactive conformance on `String`
/// would be a global change made for one sheet. A named wrapper says what it holds.
private struct LicenseFile: Identifiable {
    let id: String
}
