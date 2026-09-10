# BlueLM Interceptor

Replaces Vivo's built-in BlueLM assistant with a chosen TargetAction. Shutter remap is extra, not the reason the product exists.

## Language

**BlueLM Interceptor**:
The app. Remaps BlueLM to a TargetAction; may also remap the Shutter.
_Avoid_: Hardware Button Interceptor

**BlueLM**:
Vivo's built-in assistant that this app remaps.
_Avoid_: Vivo Assistant, Copilot (for this assistant)

**Intercepted assistant**:
The OEM assistant this app Remaps (BlueLM, Celia, and other vendor wake UIs).
_Avoid_: Remap target assistant, Copilot (as the generic term), OEM assistant

**Wake UI**:
The intercept surface of an Intercepted assistant that should Remap (float/chat overlays, not settings).
_Avoid_: Copilot wake, primary UI

**Secondary UI**:
An Intercepted assistant surface that must Pass-through (settings, gallery, in-assistant search).
_Avoid_: Copilot secondary, non-wake UI

**Shutter**:
The camera-button role: on-body press, housing, and grip. One trigger, many keycodes, one TargetAction.
_Avoid_: Camera button, camera key, PRESS (those are keycodes or hardware, not the trigger)

**Remap**:
A trigger in, a TargetAction out.
_Avoid_: Intercept, steal, consume (consume is a mechanism, not the product idea)

**Remap session**:
One BlueLM wake from dismiss through firing the TargetAction.
_Avoid_: Dismiss-and-fire, wake Remap, intercept session

**Pass-through**:
The trigger reaches the OS because this app did not remap it.
_Avoid_: Failed intercept, rejected key

**TargetAction**:
The configured outcome of a Remap.
_Avoid_: Intent, action (too generic)
