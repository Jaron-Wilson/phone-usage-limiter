package dev.jaronwilson.modes.launcher

/**
 * What a swipe in from the side of the home screen does.
 *
 * Two kinds. An app is simply launched, because a shortcut that opens a
 * launcher panel first would be slower than the icon it replaced. A site is
 * shown in a panel over the home screen: the point of putting finance on an
 * edge is to glance at a number, and going through the browser to do that
 * means a tab you then have to leave.
 *
 * Stored as one line of text so adding one needs no migration.
 */
sealed interface EdgeTarget {
    data class App(val packageName: String) : EdgeTarget
    data class Site(val url: String, val title: String) : EdgeTarget
}

enum class Edge { LEFT, RIGHT }

object EdgePanels {

    private const val FIELD = "\t"

    fun encode(target: EdgeTarget?): String = when (target) {
        null -> ""
        is EdgeTarget.App -> "app" + FIELD + target.packageName
        is EdgeTarget.Site -> "site" + FIELD + target.url + FIELD + target.title
    }

    fun decode(text: String): EdgeTarget? {
        val parts = text.split(FIELD)
        return when {
            parts.size >= 2 && parts[0] == "app" && parts[1].isNotBlank() ->
                EdgeTarget.App(parts[1].trim())
            parts.size >= 2 && parts[0] == "site" && parts[1].isNotBlank() ->
                EdgeTarget.Site(
                    url = normalise(parts[1]),
                    title = parts.getOrNull(2)?.trim().orEmpty().ifBlank { hostOf(parts[1]) }
                )
            else -> null
        }
    }

    /**
     * Typing "finance.jaronwilson.dev" should work. Anything without a scheme
     * gets https, never http: an edge panel is not the place to be downgraded.
     */
    fun normalise(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
            else -> "https://$trimmed"
        }
    }

    /** The bit worth showing as a title when none was given. */
    fun hostOf(raw: String): String =
        normalise(raw)
            .removePrefix("https://")
            .substringBefore('/')
            .removePrefix("www.")
            .ifBlank { "Site" }
}
