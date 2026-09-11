package dev.jaronwilson.modes.commute

/**
 * Places worth one tap: home, work, school, the gym.
 *
 * Stored as text rather than a table so adding them needs no migration, and
 * kept deliberately dumb: a label and whatever you would have typed into the
 * search box.
 *
 * Note what this is not. Google Maps knows your saved Home and Work and gives
 * no way to read them, so these are yours and live here. An app could instead
 * drive the Maps interface through an accessibility service and press the
 * buttons for you, which would mean that service being able to read everything
 * on your screen, in every app, forever, to save typing an address once. The
 * navigation intent does the same job and cannot be broken by a Maps redesign.
 */
data class Destination(val label: String, val query: String) {
    val isValid: Boolean get() = label.isNotBlank() && query.isNotBlank()
}

object Destinations {

    /** A tab: absent from addresses and from anything anyone types as a label. */
    private const val FIELD = "\t"

    /** Suggested starting points, which are also what Maps tends to resolve. */
    val SUGGESTED = listOf("Home", "Work", "School", "Gym")

    fun encode(list: List<Destination>): String =
        list.filter { it.isValid }.joinToString("\n") { it.label + FIELD + it.query }

    fun decode(text: String): List<Destination> =
        text.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(FIELD)
                if (parts.size != 2) return@mapNotNull null
                Destination(parts[0].trim(), parts[1].trim()).takeIf { it.isValid }
            }
            .toList()

    /**
     * Adding one, replacing any existing entry with the same label so a second
     * "Home" cannot appear.
     */
    fun upsert(list: List<Destination>, destination: Destination): List<Destination> {
        // Trimmed here rather than at the call site: a label padded with spaces
        // would otherwise survive until it round-tripped through storage, and
        // "Home " would not match "Home".
        val clean = Destination(destination.label.trim(), destination.query.trim())
        if (!clean.isValid) return list
        val without = list.filterNot { it.label.equals(clean.label, ignoreCase = true) }
        return without + clean
    }

    fun remove(list: List<Destination>, label: String): List<Destination> =
        list.filterNot { it.label.equals(label, ignoreCase = true) }

    fun find(list: List<Destination>, label: String): Destination? =
        list.firstOrNull { it.label.equals(label, ignoreCase = true) }
}
