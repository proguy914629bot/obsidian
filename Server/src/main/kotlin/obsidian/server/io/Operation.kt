package obsidian.server.io

import kotlinx.serialization.*
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import obsidian.server.player.filter.impl.EqualizerFilter
import obsidian.server.player.filter.impl.TimescaleFilter
import obsidian.server.player.filter.impl.TremoloFilter
import obsidian.server.player.filter.impl.VolumeFilter

sealed class Operation {
  companion object : DeserializationStrategy<Operation?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Operation") {
      element("op", Op.descriptor)
      element("d", JsonObject.serializer().descriptor, isOptional = true)
    }

    @ExperimentalSerializationApi
    override fun deserialize(decoder: Decoder): Operation? {
      var op: Op? = null
      var data: Operation? = null

      with(decoder.beginStructure(descriptor)) {
        loop@ while (true) {
          val idx = decodeElementIndex(descriptor)
          fun <T> decode(serializer: DeserializationStrategy<T>) =
            decodeSerializableElement(descriptor, idx, serializer)

          when (idx) {
            CompositeDecoder.DECODE_DONE -> break@loop

            0 ->
              op = Op.deserialize(decoder)

            1 ->
              data = when (op) {
                Op.SUBMIT_VOICE_UPDATE -> decode(SubmitVoiceUpdate.serializer())
                Op.PLAY_TRACK -> decode(PlayTrack.serializer())
                Op.STOP_TRACK -> decode(StopTrack.serializer())
                Op.PAUSE -> decode(Pause.serializer())
                Op.FILTERS -> decode(Filters.serializer())

                else -> if (data == null) {
                  val element = decodeNullableSerializableElement(descriptor, idx, JsonElement.serializer().nullable)
                  error("Unknown 'd' field for operation ${op?.name}: $element")
                } else {
                  decodeNullableSerializableElement(descriptor, idx, JsonElement.serializer().nullable)
                  data
                }
              }
          }
        }

        endStructure(descriptor)
        return data
      }
    }
  }
}

@Serializable
data class PlayTrack(
  val track: String,

  @Serializable(with = LongAsStringSerializer::class)
  @SerialName("guild_id")
  val guildId: Long,

  @SerialName("no_replace")
  val noReplace: Boolean = false,

  @SerialName("start_time")
  val startTime: Long = 0,

  @SerialName("end_time")
  val endTime: Long = 0
) : Operation()

@Serializable
data class StopTrack(
  @Serializable(with = LongAsStringSerializer::class)
  val guildId: Long

) : Operation()

@Serializable
data class SubmitVoiceUpdate(
  val endpoint: String,
  val token: String,

  @Serializable(with = LongAsStringSerializer::class)
  @SerialName("guild_id")
  val guildId: Long,

  @SerialName("session_id")
  val sessionId: String,
) : Operation()

@Serializable
data class Pause(
  @Serializable(with = LongAsStringSerializer::class)
  @SerialName("guild_id")
  val guildId: Long,
  val state: Boolean = true
) : Operation()

@Serializable
data class Filters(
  @Serializable(with = LongAsStringSerializer::class)
  @SerialName("guild_id")
  val guildId: Long,

  val volume: Float?,
  val tremolo: TremoloFilter?,
  val equalizer: EqualizerFilter?,
  val timescale: TimescaleFilter?
) : Operation()
