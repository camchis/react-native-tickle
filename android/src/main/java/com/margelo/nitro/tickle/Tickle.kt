package com.margelo.nitro.tickle
  
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class Tickle : HybridTickleSpec() {
  override val memorySize: Long
    get() = 0L

  override fun startHaptic(events: Array<HapticEvent>, curves: Array<HapticCurve>) {
    AndroidHaptics.play(events, curves)
  }

  override fun stopAllHaptics() {
    AndroidHaptics.stopAll()
  }

  override fun initializeEngine() {
    AndroidHaptics.initializeEngine()
  }

  override fun destroyEngine() {
    AndroidHaptics.destroyEngine()
  }

  override fun createContinuousPlayer(playerId: String, initialIntensity: Double, initialSharpness: Double) {
    AndroidHaptics.createPlayer(playerId, initialIntensity, initialSharpness)
  }

  override fun startContinuousPlayer(playerId: String) {
    AndroidHaptics.startPlayer(playerId)
  }

  override fun updateContinuousPlayer(playerId: String, intensityControl: Double, sharpnessControl: Double) {
    AndroidHaptics.updatePlayer(playerId, intensityControl, sharpnessControl)
  }

  override fun stopContinuousPlayer(playerId: String) {
    AndroidHaptics.stopPlayer(playerId)
  }
  
  override fun destroyContinuousPlayer(playerId: String) {
    AndroidHaptics.destroyPlayer(playerId)
  }

  // MARK: - Global Haptics Enable/Disable

  override fun setHapticsEnabled(enabled: Boolean) {
    AndroidHaptics.enabled = enabled
  }

  override fun getHapticsEnabled(): Boolean {
    return AndroidHaptics.enabled
  }

  // MARK: - System Haptics (Predefined OS-level feedback)
  // Each cue is the intensity and sharpness of its UIKit counterpart.

  override fun triggerImpact(style: HapticImpactStyle) {
    when (style) {
      HapticImpactStyle.SOFT -> AndroidHaptics.tap(0.35f, 0.2f)
      HapticImpactStyle.LIGHT -> AndroidHaptics.tap(0.3f, 0.6f)
      HapticImpactStyle.MEDIUM -> AndroidHaptics.tap(0.6f, 0.6f)
      HapticImpactStyle.RIGID -> AndroidHaptics.tap(0.7f, 1f)
      HapticImpactStyle.HEAVY -> AndroidHaptics.tap(0.9f, 0.4f)
    }
  }

  override fun triggerNotification(type: HapticNotificationType) {
    // (delay ms, intensity, sharpness) per tap.
    when (type) {
      HapticNotificationType.SUCCESS -> AndroidHaptics.taps(floatArrayOf(0f, 0.5f, 0.6f, 100f, 0.8f, 0.6f))
      HapticNotificationType.WARNING -> AndroidHaptics.taps(floatArrayOf(0f, 0.8f, 0.6f, 130f, 0.5f, 0.6f))
      HapticNotificationType.ERROR ->
        AndroidHaptics.taps(floatArrayOf(0f, 0.7f, 0.6f, 100f, 0.7f, 0.6f, 200f, 0.9f, 0.6f))
    }
  }

  override fun triggerSelection() {
    AndroidHaptics.tap(0.3f, 0.8f)
  }
}
