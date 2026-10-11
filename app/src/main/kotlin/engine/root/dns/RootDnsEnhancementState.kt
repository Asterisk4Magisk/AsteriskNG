// SPDX-License-Identifier: GPL-3.0
package engine.root.dns

internal enum class RootDnsEnhancementPhase(val wireValue: String) {
    Disabled("disabled"), Preparing("preparing"), Prepared("prepared"), Active("active"),
    Inactive("inactive"), Draining("draining"), Unsupported("unsupported"), Failed("failed"),
}

// Runtime evidence is never persisted as permission to activate a later cycle.
internal data class RootDnsEnhancementState(
    val required: Boolean,
    val phase: RootDnsEnhancementPhase,
    val instanceId: String,
    val cycleId: String,
    val generation: String,
    val reason: String?,
) {
    init {
        listOf(instanceId, cycleId, generation).forEach { value ->
            require(value.matches(Regex("0|[1-9][0-9]{0,19}")) && value.toULongOrNull() != null)
        }
        require(required || phase == RootDnsEnhancementPhase.Disabled)
        if (phase == RootDnsEnhancementPhase.Active) {
            require(required && instanceId != "0" && cycleId != "0" && generation != "0" && reason == null)
        }
    }

    val active: Boolean get() = phase == RootDnsEnhancementPhase.Active

    fun requireToken() {
        require(required && instanceId != "0" && cycleId != "0" && generation != "0")
    }
}
