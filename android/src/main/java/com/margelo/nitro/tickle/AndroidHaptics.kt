package com.margelo.nitro.tickle

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.margelo.nitro.NitroModules
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Plays Tickle's Core Haptics model on Android's Vibrator.
 *
 * Core Haptics holds one continuous vibration whose intensity and sharpness can
 * change at any moment. Most Android phones cannot, so a continuous player is
 * played as grain instead: a stream of very short vibrations whose strength and
 * spacing follow the player's intensity. How each grain is made depends on what
 * the phone's vibrator can do:
 *
 * - [Tier.PRIMITIVES]: composition primitives, scaled by intensity and picked by
 *   sharpness (recent Pixel and Galaxy phones).
 * - [Tier.AMPLITUDE]: one-shots at the intensity's amplitude, shorter when sharper.
 * - [Tier.BASIC]: no strength control at all. A motor driven only briefly never
 *   reaches full swing, so the pulse's length carries the strength, and faint
 *   textures also thin out.
 *
 * Without primitives, transients use the vendor's predefined effects, which are
 * braked and crisper than a one-shot.
 *
 * Every vibrator call and all player state live on one haptics thread. The public
 * functions only post to it, so a caller on the UI thread never blocks on a
 * binder call, and nothing here needs a lock.
 */
internal object AndroidHaptics {
  private enum class Tier { PRIMITIVES, AMPLITUDE, BASIC }

  private class Player(val baseIntensity: Float, val baseSharpness: Float) {
    var intensityControl = 1f
    var sharpnessControl = 0f
    var playing = false

    // Core Haptics multiplies intensity by its control and adds sharpness's.
    val intensity get() = (baseIntensity * intensityControl).coerceIn(0f, 1f)
    val sharpness get() = (baseSharpness + sharpnessControl).coerceIn(0f, 1f)
  }

  // A player below this is silent, as Core Haptics is in practice.
  private const val AUDIBLE = 0.01f
  // Grains per second for players with strength control, faint to full.
  private const val SLOW_GRAINS = 14f
  private const val FAST_GRAINS = 40f
  // Each gap varies by up to this share, so a stream feels like a material, not a motor.
  private const val JITTER = 0.2f
  // A player quiet for longer than this starts again with an immediate grain.
  private const val FRESH_MS = 120L
  // A transient keeps grains back this long so the next one does not cut it short.
  private const val TRANSIENT_HOLD_MS = 45L
  // Pulse lengths for the tiers without primitives.
  private const val BASIC_SHORTEST_MS = 3f
  private const val BASIC_LONGEST_MS = 12f
  private const val SOFT_PULSE_MS = 14f
  private const val SHARP_PULSE_MS = 5f
  // A pattern's continuous event without a duration plays for this long, as on iOS.
  private const val DEFAULT_DURATION_MS = 1000.0
  private val PATTERN = Any()

  @Volatile var enabled = true

  private val handler: Handler by lazy {
    val thread = HandlerThread("TickleHaptics", Process.THREAD_PRIORITY_DISPLAY)
    thread.start()
    Handler(thread.looper)
  }

  // Everything below is touched only on the haptics thread.
  private var vibrator: Vibrator? = null
  private var tier = Tier.BASIC
  private var lowTick = false
  private var running = false
  private val players = HashMap<String, Player>()
  // Grains per second at the last tick; zero while nothing is audible.
  private var rate = 0f
  private var phase = 1f
  private var lastStep = 0L
  private var quietSince = 0L
  private var heard = false
  private var holdUntil = 0L

  fun initializeEngine() = handler.post { running = connect() }

  fun destroyEngine() =
    handler.post {
      running = false
      players.clear()
      handler.removeCallbacksAndMessages(PATTERN)
      tick()
      vibrator?.cancel()
    }

  fun stopAll() = handler.post { handler.removeCallbacksAndMessages(PATTERN) }

  fun createPlayer(id: String, intensity: Double, sharpness: Double) =
    handler.post { if (running) players[id] = Player(intensity.toFloat(), sharpness.toFloat()) }

  fun startPlayer(id: String) =
    handler.post {
      val player = players[id] ?: return@post
      if (!enabled) return@post
      player.playing = true
      tick()
    }

  fun updatePlayer(id: String, intensity: Double, sharpness: Double) =
    handler.post {
      val player = players[id] ?: return@post
      player.intensityControl = intensity.toFloat()
      player.sharpnessControl = sharpness.toFloat()
      tick()
    }

  fun stopPlayer(id: String) =
    handler.post {
      players[id]?.playing = false
      tick()
    }

  fun destroyPlayer(id: String) =
    handler.post {
      players.remove(id)
      tick()
    }

  /** One transient now, for the system impact, notification and selection cues. */
  fun tap(intensity: Float, sharpness: Float) = taps(floatArrayOf(0f, intensity, sharpness))

  /** Transients as (delay ms, intensity, sharpness) triples. */
  fun taps(triples: FloatArray) =
    handler.post {
      if (!enabled || !connect()) return@post
      val start = SystemClock.uptimeMillis()
      for (i in triples.indices step 3) {
        val at = start + triples[i].toLong()
        handler.postAtTime({ transient(triples[i + 1], triples[i + 2]) }, PATTERN, at)
      }
    }

  /**
   * Schedules a pattern's transients as they are and its continuous events as
   * grain streams, both shaped by the pattern's parameter curves.
   */
  fun play(events: Array<HapticEvent>, curves: Array<HapticCurve>) =
    handler.post {
      if (!enabled || !running) return@post
      val start = SystemClock.uptimeMillis()
      for (event in events) {
        val intensity = event.value(HapticParameterType.INTENSITY, 1.0)
        val sharpness = event.value(HapticParameterType.SHARPNESS, 0.5)
        fun at(time: Double) =
          Pair(
            (intensity * control(curves, HapticParameterType.INTENSITY, time, 1.0)).toFloat(),
            (sharpness + control(curves, HapticParameterType.SHARPNESS, time, 0.0)).toFloat(),
          )
        if (event.type == HapticEventType.TRANSIENT) {
          val (i, s) = at(event.relativeTime)
          handler.postAtTime({ transient(i, s) }, PATTERN, start + event.relativeTime.toLong())
          continue
        }
        val end = event.relativeTime + (event.duration ?: DEFAULT_DURATION_MS)
        var time = event.relativeTime
        while (time < end) {
          val (i, s) = at(time)
          val level = sqrt(i.coerceIn(0f, 1f))
          if (i >= AUDIBLE) handler.postAtTime({ grain(i, s) }, PATTERN, start + time.toLong())
          time += 1000.0 / max(grainRate(level), SLOW_GRAINS) * jitter()
        }
      }
    }

  private fun HapticEvent.value(type: HapticParameterType, fallback: Double) =
    parameters.firstOrNull { it.type == type }?.value ?: fallback

  // A curve's control at a pattern time: linear between its points, the default outside them.
  private fun control(
    curves: Array<HapticCurve>,
    type: HapticParameterType,
    time: Double,
    fallback: Double,
  ): Double {
    val curve = curves.firstOrNull { it.type == type } ?: return fallback
    val points = curve.controlPoints
    if (points.isEmpty()) return fallback
    val t = time - curve.relativeTime
    if (t < points.first().relativeTime || t > points.last().relativeTime) return fallback
    for (k in 1 until points.size) {
      val a = points[k - 1]
      val b = points[k]
      if (t <= b.relativeTime) {
        val span = b.relativeTime - a.relativeTime
        return if (span <= 0) b.value else a.value + (b.value - a.value) * (t - a.relativeTime) / span
      }
    }
    return points.last().value
  }

  /**
   * The grain clock. It runs when a player changes and when the next grain is
   * due, and sleeps while nothing is audible, so a steady texture wakes this
   * thread only for its grains and its updates.
   */
  private fun tick() {
    val now = SystemClock.uptimeMillis()
    phase = min(1f, phase + (now - lastStep) / 1000f * rate)
    lastStep = now
    handler.removeCallbacks(step)
    var loudest: Player? = null
    for (player in players.values) {
      if (player.playing && player.intensity > (loudest?.intensity ?: 0f)) loudest = player
    }
    val intensity = if (running) loudest?.intensity ?: 0f else 0f
    if (loudest == null || intensity < AUDIBLE) {
      if (heard) quietSince = now
      heard = false
      rate = 0f
      return
    }
    // A fresh touch is felt at once; a signal flickering between frames keeps its rhythm.
    if (!heard && now - quietSince > FRESH_MS) phase = 1f
    heard = true
    rate = grainRate(sqrt(intensity))
    if (phase >= 1f && now >= holdUntil) {
      grain(intensity, loudest.sharpness)
      phase = (Random.nextFloat() * 2 - 1) * JITTER
    }
    val wait = if (phase >= 1f) holdUntil - now else ((1f - phase) / rate * 1000f).toLong()
    handler.postDelayed(step, max(1L, wait))
  }

  private val step = Runnable { tick() }

  // Faint grains thin out on a motor that cannot soften them.
  private fun grainRate(level: Float) =
    if (tier == Tier.BASIC) FAST_GRAINS * level else SLOW_GRAINS + (FAST_GRAINS - SLOW_GRAINS) * level

  private fun jitter() = 1 + (Random.nextFloat() * 2 - 1) * JITTER

  // A grain is far shorter than the vibration it stands in for, so its strength
  // follows the square root of intensity: the textures sit between 0.05 and 0.4.
  private fun grain(intensity: Float, sharpness: Float) {
    val level = sqrt(intensity.coerceIn(0f, 1f))
    val effect =
      when (tier) {
        Tier.PRIMITIVES -> {
          val primitive =
            when {
              sharpness < 0.35f && lowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
              sharpness < 0.7f -> VibrationEffect.Composition.PRIMITIVE_TICK
              else -> VibrationEffect.Composition.PRIMITIVE_CLICK
            }
          primitive(primitive, level)
        }
        Tier.AMPLITUDE ->
          oneShot(SOFT_PULSE_MS + (SHARP_PULSE_MS - SOFT_PULSE_MS) * sharpness, amplitude(level))
        Tier.BASIC ->
          oneShot(
            BASIC_SHORTEST_MS + (BASIC_LONGEST_MS - BASIC_SHORTEST_MS) * level,
            VibrationEffect.DEFAULT_AMPLITUDE,
          )
      }
    vibrate(effect ?: return)
  }

  private fun transient(intensity: Float, sharpness: Float) {
    if (intensity < AUDIBLE) return
    val level = sqrt(intensity.coerceIn(0f, 1f))
    val effect =
      when {
        tier == Tier.PRIMITIVES -> {
          val primitive =
            if (intensity < 0.4f && sharpness < 0.35f && lowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            else if (intensity < 0.4f) VibrationEffect.Composition.PRIMITIVE_TICK
            else VibrationEffect.Composition.PRIMITIVE_CLICK
          primitive(primitive, level)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
          VibrationEffect.createPredefined(
            when {
              intensity < 0.4f -> VibrationEffect.EFFECT_TICK
              intensity < 0.75f -> VibrationEffect.EFFECT_CLICK
              else -> VibrationEffect.EFFECT_HEAVY_CLICK
            },
          )
        else -> oneShot(10f + 15f * level, amplitude(level))
      }
    vibrate(effect ?: return)
    holdUntil = SystemClock.uptimeMillis() + TRANSIENT_HOLD_MS
  }

  // Primitives exist from Android 11; the tier is only PRIMITIVES there.
  private fun primitive(id: Int, scale: Float) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      VibrationEffect.startComposition().addPrimitive(id, scale).compose()
    } else {
      null
    }

  private fun amplitude(level: Float) =
    if (tier == Tier.BASIC) VibrationEffect.DEFAULT_AMPLITUDE else (1 + 254 * level).roundToInt()

  private fun oneShot(ms: Float, amplitude: Int) =
    VibrationEffect.createOneShot(max(1, ms.roundToInt()).toLong(), amplitude)

  private fun vibrate(effect: VibrationEffect) {
    val target = vibrator ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        target.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
      } else {
        @Suppress("DEPRECATION")
        target.vibrate(effect, TOUCH)
      }
    } catch (_: RuntimeException) {
      // A vibrator that rejects an effect leaves the toy silent rather than crashing it.
    }
  }

  private val TOUCH =
    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()

  // Finds the vibrator and what it can do. False when there is none to drive.
  private fun connect(): Boolean {
    vibrator?.let { return true }
    val context = NitroModules.applicationContext ?: return false
    val found =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
    if (found == null || !found.hasVibrator()) return false
    tier =
      when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          found.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
          ) -> Tier.PRIMITIVES
        found.hasAmplitudeControl() -> Tier.AMPLITUDE
        else -> Tier.BASIC
      }
    lowTick =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        found.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    vibrator = found
    return true
  }
}
