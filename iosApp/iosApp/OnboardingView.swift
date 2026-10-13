//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI

private enum OnboardingStep: Int, CaseIterable {
    case welcome
    case privacy
}

/// Two-step onboarding ported from the shipping SwiftUI build (`Breakthrough-Swift6`,
/// `UI/Onboarding/`): step 1 — welcome (avatar, title, 3 feature rows); step 2 — the
/// privacy pledge and a link to the terms.
///
/// It deliberately collects **nothing**. The source build's third step (pick a role)
/// is dropped — it never persisted its selection there either — and so is the
/// endpoint/token/model step this app used to show: `openaiAPI`, `openaiKey` and
/// `selectedModel` stay at their empty defaults until the user fills them in
/// under Settings, and the chat surfaces already refuse to send without them.
/// Android matches (`OnboardingScreen.kt`); finishing there pre-selects the
/// on-device engine where the phone can run it at all
/// (`onDeviceInferenceSupported`), like this platform's `setUseOnDevice(true)`.
struct OnboardingView: View {
    @EnvironmentObject private var app: AppViewModel

    @State private var currentStep: Int = 0
    private let steps = OnboardingStep.allCases

    var body: some View {
        VStack {
            Spacer()

            Group {
                switch steps[currentStep] {
                case .welcome:
                    WelcomeStep()
                case .privacy:
                    PrivacyStep()
                }
            }

            Spacer()

            footer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
        .task {
            // Seed the roles + welcome thread here rather than at launch, matching
            // Android's `LaunchedEffect(Unit)` in OnboardingScreen: this screen is
            // shown exactly when onboarding hasn't been completed, i.e. on a fresh
            // install and after a reset.
            await app.seedDefaults()
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: 16) {
            if currentStep > 0 {
                Button {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                        currentStep -= 1
                    }
                } label: {
                    Text("Back")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(.background.secondary)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }

            Button {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                    advance()
                }
            } label: {
                // Branch rather than a ternary so both strings stay plain literals
                // the catalog extractor can see.
                Group {
                    if currentStep == steps.count - 1 {
                        Text("Finish Setup")
                    } else {
                        Text("Next")
                    }
                }
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(
                    LinearGradient(
                        gradient: Gradient(colors: [.accentColor, .accentColor.opacity(0.8)]),
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .shadow(color: .accentColor.opacity(0.3), radius: 12, y: 6)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 24)
    }

    private func advance() {
        if currentStep < steps.count - 1 {
            currentStep += 1
            return
        }
        // NOTE: notification authorization is deliberately NOT requested here.
        // Android asks on first chat open, gated by the shared
        // `hasSeenNotificationPrompt` flag — which is also where the prompt has
        // context, per Apple's own guidance. See `MessagesView`.
        app.completeOnboarding()
    }
}

// MARK: - Step 1: Welcome

private struct WelcomeStep: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(
                                LinearGradient(
                                    gradient: Gradient(colors: [.accentColor, .accentColor.opacity(0.6)]),
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 104, height: 104)
                            .shadow(color: .accentColor.opacity(0.3), radius: 20, y: 10)

                        Image("Profile")
                            .resizable()
                            .scaledToFill()
                            .frame(width: 104, height: 104)
                            .clipShape(Circle())
                    }

                    Text("Hello, it\u{2019}s Roles Witch")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.center)

                    Text("Your intelligent voice assistant")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 8)
                }

                VStack(spacing: 12) {
                    FeatureRow(
                        systemName: "cpu.fill",
                        color: .purple,
                        title: "Works offline",
                        description: "Run a model fully on this device."
                    )
                    FeatureRow(
                        systemName: "waveform.circle.fill",
                        color: .accentColor,
                        title: "Voice Conversations",
                        description: "Natural voice interactions with AI."
                    )
                    FeatureRow(
                        systemName: "theatermasks.fill",
                        color: .orange,
                        title: "Multiple Roles",
                        description: "Switch roles on the fly."
                    )
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
    }
}

// MARK: - Step 2: Privacy

private struct PrivacyStep: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.accentColor.opacity(0.15))
                            .frame(width: 80, height: 80)

                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 36))
                            .foregroundStyle(Color.accentColor.gradient)
                    }

                    Text("Before We Begin")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.center)

                    Text("Roles Witch respects your privacy")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 8)
                }

                VStack(spacing: 12) {
                    FeatureRow(
                        systemName: "person.fill.xmark",
                        color: .green,
                        title: "No Sign-Up Required",
                        description: "No account, no personal data. Fully anonymous."
                    )
                    FeatureRow(
                        systemName: "eye.slash.fill",
                        color: .purple,
                        title: "No Analytics or Tracking",
                        description: "Your messages, responses and attachments are never collected."
                    )
                    FeatureRow(
                        systemName: "iphone.gen3",
                        color: .orange,
                        title: "Your Choice of Engine",
                        description: "Run a model on device, or connect your own API. Nothing goes anywhere else."
                    )
                }
                .padding(.horizontal, 20)

                // Plain text, no external-link glyph beside it — the same shape Android's
                // `PrivacyStep` gives it, where the link is a bare `TextButton`.
                Link(destination: URL(string: "https://roleswitch.ai/terms-and-conditions.html")!) {
                    Text("Terms and Conditions")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                }
            }
            .padding(.vertical, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
    }
}

// MARK: - Shared row

private struct FeatureRow: View {
    let systemName: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey

    var body: some View {
        HStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(color.opacity(0.15))
                    .frame(width: 50, height: 50)

                Image(systemName: systemName)
                    .font(.system(size: 22))
                    .foregroundStyle(color.gradient)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)

                Text(description)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.background.secondary)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

#Preview {
    OnboardingView().environmentObject(AppViewModel())
}
