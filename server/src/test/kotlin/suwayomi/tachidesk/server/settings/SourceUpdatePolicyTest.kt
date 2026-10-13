package suwayomi.tachidesk.server.settings

import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.toSchema
import com.typesafe.config.ConfigFactory
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import io.github.config4k.extract
import io.github.config4k.toConfig
import kotlinx.serialization.protobuf.ProtoBuf
import suwayomi.tachidesk.graphql.mutations.SettingsMutation
import suwayomi.tachidesk.graphql.queries.SettingsQuery
import suwayomi.tachidesk.graphql.server.CustomSchemaGeneratorHooks
import suwayomi.tachidesk.graphql.types.SourceUpdatePolicy
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupServerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceUpdatePolicyTest {
    private val policy = SourceUpdatePolicy("9223372036854775807")

    @Test
    fun `source policies round trip through config and server settings backups`() {
        val policies = listOf(policy, SourceUpdatePolicy("123", minDelaySeconds = 0, maxDelaySeconds = 0))
        assertEquals(policies, policies.toConfig("policies").extract<List<SourceUpdatePolicy>>("policies"))
        val minimal = ConfigFactory.parseString("""policies = [{ sourceId = "123" }]""")
        assertEquals(listOf(SourceUpdatePolicy("123")), minimal.extract<List<SourceUpdatePolicy>>("policies"))

        val settings = BackupServerSettings(sourceUpdatePolicies = policies)
        val bytes = ProtoBuf.encodeToByteArray(BackupServerSettings.serializer(), settings)
        val restored = ProtoBuf.decodeFromByteArray(BackupServerSettings.serializer(), bytes)
        assertEquals(policies, restored.sourceUpdatePolicies)
        assertNull(ProtoBuf.decodeFromByteArray(BackupServerSettings.serializer(), byteArrayOf()).sourceUpdatePolicies)
    }

    @Test
    fun `pre merge policy backups keep their field number`() {
        // Field 98 contains a policy for source "123", with all optional values omitted.
        val bytes = byteArrayOf(0x92.toByte(), 0x06, 0x05, 0x0a, 0x03, 0x31, 0x32, 0x33)
        val restored = ProtoBuf.decodeFromByteArray(BackupServerSettings.serializer(), bytes)

        assertEquals(listOf(SourceUpdatePolicy("123")), restored.sourceUpdatePolicies)
        assertNull(restored.autoBackupIncludeUserSettings)
    }

    @Test
    fun `policies are available in both GraphQL settings input and output`() {
        val schema =
            toSchema(
                config =
                    SchemaGeneratorConfig(
                        supportedPackages = listOf("suwayomi.tachidesk.graphql"),
                        hooks = CustomSchemaGeneratorHooks(),
                    ),
                queries = listOf(TopLevelObject(SettingsQuery())),
                mutations = listOf(TopLevelObject(SettingsMutation())),
            )
        val settings = GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition("settings").type) as GraphQLObjectType
        val output = GraphQLTypeUtil.unwrapAll(settings.getFieldDefinition("sourceUpdatePolicies").type) as GraphQLObjectType
        assertNotNull(output.getFieldDefinition("sourceId"))
        assertNotNull(output.getFieldDefinition("initialCooldownSeconds"))

        val mutation = schema.mutationType.getFieldDefinition("setSettings")
        val input = GraphQLTypeUtil.unwrapAll(mutation.getArgument("input").type) as GraphQLInputObjectType
        val partial = GraphQLTypeUtil.unwrapAll(input.getFieldDefinition("settings").type) as GraphQLInputObjectType
        val policyInput = GraphQLTypeUtil.unwrapAll(partial.getFieldDefinition("sourceUpdatePolicies").type) as GraphQLInputObjectType
        assertNotNull(policyInput.getFieldDefinition("sourceId"))
        assertNotNull(policyInput.getFieldDefinition("maxCooldownSeconds"))
    }

    @Test
    fun `invalid ranges and source IDs are rejected`() {
        val invalid =
            listOf(
                policy.copy(sourceId = "mangaball.com"),
                policy.copy(sourceId = "0"),
                policy.copy(sourceId = "-1"),
                policy.copy(sourceId = "0123"),
                policy.copy(sourceId = "9223372036854775808"),
                policy.copy(minDelaySeconds = -1),
                policy.copy(maxDelaySeconds = 59),
                policy.copy(initialCooldownSeconds = 0),
                policy.copy(maxCooldownSeconds = 1799),
            )
        invalid.forEach { assertNotNull(SourceUpdatePolicy.validate(listOf(it)), it.toString()) }
        assertNotNull(SourceUpdatePolicy.validate(listOf(policy, policy)))
        assertNull(SourceUpdatePolicy.validate(emptyList()))
        assertNull(SourceUpdatePolicy.validate(listOf(policy)))
        assertNull(SourceUpdatePolicy.validate(listOf(policy.copy(minDelaySeconds = 0, maxDelaySeconds = 0))))
    }
}
