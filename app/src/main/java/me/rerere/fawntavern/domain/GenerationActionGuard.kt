package me.rerere.fawntavern.domain

/** Central policy for actions that must not mutate chat/configuration during generation. */
internal object GenerationActionGuard {
    fun allowsMutation(generating: Boolean): Boolean = !generating
}
