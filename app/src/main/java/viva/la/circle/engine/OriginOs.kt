package viva.la.circle.engine

/**
 * OriginOS version probe. `ro.vivo.os.name` reports Funtouch on OriginOS 6,
 * so display.id is preferred. `getprop` runs once and is cached — never on the key path.
 */
object OriginOs {
    data class Detection(val major: Int?, val label: String)

    @Volatile
    private var cached: Detection? = null

    fun parseMajor(displayId: String?, osVersion: String?): Int? {
        displayId
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.let { return it }
        osVersion
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?.let { return it - 10 }
        return null
    }

    fun label(displayId: String?, osVersion: String?, major: Int?): String {
        displayId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        major?.let { return "OriginOS $it" }
        osVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Vivo OS $it" }
        return "unknown"
    }

    fun detect(): Detection {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val displayId = readProp("ro.vivo.os.build.display.id")
            val osVersion = readProp("ro.vivo.os.version")
            val major = parseMajor(displayId, osVersion)
            val detected = Detection(major = major, label = label(displayId, osVersion, major))
            cached = detected
            return detected
        }
    }

    fun readProp(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            process.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun resetCacheForTests() {
        cached = null
    }
}
