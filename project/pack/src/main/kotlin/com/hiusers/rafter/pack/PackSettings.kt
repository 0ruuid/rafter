package com.hiusers.rafter.pack

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.Field
import com.hiusers.rafter.model.Values
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** 只读资源包配置，读取时不启动托管。 */
data class PackSettings(
    val archive: Boolean = false,
    val compression: Int = 6,
    val sources: List<String> = emptyList(),
    val host: Host? = null,
) {
    data class Host(
        val bind: String,
        val port: Int,
        val publicUrl: String,
        val join: Boolean,
        val afterBuild: Boolean,
        val required: Boolean,
    )

    companion object {
        fun read(raw: Map<String, Any?>): PackSettings {
            Contract.check(
                raw,
                mapOf(
                    "archive" to Field("boolean"),
                    "compression-level" to Field("integer"),
                    "merge" to Field("object"),
                    "self-host" to Field("object"),
                ),
                "config.yml",
                "$.resource-pack",
            )
            @Suppress("UNCHECKED_CAST") val merge = raw["merge"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val host = raw["self-host"] as? Map<String, Any?> ?: emptyMap()
            Contract.check(
                merge,
                mapOf("sources" to Field("array")),
                "config.yml",
                "$.resource-pack.merge",
            )
            Contract.check(
                host,
                mapOf(
                    "enabled" to Field("boolean"),
                    "bind-address" to Field("string"),
                    "port" to Field("integer"),
                    "public-url" to Field("string"),
                    "send-on-join" to Field("boolean"),
                    "send-after-build" to Field("boolean"),
                    "required" to Field("boolean"),
                ),
                "config.yml",
                "$.resource-pack.self-host",
            )
            val level = (raw["compression-level"] as? Number)?.toInt() ?: 6
            require(level in 0..9)
            val sources =
                (merge["sources"] as? List<*> ?: emptyList<String>()).map {
                    require(it is String && it.isNotBlank())
                    it
                }
            require(sources.size <= 8 && sources.distinct().size == sources.size) {
                "At most eight distinct pack merge sources"
            }
            val enabled = host["enabled"] as? Boolean ?: false
            val spec = if (!enabled) null else readHost(host)
            return PackSettings(
                raw["archive"] as? Boolean == true || enabled,
                level,
                Values.list(sources),
                spec,
            )
        }

        private fun readHost(host: Map<String, Any?>): Host {
            val bind = host["bind-address"] as? String ?: "127.0.0.1"
            require(('.' in bind || ':' in bind) && bind.matches(Regex("[0-9a-fA-F:.]+"))) {
                "bind-address must be a numeric IP"
            }
            val bindIp = InetAddress.getByName(bind)
            val port = (host["port"] as? Number)?.toInt() ?: 8187
            require(port in 1024..65535)
            // 空地址仅适合同机测试，不能推断远程下载地址。
            val url =
                (host["public-url"] as? String ?: "")
                    .trim()
                    .ifEmpty {
                        require(bindIp.isAnyLocalAddress || bindIp.isLoopbackAddress) {
                            "Set public-url when binding a specific network address"
                        }
                        val loopback = if (bindIp is Inet6Address) "[::1]" else "127.0.0.1"
                        "http://$loopback:$port"
                    }
                    .trimEnd('/')
            val uri = URI(url)
            require(
                uri.scheme in setOf("http", "https") &&
                    uri.host != null &&
                    uri.host !in setOf("0.0.0.0", "[::]", "[0:0:0:0:0:0:0:0]") &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            ) {
                "public-url must be a client-reachable HTTP(S) URL, not a wildcard bind address; blank is for same-machine testing only"
            }
            return Host(
                bind,
                port,
                url,
                host["send-on-join"] as? Boolean ?: false,
                host["send-after-build"] as? Boolean ?: false,
                host["required"] as? Boolean ?: false,
            )
        }
    }
}
