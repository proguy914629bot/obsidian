/*
 * Obsidian
 * Copyright (C) 2021 Mixtape-Bot
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package obsidian.server.io

import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import io.ktor.http.cio.websocket.*
import io.ktor.util.*
import io.ktor.util.network.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import obsidian.bedrock.BedrockClient
import obsidian.bedrock.BedrockEventAdapter
import obsidian.bedrock.MediaConnection
import obsidian.bedrock.VoiceServerInfo
import obsidian.bedrock.gateway.AbstractMediaGatewayConnection.Companion.asFlow
import obsidian.bedrock.util.Interval
import obsidian.server.player.Link
import obsidian.server.player.TrackEndMarkerHandler
import obsidian.server.player.filter.FilterChain
import obsidian.server.util.TrackUtil
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.full.*

@ExperimentalCoroutinesApi
@Suppress("unused")
class MagmaClient(
  private val clientId: Long,
  private val session: WebSocketServerSession
) : CoroutineScope {
  /**
   * The Bedrock client for this Session
   */
  val bedrock = BedrockClient(clientId)

  /**
   * guild id -> [Link]
   */
  val links = ConcurrentHashMap<Long, Link>()


  /**
   * Events flow lol - idk kotlin
   */
  private val events = MutableSharedFlow<Operation>(extraBufferCapacity = Int.MAX_VALUE)

  override val coroutineContext: CoroutineContext
    get() = Job() + Dispatchers.IO

  /**
   * The stats interval.
   */
  @ObsoleteCoroutinesApi
  private val stats = Interval()

  init {
    on<SubmitVoiceUpdate> {
      val conn = mediaConnectionFor(guildId)
      val link = links.computeIfAbsent(guildId) {
        Link(this@MagmaClient, guildId)
      }

      conn.connect(VoiceServerInfo(sessionId, token, endpoint))
      link.provideTo(conn)
    }

    on<Filters> {
      val link = links.computeIfAbsent(guildId) {
        Link(this@MagmaClient, guildId)
      }

      link.filters = FilterChain.from(link, this)
    }


    on<Pause> {
      val link = links.computeIfAbsent(guildId) {
        Link(this@MagmaClient, guildId)
      }

      link.player.isPaused = state
    }

    on<Seek> {
      val link = links.computeIfAbsent(guildId) {
        Link(this@MagmaClient, guildId)
      }

      link.seekTo(position)
    }

    on<Destroy> {
      links[guildId]?.player?.stopTrack()
      bedrock.destroyConnection(guildId)
    }

    on<StopTrack> {
      val link = links.computeIfAbsent(guildId) {
        Link(this@MagmaClient, guildId)
      }

      link.player.stopTrack()
    }

    on<PlayTrack> {
      val link = links.computeIfAbsent(guildId) {
        Link(this@MagmaClient, guildId)
      }

      if (link.player.playingTrack != null && noReplace) {
        logger.info("Skipping PLAY_TRACK operation")
        return@on
      }

      val track = TrackUtil.decode(track)

      // handle "end_time" and "start_time" parameters
      if (startTime in 0..track.duration) {
        track.position = startTime
      }

      if (endTime in 0..track.duration) {
        val handler = TrackEndMarkerHandler(link)
        val marker = TrackMarker(endTime, handler)
        track.setMarker(marker)
      }

      link.play(track)
    }
  }

  @ObsoleteCoroutinesApi
  suspend fun listen() {
    session.incoming.asFlow().buffer(Channel.UNLIMITED)
      .collect {
        when (it) {
          is Frame.Binary, is Frame.Text -> handleIncomingFrame(it)
          else -> { // no-op
          }
        }
      }
  }

  private inline fun <reified T : Operation> on(crossinline block: suspend T.() -> Unit) {
    events.filterIsInstance<T>()
      .onEach {
        try {
          block.invoke(it)
        } catch (ex: Exception) {
          logger.error(ex)
        }
      }
      .launchIn(this)
  }

  /**
   * Handles an incoming [Frame].
   *
   * @param frame The received text or binary frame.
   */
  private suspend fun handleIncomingFrame(frame: Frame) {
    val json = frame.data.toString(Charset.defaultCharset())

    try {
      logger.trace("$clientId >>> $json")
      jsonParser.decodeFromString(Operation, json)?.let { events.emit(it) }
    } catch (ex: Exception) {
      logger.error(ex)
    }
  }

  private fun mediaConnectionFor(guildId: Long): MediaConnection {
    var mediaConnection = bedrock.getConnection(guildId)
    if (mediaConnection == null) {
      mediaConnection = bedrock.createConnection(guildId)
      mediaConnection.eventDispatcher.register(EventListener(guildId))
    }

    return mediaConnection
  }

  /**
   * Send a JSON payload to the client.
   *
   * @param dispatch The dispatch instance
   */
  suspend fun send(dispatch: Dispatch) {
    val json = jsonParser.encodeToString(Dispatch.Companion, dispatch)
    logger.trace("$clientId <- $json")
    session.send(json)
  }

  internal suspend fun shutdown() {
    logger.info("Shutting down ${links.size} links.")
    for ((_, link) in links) {
      link.player.stopTrack()
    }

    bedrock.close()
  }

  inner class EventListener(private val guildId: Long) : BedrockEventAdapter() {
    private var lastHeartbeat: Long? = null
    private var lastHeartbeatNonce: Long? = null

    override suspend fun gatewayReady(target: NetworkAddress, ssrc: Int) {
      send(
        WebSocketOpenEvent(
          guildId = guildId,
          ssrc = ssrc,
          target = target.hostname
        )
      )
    }

    override suspend fun gatewayClosed(code: Short, reason: String?) {
      send(
        WebSocketClosedEvent(
          guildId = guildId,
          reason = reason,
          code = code
        )
      )
    }

    override suspend fun heartbeatAcknowledged(nonce: Long) {
      if (lastHeartbeatNonce == null || lastHeartbeat == null) {
        return
      }

      if (lastHeartbeatNonce != nonce) {
        logger.warn("A heartbeat was acknowledged but it wasn't the last?")
        return
      }

      logger.info("Our latency between the voice websocket is ${System.currentTimeMillis() - lastHeartbeat!!}ms")
    }

    override suspend fun heartbeatDispatched(nonce: Long) {
      lastHeartbeat = System.currentTimeMillis()
      lastHeartbeatNonce = nonce
    }
  }

  @ObsoleteCoroutinesApi
  private suspend fun sendStats() {
//    send(Op.STATS) {
//      combineWith(Stats.build(this@MagmaClient))
//    }

    if (!stats.started) {
      coroutineScope {
        launch {
          stats.start(60000, ::sendStats)
        }
      }
    }
  }

  companion object {
    /**
     * JSON parser for everything.
     */
    val jsonParser = Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
    }

    private val logger: Logger = LoggerFactory.getLogger(MagmaClient::class.java)

    private fun getGuildId(data: JSONObject): Long? =
      try {
        data.getLong("guild_id")
      } catch (ex: Exception) {
        null
      }
  }
}
