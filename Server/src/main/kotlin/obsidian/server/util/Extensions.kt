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

package obsidian.server.util

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.response.*

suspend inline fun <reified T> ApplicationCall.respondJson(
  status: HttpStatusCode = HttpStatusCode.OK,
  noinline configure: OutgoingContent.() -> Unit = {},
  builder: T.() -> Unit
) =
  respondJson(buildJson(builder), status = status, configure = configure)

suspend inline fun <reified T> ApplicationCall.respondJson(
  json: T,
  status: HttpStatusCode = HttpStatusCode.OK,
  noinline configure: OutgoingContent.() -> Unit = {},
) =
  respondText(
    json.toString(),
    status = status,
    contentType = ContentType.Application.Json,
    configure = configure
  )