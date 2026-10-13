//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import OSLog
import SharedLogic

private let logger = Logger(
    subsystem: Bundle.main.bundleIdentifier ?? "RolesWitch",
    category: "LiteRTDownload"
)

/// Fetches a model's weights from the Hugging Face hub on a **background** `URLSession`, so a
/// download survives the screen locking, the app being suspended, and the app being killed.
///
/// A model is one file, which keeps this simpler than `MLXDownloader`, but the reasons for
/// owning the transfers are the same two, and they are one: the transfer has to belong to us.
///
/// - **Lock survival.** An in-process `URLSession` dies with the app; a background session's
///   transfers run in `nsurlsessiond` and are the only mechanism iOS offers for a download
///   that outlives suspension. `AppDelegate` re-attaches at launch so a transfer that finished
///   while the app was dead still lands its file in the right place.
/// - **Progress.** A bare `downloadTask` without a delegate reports nothing until it completes;
///   with one, `didWriteData` is per byte and needs no guessing.
///
/// Two things the hub layout makes necessary:
///
/// - **Resume.** A truncated file is a corrupt model (see `LiteRtModel.isDownloaded`, where
///   size is the check), so a retry sends `Range: bytes=<partial>–` and appends what arrived
///   instead of starting the multi-gigabyte fetch over.
/// - **Gated repos** need an `Authorization: Bearer <token>` header. A gated model fetched
///   anonymously is a 401 that only surfaces much later, inside the engine, so the downloader
///   throws a `needsToken` error up front when the app has no token to send.
final class LiteRtDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    static let shared = LiteRtDownloader()

    /// Stable across launches: iOS matches a relaunched app to its outstanding transfers by this.
    private static let sessionIdentifier = "\(Bundle.main.bundleIdentifier ?? "RolesWitch").litert-download"

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        // The user is watching a progress bar and cannot use the model until this finishes, so
        // it is not the kind of work iOS should defer to a convenient moment.
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        // Weights are the one thing worth waiting on a bad connection for; the default 60s
        // per-resource timeout would abandon a 4 GB fetch on a slow train.
        configuration.timeoutIntervalForResource = 24 * 60 * 60
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    /// Everything the delegate callbacks mutate. They arrive on the session's own queue, which is
    /// neither the main actor nor serial with respect to the download call, so all of it lives
    /// behind one lock rather than in `@MainActor` state.
    private let lock = NSLock()
    private var jobs: [String: Job] = [:]

    /// Set by `AppDelegate` when iOS relaunches the app to hand over finished transfers, and
    /// called once the session says it has delivered everything.
    private var backgroundEventsHandler: (@Sendable () -> Void)?

    private override init() {
        super.init()
    }

    // MARK: - Download

    /// Downloads [model]'s weights to its per-model directory, reporting per-byte progress.
    ///
    /// - Parameter model: The model to fetch.
    /// - Parameter token: The HuggingFace token for gated repos; ignored for public ones.
    /// - Parameter onProgress: Called with the completed fraction as bytes land; never
    ///   monotonically backwards — a resumed transfer restarts its own byte count, and the
    ///   caller folds already-on-disk bytes into the total only once.
    func download(
        _ model: LiteRtModel,
        token: String,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        guard !model.isDownloaded else { return }
        if model.gated && token.isEmpty {
            throw LiteRtDownloadError.needsToken(model.displayName)
        }

        let destination = model.fileURL
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        // Bytes already on disk are the resume base, not a re-fetch.
        let existing = Int64((try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)

        logger.info("""
            LiteRT download start: \(model.id, privacy: .public) \
            \(model.sizeBytes / 1_000_000, privacy: .public) MB, \
            \(existing / 1_000_000, privacy: .public) MB already on disk
            """)

        let reporter = startReportingTaskState(modelId: model.id)
        defer { reporter.cancel() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                start(
                    job: Job(
                        modelId: model.id,
                        destination: destination,
                        totalBytes: model.sizeBytes,
                        alreadyOnDisk: existing,
                        encodedURL: model.downloadURL,
                        bearerToken: model.gated ? token : nil,
                        progressHandler: onProgress,
                        continuation: continuation
                    )
                )
            }
        } onCancel: {
            cancel(modelId: model.id)
        }
    }

    /// Abandons an in-flight download. The bytes that landed stay where they are, so restarting
    /// resumes from them.
    func cancel(modelId: String) {
        lock.lock()
        let job = jobs.removeValue(forKey: modelId)
        lock.unlock()
        guard let job else { return }
        job.tasks.forEach { $0.cancel() }
        logger.info("LiteRT download cancelled: \(modelId, privacy: .public)")
        job.continuation.resume(throwing: CancellationError())
    }

    /// Logs what the transfer is actually doing, every few seconds, until the job ends.
    ///
    /// A background session schedules transfers on its own terms, so "stuck" has several
    /// possible meanings — a task `.suspended`, one `.running` but receiving nothing, or Low
    /// Power Mode deferring background work whatever `isDiscretionary` says. Each reads
    /// differently here.
    private func startReportingTaskState(modelId: String) -> Task<Void, Never> {
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                guard let self, !Task.isCancelled else { return }
                self.lock.lock()
                let job = self.jobs[modelId]
                let counted = (job?.bytesCompleted ?? 0)
                    + (job?.bytesInFlight.values.reduce(Int64(0), +) ?? 0)
                self.lock.unlock()
                guard job != nil else { return }
                logger.info("""
                    LiteRT download \(modelId, privacy: .public): \
                    \(counted / 1_000_000, privacy: .public) MB counted, \
                    lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled, privacy: .public)
                    """)
            }
        }
    }

    // MARK: - Relaunch

    /// Re-attaches to transfers `nsurlsessiond` carried across a launch, so their files still
    /// land in the right place. Called once at startup; without it a download that completed
    /// while the app was dead would be delivered to a session with no idea what to do with it.
    func adoptOutstandingTasks() {
        session.getAllTasks { tasks in
            guard !tasks.isEmpty else { return }
            logger.info("LiteRT download: adopting \(tasks.count, privacy: .public) outstanding task(s)")
        }
    }

    /// Stores the completion handler iOS hands over when it relaunches the app for a finished
    /// background transfer. Calling it is what tells the system the app is done working.
    func handleBackgroundEvents(completionHandler: @escaping @Sendable () -> Void) {
        lock.lock()
        backgroundEventsHandler = completionHandler
        lock.unlock()
    }

    // MARK: - Internals

    /// One model's download: where its bytes are going, how much is counted, and who to tell
    /// when it ends.
    private final class Job {
        let modelId: String
        let destination: URL
        let totalBytes: Int64
        let alreadyOnDisk: Int64
        let encodedURL: String
        let bearerToken: String?
        let progressHandler: @Sendable (Double) -> Void
        let continuation: CheckedContinuation<Void, any Error>

        var tasks: [URLSessionDownloadTask] = []
        /// Bytes written per still-running task, replaced (not added) on every callback — a task
        /// reports its own running total, and a resumed one starts again from its own zero.
        var bytesInFlight: [Int: Int64] = [:]
        var bytesCompleted: Int64 = 0
        var outstanding = 0
        /// Tasks from a download this job superseded: cancelled, and their callbacks ignored —
        /// their bytes must not feed this job's count and their completion must not finish it.
        var ignoredTaskIds: Set<Int> = []
        /// Set once a ranged request is answered with the whole body: the on-disk resume
        /// base is no longer part of what is arriving, so it stops counting.
        var droppedResumeBase = false

        init(
            modelId: String,
            destination: URL,
            totalBytes: Int64,
            alreadyOnDisk: Int64,
            encodedURL: String,
            bearerToken: String?,
            progressHandler: @escaping @Sendable (Double) -> Void,
            continuation: CheckedContinuation<Void, any Error>
        ) {
            self.modelId = modelId
            self.destination = destination
            self.totalBytes = totalBytes
            self.alreadyOnDisk = alreadyOnDisk
            self.encodedURL = encodedURL
            self.bearerToken = bearerToken
            self.progressHandler = progressHandler
            self.continuation = continuation
            self.bytesCompleted = alreadyOnDisk
        }

        /// Completed fraction, never backwards (already-on-disk bytes are the floor; in-flight
        /// bytes only add to it).
        var fraction: Double {
            let done = bytesCompleted + bytesInFlight.values.reduce(0, +)
            // `totalBytes` is at least 1, so the division is safe and 1.0 means "done, but only
            // the finishing callback confirms it".
            return min(Double(done), Double(totalBytes)) / Double(max(totalBytes, 1))
        }
    }

    private func start(job: Job) {
        lock.lock()
        // A second request for a model already downloading must take the whole job over.
        // Resuming the old continuation alone is not enough: its URLSession tasks kept
        // running, both transfers then fed the new job's byte count — progress at twice
        // the rate, the bar pinning at 100% with megabytes still to come — and the first
        // old task's completion "finished" the new job while its own transfer was still
        // mid-file. They are cancelled here and their callbacks ignored below.
        if let existing = jobs[job.modelId] {
            existing.tasks.forEach { $0.cancel() }
            job.ignoredTaskIds = Set(existing.tasks.map(\.taskIdentifier))
            lock.unlock()
            existing.continuation.resume(throwing: CancellationError())
            lock.lock()
        }
        jobs[job.modelId] = job
        job.outstanding = 1
        lock.unlock()

        var request = URLRequest(url: URL(string: job.encodedURL)!)
        if let token = job.bearerToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        // A file that is part-way there resumes by range; a server that ignores the range
        // answers 200 with the whole body, which the finishing path detects and overwrites.
        if job.alreadyOnDisk > 0 && job.alreadyOnDisk < job.totalBytes {
            request.setValue("bytes=\(job.alreadyOnDisk)-", forHTTPHeaderField: "Range")
        }

        let task = session.downloadTask(with: request)
        // The one piece of state iOS keeps for us across a relaunch. Everything the delegate
        // needs to file the result away has to fit in here.
        task.taskDescription = TaskDescription(
            modelId: job.modelId,
            destination: job.destination.path
        ).encoded
        lock.lock()
        job.tasks.append(task)
        lock.unlock()
        task.resume()
    }
}

// MARK: - URLSessionDownloadDelegate

extension LiteRtDownloader {
    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let description = TaskDescription(downloadTask.taskDescription),
              let job = job(for: description) else { return }
        lock.lock()
        // A task this job superseded: cancelled on its way out, and its bytes are not
        // ours to count.
        guard !job.ignoredTaskIds.contains(downloadTask.taskIdentifier) else {
            lock.unlock()
            return
        }
        // A whole body in answer to a ranged request: the on-disk base is not part of
        // what is arriving, so it stops counting — the finishing path overwrites rather
        // than appends. Without this the count runs base+body, the bar pins at 100%
        // mid-transfer, and the model looks both stuck and downloaded.
        if job.alreadyOnDisk > 0, !job.droppedResumeBase,
           totalBytesExpectedToWrite > 0, totalBytesExpectedToWrite >= job.totalBytes {
            job.droppedResumeBase = true
            job.bytesCompleted = 0
        }
        job.bytesInFlight[downloadTask.taskIdentifier] = totalBytesWritten
        let fraction = job.fraction
        let handler = job.progressHandler
        lock.unlock()
        handler(fraction)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let description = TaskDescription(downloadTask.taskDescription) else { return }
        // Must happen before returning: the file at `location` is deleted as soon as this
        // method does. This is also the path taken when the app was relaunched to receive a
        // transfer it no longer has a `Job` for — the bytes are filed away regardless.
        let destination = URL(fileURLWithPath: description.destination)
        if let http = downloadTask.response as? HTTPURLResponse, !(200 ..< 300).contains(http.statusCode) {
            logger.error("""
                LiteRT download: \(description.modelId, privacy: .public) refused with HTTP \
                \(http.statusCode, privacy: .public)
                """)
            fail(modelId: description.modelId, with: LiteRtDownloadError.fileFailed(http.statusCode))
            return
        }
        // A 206 is the resumed remainder, appended to what was already there. Anything
        // else overwrites: a server that ignored the range answered with the whole file.
        let isPartialRange = (downloadTask.response as? HTTPURLResponse)?.statusCode == 206
        do {
            try store(location, at: destination, appending: isPartialRange)
        } catch {
            // A leftover sitting at the destination — typically from a delete that ran
            // under an earlier transfer, or a half-written base from a killed append —
            // makes the store fail "not permitted" and would strand every later flush
            // the same way. Clear it and store the body we are still holding once more,
            // as a plain whole-file move.
            logger.error("""
                LiteRT download: storing \(description.modelId, privacy: .public) failed \
                (\(error.localizedDescription, privacy: .public)); clearing the destination \
                and retrying the store
                """)
            try? FileManager.default.removeItem(at: destination)
            // An append that cannot complete is not retried: the remainder is worthless
            // without the base it was ranged from, so the partial goes and the failure
            // stands — the next attempt starts clean (no base, whole-file 200 path).
            guard !isPartialRange else {
                fail(modelId: description.modelId, with: error)
                return
            }
            do {
                try FileManager.default.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try FileManager.default.moveItem(at: location, to: destination)
            } catch {
                logger.error("LiteRT download: could not store \(description.modelId, privacy: .public): \(error.localizedDescription)")
                fail(modelId: description.modelId, with: error)
            }
        }
    }

    /// Files a finished body away: append to the on-disk base for a 206, replace or move
    /// for anything else.
    private func store(_ location: URL, at destination: URL, appending: Bool) throws {
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if appending, FileManager.default.fileExists(atPath: destination.path) {
            let handle = try FileHandle(forWritingTo: destination)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: Data(contentsOf: location))
        } else if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: location)
        } else {
            try FileManager.default.moveItem(at: location, to: destination)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: (any Error)?
    ) {
        guard let description = TaskDescription(task.taskDescription) else { return }
        if let error {
            // A cancelled task is the user's doing, and `cancel(modelId:)` has already resumed
            // the continuation — resuming it again would trap.
            if (error as NSError).code == NSURLErrorCancelled { return }
            logger.error("LiteRT download failed: \(description.modelId, privacy: .public): \(error.localizedDescription)")
            fail(modelId: description.modelId, with: error)
            return
        }

        guard let job = job(for: description) else { return }
        lock.lock()
        // A superseded task completing must not touch this job's accounting: its bytes
        // were never ours, and decrementing `outstanding` for it once "finished" the
        // new job while its own transfer was still mid-file.
        guard !job.ignoredTaskIds.contains(task.taskIdentifier) else {
            lock.unlock()
            return
        }
        job.bytesInFlight[task.taskIdentifier] = nil
        job.bytesCompleted += task.countOfBytesReceived
        job.outstanding -= 1
        let finished = job.outstanding <= 0
        let fraction = job.fraction
        let handler = job.progressHandler
        if finished { jobs[description.modelId] = nil }
        lock.unlock()

        handler(fraction)
        if finished {
            logger.info("LiteRT download complete: \(description.modelId, privacy: .public)")
            job.continuation.resume()
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        lock.lock()
        let handler = backgroundEventsHandler
        backgroundEventsHandler = nil
        lock.unlock()
        // On the main queue, as UIKit requires of this handler.
        DispatchQueue.main.async { handler?() }
    }

    /// The in-memory job for a task whose description we can decode, or nil when the app was
    /// relaunched to deliver a finished transfer it no longer has a job for.
    private func job(for description: TaskDescription) -> Job? {
        lock.lock()
        defer { lock.unlock() }
        return jobs[description.modelId]
    }

    /// Fails the model's download on the first error, cancelling the task.
    private func fail(modelId: String, with error: any Error) {
        lock.lock()
        guard let job = jobs.removeValue(forKey: modelId) else {
            lock.unlock()
            return
        }
        lock.unlock()
        job.tasks.forEach { $0.cancel() }
        job.continuation.resume(throwing: error)
    }
}

// MARK: - Task description

/// What a download task needs to remember about itself, encoded into `URLSessionTask.taskDescription`.
///
/// Not a convenience: a background task can outlive the process, and when iOS relaunches the app
/// to deliver it there is no `Job` left in memory. This string is the only context that comes
/// back with it.
private struct TaskDescription {
    let modelId: String
    let destination: String

    var encoded: String { "\(modelId)\n\(destination)" }

    init(modelId: String, destination: String) {
        self.modelId = modelId
        self.destination = destination
    }

    init?(_ encoded: String?) {
        let parts = (encoded ?? "").components(separatedBy: "\n")
        guard parts.count == 2 else { return nil }
        modelId = parts[0]
        destination = parts[1]
    }
}

enum LiteRtDownloadError: LocalizedError {
    case needsToken(String)
    case fileFailed(Int)

    var errorDescription: String? {
        switch self {
        case .needsToken(let name):
            return "\(name) is gated — add a HuggingFace token in Settings → Intelligence to download it."
        case .fileFailed(let code):
            return "Could not download the model (HTTP \(code))."
        }
    }
}