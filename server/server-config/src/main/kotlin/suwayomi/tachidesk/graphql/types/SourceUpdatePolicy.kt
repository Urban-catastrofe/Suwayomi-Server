package suwayomi.tachidesk.graphql.types

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SourceUpdatePolicy(
    @ProtoNumber(1) val sourceId: String,
    @ProtoNumber(2) val minDelaySeconds: Int = 60,
    @ProtoNumber(3) val maxDelaySeconds: Int = 300,
    @ProtoNumber(4) val initialCooldownSeconds: Int = 1800,
    @ProtoNumber(5) val maxCooldownSeconds: Int = 21600,
) {
    companion object {
        fun validate(policies: List<SourceUpdatePolicy>): String? {
            if (policies.distinctBy { it.sourceId }.size != policies.size) {
                return "Only one update policy can be configured per source"
            }

            return policies.firstNotNullOfOrNull { policy ->
                val id = policy.sourceId.toLongOrNull()
                when {
                    id == null || id <= 0 || id.toString() != policy.sourceId -> "sourceId must be a positive numeric source ID"
                    policy.minDelaySeconds < 0 -> "minDelaySeconds must be at least 0"
                    policy.maxDelaySeconds < policy.minDelaySeconds -> "maxDelaySeconds must be at least minDelaySeconds"
                    policy.initialCooldownSeconds <= 0 -> "initialCooldownSeconds must be greater than 0"
                    policy.maxCooldownSeconds < policy.initialCooldownSeconds -> "maxCooldownSeconds must be >= initialCooldownSeconds"
                    else -> null
                }
            }
        }
    }
}
