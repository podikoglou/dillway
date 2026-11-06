package com.dillway

/**
 * The optional words of `/dill rec save <id> [label] [confirm]`.
 *
 * `confirm` keeps its old meaning wherever it appears; any other word is the
 * label. Two labels are ambiguous, so [parse] returns null for them.
 */
data class SaveArguments(val label: String?, val confirmed: Boolean) {

    companion object {
        private const val CONFIRM = "confirm"

        fun parse(first: String?, second: String?): SaveArguments? {
            val args = listOfNotNull(first, second)
            val labels = args.filterNot { it.equals(CONFIRM, ignoreCase = true) }
            if (labels.size > 1) return null

            return SaveArguments(
                label = labels.firstOrNull(),
                confirmed = args.any { it.equals(CONFIRM, ignoreCase = true) },
            )
        }
    }
}
