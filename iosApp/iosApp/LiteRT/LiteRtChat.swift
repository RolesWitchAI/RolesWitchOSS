//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import LiteRTLM
import OSLog
import SharedLogic

private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "RolesWitch", category: "LiteRT")

/// Builds the LiteRT `Message`s a turn is sent as. The model's own chat template does the
/// formatting inside the runtime — nothing here renders a prompt.
enum LiteRtChat {
    /// The images the runtime gets are base64'd into the message JSON, so unlike `MLXChat`
    /// there are no temp files to stage and clean up. The attachment's JPEG bytes go straight
    /// through — the same bytes the OpenAI-compatible path sends to the server — because the
    /// runtime's decoder (`stb_image`) reads JPEG as well as PNG. Re-encoding to PNG would only
    /// round-trip through another decoder and can never add detail, so nothing is cropped,
    /// resized or recompressed here: the runtime's vision preprocessor downsizes the image to
    /// fit the checkpoint's own patch budget, and the engine decides that budget (no
    /// `visualTokenBudget` is set on the conversation).
    private static let maxImagesPerTurn = 10

    /// The system message for a conversation. The runtime validates it separately from the
    /// history, so it is the one `Message` never folded into `historyMessages(for:)`.
    static func systemMessage(_ prompt: String) -> LiteRTLM.Message {
        LiteRTLM.Message(prompt, role: .system)
    }

    /// The trimmed history seeding the conversation, text only.
    ///
    /// `LLMService.consolidateMessages` has already stripped images from everything but the
    /// newest message, so replaying the older ones as text is exactly as rich as they ever
    /// were. An assistant turn can sit first (the trim does not care); the runtime accepts it
    /// there even though most chat templates do not.
    static func historyMessages(for messages: [LLMMessage]) -> [LiteRTLM.Message] {
        messages.map { message in
            let role = message.role == LLMMessage.LLMRole.user ? Role.user : Role.model
            return LiteRTLM.Message(message.content, role: role)
        }
    }

    /// The content of the turn being sent: the current user message, with images placed first —
    /// the runtime uses the final token to anchor generation, so trailing text is what the model
    /// answers about.
    ///
    /// [supportsImage] is what actually loaded, passed down from the service (`visionActive`),
    /// not the registry's claim — see `LiteRtService` for why those differ.
    static func turnContents(for message: LLMMessage?, supportsImage: Bool) -> [LiteRTLM.Content] {
        var parts: [Content] = []
        if supportsImage {
            for image in message?.imageData.prefix(maxImagesPerTurn) ?? [] {
                parts.append(.imageData(image.toData()))
            }
        }
        let text = message?.content ?? ""
        if !text.isEmpty { parts.append(.text(text)) }
        // The runtime rejects an empty turn; a lone space is the cheapest valid stand-in.
        if parts.isEmpty { parts.append(.text(" ")) }
        return parts
    }
}
