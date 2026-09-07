# BlueLM Interceptor

Replaces Vivo's built-in BlueLM assistant with a chosen TargetAction. Shutter remap is extra, not the reason the product exists.

## Language

**BlueLM Interceptor**:
The app. Remaps BlueLM to a TargetAction; may also remap the Shutter.
_Avoid_: Hardware Button Interceptor

**BlueLM**:
Vivo's built-in assistant that this app remaps.
_Avoid_: Vivo Assistant, Copilot (for this assistant)

**Shutter**:
The camera-button role: on-body press, housing, and grip. One trigger, many keycodes, one TargetAction.
_Avoid_: Camera button, camera key, PRESS (those are keycodes or hardware, not the trigger)

**Remap**:
A trigger in, a TargetAction out.
_Avoid_: Intercept, steal, consume (consume is a mechanism, not the product idea)

**Pass-through**:
The trigger reaches the OS because this app did not remap it.
_Avoid_: Failed intercept, rejected key

**TargetAction**:
The configured outcome of a Remap.
_Avoid_: Intent, action (too generic)
