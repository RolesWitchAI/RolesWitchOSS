//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import SharedLogic
import Combine

/// Wraps a closure as a Kotlin `FlowCollector`.
final class ClosureFlowCollector: FlowCollector {
    private let handler: (Any?) -> Void

    init(handler: @escaping (Any?) -> Void) {
        self.handler = handler
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        handler(value)
        completionHandler(nil)
    }
}

/// Subscribes to a Kotlin `StateFlow` and publishes its current value via Combine.
final class KotlinStateFlowObserver: ObservableObject {
    @Published var value: Any?

    private var collectTask: Task<Void, Never>?

    init(_ flow: StateFlow) {
        self.value = flow.value
        let collector = ClosureFlowCollector { [weak self] value in
            guard let self else { return }
            Task { @MainActor in self.value = value }
        }
        self.collectTask = Task { [weak self] in
            _ = try? await flow.collect(collector: collector)
            _ = self
        }
    }

    deinit { collectTask?.cancel() }
}
