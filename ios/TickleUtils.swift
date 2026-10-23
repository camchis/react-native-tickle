//
//  TickleUtils.swift
//  Pods
//
//  Created by Alireza Hadjar on 9/14/25.
//

import SwiftUI
import CoreHaptics

public enum HapticFeedbackManager {
    public static func generateFeedback(style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        let impactGenerator = UIImpactFeedbackGenerator(style: style)
        impactGenerator.prepare()
        impactGenerator.impactOccurred()
    }
}

protocol HapticAnimationState {
    var hapticEvents: [CHHapticEvent] { get }
    var hapticCurves: [CHHapticParameterCurve] { get }
}

/// Stores the configuration for a continuous player so it can be recreated after engine reinitialization
struct ContinuousPlayerConfig {
    let playerId: String
    let initialIntensity: Float
    let initialSharpness: Float
}

class HapticFeedback {
    static let shared = HapticFeedback()
    
    private static let hapticsEnabledKey = "com.tickle.hapticsEnabled"

    private var engine: CHHapticEngine?
    private var hapticPlayers: [String: CHHapticAdvancedPatternPlayer] = [:]
    private var continuousPlayers: [String: CHHapticAdvancedPatternPlayer] = [:]
    /// Stores configurations for continuous players so they can be recreated after engine reinitialization
    private var continuousPlayerConfigs: [String: ContinuousPlayerConfig] = [:]
    private let lock = NSLock()
    /// Set when the engine is not known to be running (freshly created, or
    /// stopped by the system via idle auto-shutdown, an audio-session
    /// interruption, suspension or a reset) so the next play call restarts it
    /// on demand. Guarded by its own lock: the stopped/reset handlers fire on
    /// CoreHaptics' internal queue and must never wait on `lock`, which is held
    /// across engine calls (a handler blocking on `lock` while a play call
    /// waits on CoreHaptics' queue would deadlock).
    /// Lock order is always `lock` -> `engineStateLock`, never the reverse.
    private var engineNeedsStart = false
    private let engineStateLock = NSLock()
    
    public var supportsHaptics: Bool {
        return CHHapticEngine.capabilitiesForHardware().supportsHaptics
    }
    
    // MARK: - Global Haptics Enable/Disable
    
    /// Returns whether haptics are globally enabled. Persisted in UserDefaults.
    /// Defaults to true if not previously set.
    public var hapticsEnabled: Bool {
        get {
            // If key doesn't exist, default to true (haptics enabled)
            if UserDefaults.standard.object(forKey: HapticFeedback.hapticsEnabledKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: HapticFeedback.hapticsEnabledKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: HapticFeedback.hapticsEnabledKey)
        }
    }

    public func createAndStartHapticEngine() {
        guard supportsHaptics else {
            print("Device does not support haptics")
            return
        }
        
        lock.lock()
        
        // Clean up existing engine first
        if engine != nil {
            engine?.stop(completionHandler: nil)
            engine = nil
        }
        
        // Create and configure a haptic engine.
        do {
            engine = try CHHapticEngine()
        } catch let error {
            print("Engine Creation Error: \(error)")
            lock.unlock()
            return
        }

        // Mute audio to reduce latency for collision haptics.
        engine?.playsHapticsOnly = true

        // Release the audio hardware whenever no haptic is playing. A running
        // CHHapticEngine is an active audio client of the system's media
        // server: an app that holds one open while foregrounded is treated as
        // the device's current audio client, so iOS binds the volume UI to the
        // app and re-asserts the session's volume against user changes (the
        // device volume "turns itself back up" moments after being lowered).
        // With auto-shutdown the engine only claims the audio system for the
        // moments a haptic actually plays.
        engine?.isAutoShutdownEnabled = true

        // The stopped handler alerts you of engine stoppage.
        engine?.stoppedHandler = { [weak self] reason in
            print("Stop Handler: The engine stopped for reason: \(reason.rawValue)")
            self?.noteEngineStopped()
            switch reason {
            case .audioSessionInterrupt:
                print("Audio session interrupt")
            case .applicationSuspended:
                print("Application suspended")
            case .idleTimeout:
                print("Idle timeout")
            case .systemError:
                print("System error")
            case .notifyWhenFinished:
                print("Playback finished")
            case .gameControllerDisconnect:
                print("Controller disconnected.")
            case .engineDestroyed:
                print("Engine destroyed.")
            @unknown default:
                print("Unknown error")
            }
        }

        // The reset handler provides an opportunity to restart the engine.
        // The reset handler fires on CoreHaptics' internal queue, so it must not
        // touch `engine` (guarded by `lock`) or wait on `lock`. Flag the engine
        // for a restart instead; the next play call restarts it via
        // ensureEngineRunning(). Restarting eagerly here would leave an idle
        // engine running (and holding the audio session) until auto-shutdown.
        engine?.resetHandler = { [weak self] in
            print("Reset Handler: Engine will be restarted on the next haptic.")
            self?.noteEngineStopped()
        }

        // Do NOT start the engine here: a running engine is an active audio
        // client even with no haptic playing (see isAutoShutdownEnabled above),
        // and this method runs on every app foreground. Play paths call
        // ensureEngineRunning() so the engine starts on the first real haptic.
        engineStateLock.lock()
        engineNeedsStart = true
        engineStateLock.unlock()

        // Copy configs while holding the lock, then release before recreating players
        let configsCopy = Array(continuousPlayerConfigs.values)
        lock.unlock()
        
        // Recreate any continuous players that were registered before engine was destroyed
        for config in configsCopy {
            createContinuousPlayerInternal(
                playerId: config.playerId,
                initialIntensity: config.initialIntensity,
                initialSharpness: config.initialSharpness
            )
        }
    }

    public func createHapticPlayers<State: HapticAnimationState>(for states: [State]) {
        lock.lock()
        defer { lock.unlock() }
        
        for state in states {
            let key = String(describing: state)
            guard hapticPlayers[key] == nil else { continue }
            do {
                let pattern = try CHHapticPattern(events: state.hapticEvents, parameterCurves: state.hapticCurves)
                let player = try engine?.makeAdvancedPlayer(with: pattern)
                hapticPlayers[key] = player
            } catch let error {
                print("Haptic Player Creation Error: \(error)")
            }
        }
    }

    public func clearHapticPlayers() {
        lock.lock()
        defer { lock.unlock() }
        
        // Stop all regular haptic players
        for (key, player) in hapticPlayers {
            do {
                try player.stop(atTime: CHHapticTimeImmediate)
            } catch let error {
                print("Error stopping haptic player \(key): \(error)")
            }
        }
        hapticPlayers = [:]
        
        // Stop all continuous players
        for (key, player) in continuousPlayers {
            do {
                try player.stop(atTime: CHHapticTimeImmediate)
            } catch let error {
                print("Error stopping continuous player \(key): \(error)")
            }
        }
        continuousPlayers = [:]
    }
    
    public func destroyEngine() {
        clearHapticPlayers()
        
        lock.lock()
        defer { lock.unlock() }
        
        engine?.stop(completionHandler: nil)
        engine = nil

        // No engine, nothing to restart. A late stoppedHandler callback from the
        // engine we just stopped may set this again; that is harmless because
        // ensureEngineRunning() bails out without an engine and
        // createAndStartHapticEngine() sets it for the new engine anyway.
        engineStateLock.lock()
        engineNeedsStart = false
        engineStateLock.unlock()
    }

    private func noteEngineStopped() {
        engineStateLock.lock()
        engineNeedsStart = true
        engineStateLock.unlock()
    }

    /// Starts the engine if it is not known to be running. Must be called with
    /// `lock` held, right before using a player.
    ///
    /// Auto-shutdown (and interruptions) stop the engine while idle; advanced
    /// pattern players are not reliably auto-restarted by the system, so every
    /// path that plays or sends parameters to a player calls this first.
    private func ensureEngineRunning() {
        guard let engine else { return }

        // Test-and-clear *before* starting so a stop that lands while start()
        // is in flight re-sets the flag instead of being overwritten after.
        engineStateLock.lock()
        let needsStart = engineNeedsStart
        engineNeedsStart = false
        engineStateLock.unlock()
        guard needsStart else { return }

        do {
            try engine.start()
        } catch {
            print("Failed to start the engine: \(error)")
            // Leave the flag set so the next play call retries.
            noteEngineStopped()
        }
    }

    public func startHapticPlayer<State: HapticAnimationState>(for state: State) {
        guard hapticsEnabled else { return }
        
        lock.lock()
        defer { lock.unlock() }
        
        let key = String(describing: state)
        guard let player = hapticPlayers[key] else { return }
        ensureEngineRunning()
        do {
            try player.start(atTime: CHHapticTimeImmediate)
        } catch let error {
            print("Error starting the haptic player: \(error)")
        }
    }

    public func stopHapticPlayer<State: HapticAnimationState>(for state: State) {
        lock.lock()
        defer { lock.unlock() }
        
        let key = String(describing: state)
        do {
            try hapticPlayers[key]?.stop(atTime: CHHapticTimeImmediate)
        } catch let error {
            print("Error stopping the haptic player: \(error)")
        }
    }

    public func stopAllHapticPlayers() {
        lock.lock()
        defer { lock.unlock() }
        
        for (key, player) in hapticPlayers {
            do {
                try player.stop(atTime: CHHapticTimeImmediate)
            } catch let error {
                print("Error stopping haptic player \(key): \(error)")
            }
        }
    }

    // MARK: - Continuous Player Methods
    
    /// Creates a continuous haptic player with the given ID.
    /// - If a player with this ID already exists, it will be stopped and replaced.
    /// - If the engine is not initialized or device doesn't support haptics, this is a no-op.
    /// - The player configuration is stored so it can be recreated after engine reinitialization.
    public func createContinuousPlayer(playerId: String, initialIntensity: Float, initialSharpness: Float) {
        // Store the configuration so we can recreate the player after engine reinitialization
        let config = ContinuousPlayerConfig(
            playerId: playerId,
            initialIntensity: initialIntensity,
            initialSharpness: initialSharpness
        )
        
        lock.lock()
        continuousPlayerConfigs[playerId] = config
        lock.unlock()
        
        createContinuousPlayerInternal(
            playerId: playerId,
            initialIntensity: initialIntensity,
            initialSharpness: initialSharpness
        )
    }
    
    /// Internal method to create a continuous player without storing config (used during recreation)
    private func createContinuousPlayerInternal(playerId: String, initialIntensity: Float, initialSharpness: Float) {
        lock.lock()
        defer { lock.unlock() }
        
        // Guard: Check if engine exists
        guard let engine = engine else {
            print("[Tickle] Cannot create continuous player '\(playerId)': Engine not initialized. Call initializeEngine() first.")
            return
        }
        
        // If player already exists with this ID, destroy it first
        if let existingPlayer = continuousPlayers[playerId] {
            do {
                try existingPlayer.stop(atTime: CHHapticTimeImmediate)
            } catch {}
            continuousPlayers.removeValue(forKey: playerId)
        }
        
        do {
            // Create an intensity parameter
            let intensity = CHHapticEventParameter(parameterID: .hapticIntensity, value: initialIntensity)
            
            // Create a sharpness parameter
            let sharpness = CHHapticEventParameter(parameterID: .hapticSharpness, value: initialSharpness)
            
            // Create a continuous event with a long duration
            let continuousEvent = CHHapticEvent(
                eventType: .hapticContinuous,
                parameters: [intensity, sharpness],
                relativeTime: 0,
                duration: 30000
            )
            
            // Create a pattern from the continuous haptic event
            let pattern = try CHHapticPattern(events: [continuousEvent], parameters: [])
            
            // Create a player from the continuous haptic pattern
            let player = try engine.makeAdvancedPlayer(with: pattern)
            continuousPlayers[playerId] = player
        } catch let error {
            print("[Tickle] Continuous player '\(playerId)' creation error: \(error)")
        }
    }

    /// Starts the continuous haptic player with the given ID.
    /// - If the player doesn't exist (not created or already destroyed), this is a safe no-op.
    /// - If haptics are globally disabled, this is a no-op.
    public func startContinuousPlayer(playerId: String) {
        guard hapticsEnabled else { return }
        
        lock.lock()
        defer { lock.unlock() }
        
        guard let player = continuousPlayers[playerId] else {
            // Player doesn't exist - this is safe, just a no-op
            // This can happen if start is called before create, or after destroy
            return
        }
        
        ensureEngineRunning()
        do {
            try player.start(atTime: CHHapticTimeImmediate)
        } catch let error {
            print("[Tickle] Error starting continuous player '\(playerId)': \(error)")
        }
    }

    /// Updates the continuous haptic player parameters.
    /// - If the player doesn't exist, this is a safe no-op.
    /// - Parameters can be updated even before start() - they will take effect when started.
    public func updateContinuousPlayer(playerId: String, intensityControl: Float, sharpnessControl: Float) {
        lock.lock()
        defer { lock.unlock() }
        
        guard let player = continuousPlayers[playerId] else {
            // Player doesn't exist - safe no-op
            return
        }
        
        // Parameters may be sent before start(); the engine must be running to
        // accept them, and may have auto-shut down since the last haptic.
        ensureEngineRunning()
        
        // Create dynamic parameters for the updated intensity & sharpness
        let intensityParameter = CHHapticDynamicParameter(
            parameterID: .hapticIntensityControl,
            value: intensityControl,
            relativeTime: 0
        )
        
        let sharpnessParameter = CHHapticDynamicParameter(
            parameterID: .hapticSharpnessControl,
            value: sharpnessControl,
            relativeTime: 0
        )
        
        // Send dynamic parameters to the haptic player
        do {
            try player.sendParameters([intensityParameter, sharpnessParameter], atTime: 0)
        } catch let error {
            print("[Tickle] Dynamic parameter error for player '\(playerId)': \(error)")
        }
    }

    /// Stops the continuous haptic player.
    /// - If the player doesn't exist or is already stopped, this is a safe no-op.
    public func stopContinuousPlayer(playerId: String) {
        lock.lock()
        defer { lock.unlock() }
        
        guard let player = continuousPlayers[playerId] else {
            // Player doesn't exist - safe no-op
            return
        }
        
        do {
            try player.stop(atTime: CHHapticTimeImmediate)
        } catch let error {
            print("[Tickle] Error stopping continuous player '\(playerId)': \(error)")
        }
    }
    
    /// Destroys the continuous haptic player and releases resources.
    /// - If the player doesn't exist (not created or already destroyed), this is a safe no-op.
    /// - The player will be stopped if it's currently playing.
    /// - The player configuration is also removed, so it won't be recreated after engine reinitialization.
    public func destroyContinuousPlayer(playerId: String) {
        lock.lock()
        defer { lock.unlock() }
        
        // Remove the stored config so this player won't be recreated after engine reinitialization
        continuousPlayerConfigs.removeValue(forKey: playerId)
        
        guard let player = continuousPlayers[playerId] else {
            // Player doesn't exist - safe no-op
            // This can happen if destroy is called before create, or called multiple times
            return
        }
        
        do {
            try player.stop(atTime: CHHapticTimeImmediate)
        } catch {
            // Ignore stop errors during destroy - player might already be stopped
        }
        continuousPlayers.removeValue(forKey: playerId)
    }
}

let haptics = HapticFeedback.shared
