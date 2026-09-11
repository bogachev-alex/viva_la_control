package viva.la.circle.model

/**
 * Short-press outcome for a volume key.
 * [Volume] = adjust music stream (or native pass-through when the key is not armed).
 */
sealed class VolumeShortAction {
    data object Volume : VolumeShortAction()

    data class Remap(
        val action: TargetAction,
        val specificPackage: String? = null,
    ) : VolumeShortAction() {
        init {
            require(action in ALLOWED_REMAPS) {
                "Volume short Remap only allows $ALLOWED_REMAPS, got $action"
            }
        }
    }

    companion object {
        const val VOLUME_STORED = "VOLUME"

        val ALLOWED_REMAPS: Set<TargetAction> = setOf(
            TargetAction.FLASHLIGHT,
            TargetAction.MUTE_TOGGLE,
            TargetAction.SCREENSHOT,
            TargetAction.SPECIFIC_APP,
        )

        fun fromStored(name: String?, specificPackage: String?): VolumeShortAction {
            if (name.isNullOrBlank() || name == VOLUME_STORED) return Volume
            val action = TargetAction.fromName(name, TargetAction.FLASHLIGHT)
            if (action !in ALLOWED_REMAPS) return Volume
            return Remap(action = action, specificPackage = specificPackage)
        }

        fun toStoredName(action: VolumeShortAction): String = when (action) {
            is Volume -> VOLUME_STORED
            is Remap -> action.action.name
        }

        fun toStoredPackage(action: VolumeShortAction): String? = when (action) {
            is Volume -> null
            is Remap -> action.specificPackage
        }
    }
}
