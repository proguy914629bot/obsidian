package obsidian.server

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.yaml
import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import obsidian.bedrock.Bedrock
import obsidian.server.io.Magma
import obsidian.server.player.ObsidianPlayerManager
import obsidian.server.util.ObsidianConfig

object Obsidian {
  val config = Config {
    addSpec(ObsidianConfig)
    addSpec(Bedrock.Config)
  }
    .from.yaml.file(".obsidianrc")
    .from.env()
    .from.systemProperties()

  val playerManager = ObsidianPlayerManager()
  val magma = Magma()

  @JvmStatic
  fun main(args: Array<String>) {

    embeddedServer(Netty, host = config[ObsidianConfig.Host], port = config[ObsidianConfig.Port]) {
      install(Routing)
      install(WebSockets)

      routing(magma::use)
    }.start()
  }
}