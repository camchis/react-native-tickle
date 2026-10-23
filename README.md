# @camchis/react-native-tickle

[![npm version](https://img.shields.io/npm/v/@camchis/react-native-tickle?logo=npm)](https://www.npmjs.com/package/@camchis/react-native-tickle)
[![npm downloads](https://img.shields.io/npm/dm/@camchis/react-native-tickle?logo=npm)](https://www.npmjs.com/package/@camchis/react-native-tickle)
[![GitHub release](https://img.shields.io/github/v/release/camchis/react-native-tickle?logo=github)](https://github.com/camchis/react-native-tickle/releases)
[![license](https://img.shields.io/npm/l/@camchis/react-native-tickle)](LICENSE)

AHAP-style haptics (transient + continuous) on top of [Nitro Modules](https://nitro.margelo.com/) — the core functions are **UI-thread friendly** (`'worklet'`).

> A fork of [@renegades/react-native-tickle](https://github.com/Renegades-Studio/react-native-tickle). It adds an Android implementation and generates its bindings with Nitro 0.37. The API is unchanged, so switching is a change of package name. Issues and PRs are welcome.

## Why this package?

Complete flexibility over iOS haptics with synchronous UI-thread execution, and an Android player that follows the same model.

## Android

Core Haptics holds one continuous vibration whose intensity and sharpness can change at any moment; most Android vibrators cannot. So on Android a continuous player plays as grain: a stream of very short vibrations whose strength and spacing follow the player's intensity. How a grain is made depends on what the vibrator reports:

- **Composition primitives** (recent Pixel and Galaxy phones): `LOW_TICK`, `TICK` or `CLICK` by sharpness, scaled by intensity.
- **Amplitude control only**: one-shots at the intensity's amplitude, shorter when sharper.
- **Neither**: short full-power one-shots whose length carries the strength, and faint textures thin out.

Transients use primitives where there are any, otherwise the predefined `TICK`, `CLICK` and `HEAVY_CLICK` effects. Patterns play their transients as they are and their continuous events as grain streams shaped by their curves. Every vibrator call runs on one haptics thread, so calling Tickle from the UI thread never blocks on a binder call. Vibrations use the touch usage, so Android's touch-vibration setting also silences them. The library adds the `VIBRATE` permission to your manifest.

It is an approximation of Core Haptics, not a port: tune textures on an iPhone, then check that they read well on a couple of Android phones.

## Installation

```sh
npm i @camchis/react-native-tickle react-native-nitro-modules
```

## Concepts (what to use when)

- **Transient**: Instant "click/tap" events. No duration — you trigger them at a point in time.
- **Continuous (pattern)**: Time-based patterns you _can_ define ahead of time. You provide **events** (with `duration`) and optionally **curves** (automation over time).
- **Continuous player (real-time)**: For _unpredictable_ input (gesture position, scroll velocity, real-time data). You create a player once, then **start → update (many times) → stop**.

### Why `events[]` and `curves[]` are separate

On iOS Core Haptics, a pattern is made of two different building blocks:

- **Events**: things that happen (transient "ticks" or continuous segments) at a `relativeTime`, with base `intensity`/`sharpness`.
- **Curves**: how parameters (intensity/sharpness) evolve over time via control points, independent of "what event" is currently playing.

They're separate because they're different object types in Core Haptics (events vs parameter curves) and they serve different jobs: **events define the structure**, **curves define the modulation**. You often combine both in one pattern.

## Usage

### Haptic "provider" (recommended)

Wrap your app inside `HapticProvider`. This initializes the engine and automatically destroys it when the app goes to background.

```tsx
import { HapticProvider } from '@camchis/react-native-tickle';

export function App() {
  return <HapticProvider>{/* {Rest of your app} */}</HapticProvider>;
}
```

### `startHaptic(events, curves)` (transient + continuous patterns)

Play a transient:

```ts
import { startHaptic } from '@camchis/react-native-tickle';

startHaptic(
  [
    {
      type: 'transient',
      relativeTime: 0,
      parameters: [
        { type: 'intensity', value: 1 },
        { type: 'sharpness', value: 0.5 },
      ],
    },
  ],
  []
);
```

Play a continuous pattern (events + curves together):

```ts
import { startHaptic } from '@camchis/react-native-tickle';

startHaptic(
  [
    {
      type: 'continuous',
      relativeTime: 0,
      duration: 1200,
      parameters: [
        { type: 'intensity', value: 0.2 },
        { type: 'sharpness', value: 0.5 },
      ],
    },
  ],
  [
    {
      type: 'intensity',
      relativeTime: 0,
      controlPoints: [
        { relativeTime: 0, value: 0.2 },
        { relativeTime: 600, value: 1.0 },
        { relativeTime: 1200, value: 0.2 },
      ],
    },
  ]
);
```

Combine transient + continuous in one pattern:

```ts
import { startHaptic } from '@camchis/react-native-tickle';

startHaptic(
  [
    {
      type: 'transient',
      relativeTime: 0,
      parameters: [
        { type: 'intensity', value: 1 },
        { type: 'sharpness', value: 0.8 },
      ],
    },
    {
      type: 'continuous',
      relativeTime: 50,
      duration: 800,
      parameters: [
        { type: 'intensity', value: 0.2 },
        { type: 'sharpness', value: 0.4 },
      ],
    },
  ],
  []
);
```

### Real-time continuous mode (continuous player)

Use this when you _can't_ predefine a pattern. You start the player, update it in real time, then stop it.

```tsx
import { useContinuousPlayer } from '@camchis/react-native-tickle';

function MyComponent() {
  const { start, stop, update } = useContinuousPlayer('my-player', 1.0, 0.5);

  const gesture = Gesture.Pan()
    .onBegin(() => {
      start();
    })
    .onUpdate((e) => {
      update(e.translationY / 100, 0.5);
    })
    .onEnd(() => {
      stop();
    });
}
```

### Stop everything

If users can navigate away from a screen while haptics are still playing, call `stopAllHaptics()` in your cleanup to stop them.

You can use the `beforeRemove` event of your navigation library to do it globally for all screens or particular screen blurs. Here's how to do it with expo router:

```tsx
<Stack
  screenListeners={() => ({
    beforeRemove: () => {
      stopAllHaptics()
    },
  })}
/>
// Or
<Stack.Screen
  listeners={() => ({
    beforeRemove: () => {
      stopAllHaptics()
    },
  })}
/>
```

```ts
import { stopAllHaptics } from '@camchis/react-native-tickle';
import { useEffect } from 'react';

export function SomeScreen() {
  useEffect(() => () => stopAllHaptics(), []);
  return null;
}
```

### Global enable/disable (settings toggle)

Disable haptics globally for users who prefer no haptic feedback. The setting is **persisted** across app restarts. When disabled, all haptic calls become no-ops.

```tsx
import { useHapticsEnabled } from '@camchis/react-native-tickle';

function SettingsScreen() {
  const [hapticsEnabled, setHapticsEnabled] = useHapticsEnabled();

  return <Switch value={hapticsEnabled} onValueChange={setHapticsEnabled} />;
}
```

### System haptics (predefined OS-level feedback)

While the main purpose of this package is to support AHAP-style patterns (transient + continuous haptics with curves), system haptics are also available for completeness. These are simple, predefined OS-level feedback types that don't require pattern definitions. These methods are also UI-thread friendly.

```ts
import {
  triggerImpact,
  triggerNotification,
  triggerSelection,
} from '@camchis/react-native-tickle';

// Impact feedback - simulates a physical collision
triggerImpact('light'); // 'light' | 'medium' | 'heavy' | 'soft' | 'rigid'

// Notification feedback - for alerts and status updates
triggerNotification('success'); // 'success' | 'warning' | 'error'

// Selection feedback - for picker wheels and toggles
triggerSelection();
```

### Advanced control

For fine-grained control over the haptic API, you can opt out of the managed hooks and call the underlying functions directly:

- **Engine lifecycle**: `initializeEngine()` / `destroyEngine()` instead of `HapticProvider`
- **Enable/disable toggle**: `setHapticsEnabled()` / `getHapticsEnabled()` instead of `useHapticsEnabled()`
- **Continuous player**: `createContinuousPlayer()` / `startContinuousPlayer()` / `updateContinuousPlayer()` / `stopContinuousPlayer()` / `destroyContinuousPlayer()` instead of `useContinuousPlayer()`

## API

| Function                                                               | Purpose                                                         |
| ---------------------------------------------------------------------- | --------------------------------------------------------------- |
| `HapticProvider`                                                       | Component that initializes engine; destroys on background       |
| `useHapticEngine()`                                                    | Hook to manage engine lifecycle (used internally by provider)   |
| `initializeEngine()` / `destroyEngine()`                               | Manual engine lifecycle                                         |
| `startHaptic(events, curves)`                                          | Play a pattern (transient + continuous events, optional curves) |
| `stopAllHaptics()`                                                     | Stop any running haptics (useful on unmount/navigation)         |
| `useHapticsEnabled()`                                                  | Hook for reactive haptics enabled state                         |
| `setHapticsEnabled(enabled)` / `getHapticsEnabled()`                   | Manual enable/disable toggle (persisted)                        |
| `useContinuousPlayer(playerId, initialIntensity, initialSharpness)`    | Hook to manage a continuous player lifecycle                    |
| `createContinuousPlayer(playerId, initialIntensity, initialSharpness)` | Create a continuous player with given ID                        |
| `startContinuousPlayer(playerId)` / `stopContinuousPlayer(playerId)`   | Start/stop continuous playback for player                       |
| `updateContinuousPlayer(playerId, intensityControl, sharpnessControl)` | Update intensity/sharpness for player                           |
| `destroyContinuousPlayer(playerId)`                                    | Destroy player and release resources                            |
| `triggerImpact(style)`                                                 | Trigger impact feedback (collision simulation)                  |
| `triggerNotification(type)`                                            | Trigger notification feedback (alerts/status)                   |
| `triggerSelection()`                                                   | Trigger selection feedback (pickers/toggles)                    |

## Types (inputs)

| Type                     | Values                                                |
| ------------------------ | ----------------------------------------------------- |
| `HapticParameterType`    | `'intensity' \| 'sharpness'`                          |
| `HapticImpactStyle`      | `'light' \| 'medium' \| 'heavy' \| 'soft' \| 'rigid'` |
| `HapticNotificationType` | `'success' \| 'warning' \| 'error'`                   |

| `HapticEventParameter` | Type                  |
| ---------------------- | --------------------- |
| `type`                 | `HapticParameterType` |
| `value`                | `number`              |

**`HapticEvent`** (discriminated union)

- **Transient:**

  - `type`: `'transient'`
  - `relativeTime`: `number`
  - `parameters`: `HapticEventParameter[]`

- **Continuous:**
  - `type`: `'continuous'`
  - `relativeTime`: `number`
  - `duration`: `number`
  - `parameters`: `HapticEventParameter[]`

| `HapticCurveControlPoint` | Type     |
| ------------------------- | -------- |
| `relativeTime`            | `number` |
| `value`                   | `number` |

| `HapticCurve`   | Type                        |
| --------------- | --------------------------- |
| `type`          | `HapticCurveType`           |
| `relativeTime`  | `number`                    |
| `controlPoints` | `HapticCurveControlPoint[]` |

## Limitations

### Parameter curves affect all events in a pattern

In CoreHaptics, `CHHapticParameterCurve` uses `hapticIntensityControl` and `hapticSharpnessControl` — these are **pattern-level multipliers**, not per-event modifiers. Any curve you define will multiply the intensity/sharpness of **all events** playing at that moment, including transients.

**Example problem:**

```ts
startHaptic(
  [
    { type: 'continuous', relativeTime: 0, duration: 2000, parameters: [...] },
    { type: 'transient', relativeTime: 1000, parameters: [{ type: 'intensity', value: 1.0 }, ...] },
  ],
  [
    { type: 'intensity', relativeTime: 0, controlPoints: [
      { relativeTime: 0, value: 1.0 },
      { relativeTime: 1000, value: 0.3 },  // At t=1000ms, intensity control = 0.3
      { relativeTime: 2000, value: 0.3 },
    ]},
  ]
);
```

The transient at `t=1000ms` has base intensity `1.0`, but the curve sets `intensityControl=0.3` at that moment. **Effective intensity: 1.0 × 0.3 = 0.3**. The transient feels weaker than expected.

**Workaround:** Play continuous and transient events in separate `startHaptic()` calls:

```ts
// Continuous with curves
startHaptic(continuousEvents, curves);

// Transients without curves (separate pattern)
startHaptic(transientEvents, []);
```

Each call creates an isolated pattern/player — curves from one won't affect events in the other.

> **Note:** The library automatically resets control values to `1.0` at the end of each continuous event, so transients **after** a continuous event finishes are not affected. This limitation only applies to transients **during** a continuous event with active curves.

## Acknowledgment

Tickle is by [Renegades](https://renegades.xyz); this fork only adds to their work.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
