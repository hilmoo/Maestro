package maestro.cli.command

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import maestro.cli.mcp.McpMaestroSessionManager
import maestro.cli.mcp.tools.ViewHierarchyFormatters
import maestro.cli.util.WorkingDirectory
import maestro.device.DeviceService
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "server",
    description = [
        "Starts an HTTP server exposing Maestro functionality over HTTP. " +
            "GET /inspect?device_id=<id> returns the same compact JSON as the inspect_screen MCP tool. " +
            "GET /list-device returns the same device list as the list_devices MCP tool. " +
            "Configure the bind address via the HOST and PORT environment variables (defaults: localhost:8000)."
    ],
)
class ServerCommand : Callable<Int> {

    @CommandLine.Option(
        names = ["--working-dir"],
        description = ["Base working directory for resolving files"]
    )
    private var workingDir: File? = null

    override fun call(): Int {
        if (workingDir != null) {
            WorkingDirectory.baseDir = workingDir!!.absoluteFile
        }

        val host = System.getenv("HOST")?.takeIf { it.isNotBlank() } ?: "localhost"
        val port = System.getenv("PORT")?.toIntOrNull() ?: 8000

        val sessionManager = McpMaestroSessionManager()
        Runtime.getRuntime().addShutdownHook(Thread { sessionManager.close() })

        val server = embeddedServer(Netty, host = host, port = port) {
            routing {
                get("/inspect") {
                    val deviceId = call.request.queryParameters["device_id"]
                    if (deviceId.isNullOrBlank()) {
                        call.respondText(
                            "device_id is required",
                            ContentType.Text.Plain,
                            HttpStatusCode.BadRequest
                        )
                        return@get
                    }

                    try {
                        val result = sessionManager.withSession(deviceId = deviceId) { session ->
                            val viewHierarchy = runBlocking { session.maestro.viewHierarchy() }
                            ViewHierarchyFormatters.extractCompactJsonOutput(viewHierarchy.root, session.platform)
                        }
                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(
                            "Failed to inspect screen: ${e.message}",
                            ContentType.Text.Plain,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }

                get("/list-device") {
                    try {
                        val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
                        val connectedDevices = DeviceService.listConnectedDevices()

                        val allDevices = buildJsonArray {
                            connectedDevices.forEach { device ->
                                addJsonObject {
                                    put("device_id", device.instanceId)
                                    put("name", device.description)
                                    put("platform", device.platform.name.lowercase())
                                    put("type", device.deviceType.name.lowercase())
                                    put("connected", true)
                                }
                            }

                            availableDevices.forEach { device ->
                                val alreadyConnected = connectedDevices.any { it.instanceId == device.modelId }
                                if (!alreadyConnected) {
                                    addJsonObject {
                                        put("device_id", device.modelId)
                                        put("name", device.description)
                                        put("platform", device.platform.name.lowercase())
                                        put("type", device.deviceType.name.lowercase())
                                        put("connected", false)
                                    }
                                }
                            }
                        }

                        val result = buildJsonObject {
                            put("devices", allDevices)
                        }

                        call.respondText(result.toString(), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(
                            "Failed to list devices: ${e.message}",
                            ContentType.Text.Plain,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }
            }
        }

        System.err.println("Maestro server listening on http://$host:$port")

        server.start(wait = true)

        return 0
    }
}
