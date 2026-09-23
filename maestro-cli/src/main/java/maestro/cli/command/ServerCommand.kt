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
            "GET /inspect returns the same compact JSON as the inspect_screen MCP tool, " +
            "using the device given by --device-id. " +
            "GET /list-device returns the same device list as the list_devices MCP tool. " +
            "Configure the bind address and default device via --host, --port and --device-id."
    ],
)
class ServerCommand : Callable<Int> {

    @CommandLine.Option(
        names = ["--working-dir"],
        description = ["Base working directory for resolving files"]
    )
    private var workingDir: File? = null

    @CommandLine.Option(
        names = ["--host"],
        description = ["Host/address to bind the server to (default: localhost)"]
    )
    private var host: String = "localhost"

    @CommandLine.Option(
        names = ["--port"],
        description = ["Port to bind the server to (default: 8000)"]
    )
    private var port: Int = 8000

    @CommandLine.Option(
        names = ["--device-id"],
        description = ["Default device id used by /inspect"]
    )
    private var deviceId: String? = null

    override fun call(): Int {
        if (workingDir != null) {
            WorkingDirectory.baseDir = workingDir!!.absoluteFile
        }

        val sessionManager = McpMaestroSessionManager()
        Runtime.getRuntime().addShutdownHook(Thread { sessionManager.close() })

        val server = embeddedServer(Netty, host = host, port = port) {
            routing {
                get("/inspect") {
                    val currentDeviceId = deviceId
                    if (currentDeviceId.isNullOrBlank()) {
                        call.respondText(
                            "No device id configured; start the server with --device-id <id>",
                            ContentType.Text.Plain,
                            HttpStatusCode.BadRequest
                        )
                        return@get
                    }

                    try {
                        val result = sessionManager.withSession(deviceId = currentDeviceId) { session ->
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