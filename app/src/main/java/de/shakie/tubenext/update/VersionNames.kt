package de.shakie.tubenext.update

object VersionNames {
    fun normalize(raw: String): String {
        return raw
            .trim()
            .removePrefix("refs/tags/")
            .removePrefix("release/")
            .removePrefix("v")
            .substringBefore("-")
    }

    fun compare(candidate: String, current: String): Int {
        val left = parts(candidate)
        val right = parts(current)
        val max = maxOf(left.size, right.size)
        for (index in 0 until max) {
            val l = left.getOrNull(index) ?: 0
            val r = right.getOrNull(index) ?: 0
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun parts(raw: String): List<Int> {
        return normalize(raw)
            .split(".")
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
