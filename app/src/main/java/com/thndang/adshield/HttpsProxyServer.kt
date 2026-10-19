package com.thndang.adshield

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class HttpsProxyServer(
    context: Context,
    private val shouldBlockHost: (
        String
    ) -> Boolean,
    private val protectSocket: (
        Socket
    ) -> Boolean
) {

    private val appContext =
        context.applicationContext

    private val running =
        AtomicBoolean(false)

    @Volatile
    private var server:
        ServerSocket? = null

    @Volatile
    private var acceptThread:
        Thread? = null

    fun start(): Boolean {
        if (running.get()) {
            return true
        }

        return try {
            val socket =
                ServerSocket(
                    PORT,
                    50,
                    InetAddress
                        .getByName(
                            "127.0.0.1"
                        )
                )

            server = socket
            running.set(true)

            acceptThread =
                thread(
                    start = true,
                    isDaemon = true,
                    name =
                        "AdShield-HTTPS-Proxy"
                ) {
                    acceptLoop(
                        socket
                    )
                }

            true
        } catch (_: Throwable) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)

        try {
            server?.close()
        } catch (_: Throwable) {
        }

        server = null
        acceptThread = null
    }

    private fun acceptLoop(
        socket: ServerSocket
    ) {
        while (running.get()) {
            val client =
                try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }

            thread(
                start = true,
                isDaemon = true,
                name =
                    "AdShield-HTTPS-Client"
            ) {
                handleClient(
                    client
                )
            }
        }
    }

    private fun handleClient(
        client: Socket
    ) {
        client.use {
            client.soTimeout =
                CLIENT_TIMEOUT_MS

            val input =
                BufferedInputStream(
                    client.getInputStream()
                )

            val output =
                BufferedOutputStream(
                    client.getOutputStream()
                )

            val request =
                readRequest(
                    input
                )
                    ?: return

            if (
                request.method.equals(
                    "CONNECT",
                    ignoreCase = true
                )
            ) {
                handleConnect(
                    client,
                    output,
                    request
                )
            } else {
                handlePlainHttp(
                    output,
                    request
                )
            }
        }
    }

    private fun handleConnect(
        rawClient: Socket,
        rawOutput: OutputStream,
        connectRequest: HttpRequest
    ) {
        val hostPort =
            parseHostPort(
                connectRequest.target,
                443
            )
                ?: run {
                    writeSimpleResponse(
                        rawOutput,
                        400,
                        "Bad CONNECT target"
                    )
                    return
                }

        val host =
            hostPort.first

        val port =
            hostPort.second

        if (
            shouldBlockHost(
                host
            )
        ) {
            writeSimpleResponse(
                rawOutput,
                403,
                "Blocked by AdShield"
            )
            return
        }

        rawOutput.write(
            (
                "HTTP/1.1 200 " +
                    "Connection Established\r\n" +
                    "Proxy-Agent: AdShield\r\n" +
                    "\r\n"
                )
                .toByteArray(
                    Charsets.ISO_8859_1
                )
        )

        rawOutput.flush()

        val serverContext =
            HttpsCertificateAuthority
                .sslContextForHost(
                    appContext,
                    host
                )

        val clientTls =
            serverContext
                .socketFactory
                .createSocket(
                    rawClient,
                    rawClient
                        .inetAddress
                        ?.hostAddress
                        ?: "127.0.0.1",
                    rawClient.port,
                    false
                ) as SSLSocket

        clientTls.useClientMode =
            false

        clientTls.needClientAuth =
            false

        clientTls.soTimeout =
            CLIENT_TIMEOUT_MS

        try {
            clientTls.startHandshake()
        } catch (_: Throwable) {
            try {
                clientTls.close()
            } catch (_: Throwable) {
            }
            return
        }

        clientTls.use {
            val clientInput =
                BufferedInputStream(
                    clientTls
                        .getInputStream()
                )

            val clientOutput =
                BufferedOutputStream(
                    clientTls
                        .getOutputStream()
                )

            val innerRequest =
                readRequest(
                    clientInput
                )
                    ?: return

            relayHttpsRequest(
                clientOutput,
                host,
                port,
                innerRequest
            )
        }
    }

    private fun relayHttpsRequest(
        clientOutput: OutputStream,
        host: String,
        port: Int,
        request: HttpRequest
    ) {
        val upstreamRaw =
            Socket()

        if (
            !protectSocket(
                upstreamRaw
            )
        ) {
            writeSimpleResponse(
                clientOutput,
                502,
                "Unable to protect upstream socket"
            )
            return
        }

        try {
            upstreamRaw.connect(
                InetSocketAddress(
                    host,
                    port
                ),
                CONNECT_TIMEOUT_MS
            )

            upstreamRaw.soTimeout =
                UPSTREAM_TIMEOUT_MS

            val upstreamTls =
                (
                    SSLSocketFactory
                        .getDefault()
                        .createSocket(
                            upstreamRaw,
                            host,
                            port,
                            true
                        )
                    ) as SSLSocket

            upstreamTls.use {
                upstreamTls
                    .useClientMode =
                    true

                upstreamTls
                    .startHandshake()

                if (
                    !HttpsURLConnection
                        .getDefaultHostnameVerifier()
                        .verify(
                            host,
                            upstreamTls.session
                        )
                ) {
                    writeSimpleResponse(
                        clientOutput,
                        502,
                        "Upstream TLS hostname mismatch"
                    )
                    return
                }

                val upstreamInput =
                    BufferedInputStream(
                        upstreamTls
                            .getInputStream()
                    )

                val upstreamOutput =
                    BufferedOutputStream(
                        upstreamTls
                            .getOutputStream()
                    )

                writeUpstreamRequest(
                    upstreamOutput,
                    request,
                    host,
                    false
                )

                relayResponse(
                    clientOutput,
                    upstreamInput,
                    request.method,
                    host
                )
            }
        } catch (_: Throwable) {
            writeSimpleResponse(
                clientOutput,
                502,
                "HTTPS upstream failed"
            )
        } finally {
            try {
                upstreamRaw.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handlePlainHttp(
        clientOutput: OutputStream,
        request: HttpRequest
    ) {
        val uri =
            runCatching {
                URI(
                    request.target
                )
            }.getOrNull()

        val host =
            uri?.host
                ?: request
                    .header(
                        "Host"
                    )
                    ?.substringBefore(
                        ":"
                    )
                ?: run {
                    writeSimpleResponse(
                        clientOutput,
                        400,
                        "Missing Host"
                    )
                    return
                }

        val port =
            if (
                uri?.port != null &&
                uri.port > 0
            ) {
                uri.port
            } else {
                80
            }

        if (
            shouldBlockHost(
                host
            )
        ) {
            writeSimpleResponse(
                clientOutput,
                403,
                "Blocked by AdShield"
            )
            return
        }

        val upstream =
            Socket()

        if (
            !protectSocket(
                upstream
            )
        ) {
            writeSimpleResponse(
                clientOutput,
                502,
                "Unable to protect upstream socket"
            )
            return
        }

        try {
            upstream.connect(
                InetSocketAddress(
                    host,
                    port
                ),
                CONNECT_TIMEOUT_MS
            )

            upstream.soTimeout =
                UPSTREAM_TIMEOUT_MS

            upstream.use {
                val input =
                    BufferedInputStream(
                        upstream
                            .getInputStream()
                    )

                val output =
                    BufferedOutputStream(
                        upstream
                            .getOutputStream()
                    )

                writeUpstreamRequest(
                    output,
                    request,
                    host,
                    true
                )

                relayResponse(
                    clientOutput,
                    input,
                    request.method,
                    host
                )
            }
        } catch (_: Throwable) {
            writeSimpleResponse(
                clientOutput,
                502,
                "HTTP upstream failed"
            )
        }
    }

    private fun writeUpstreamRequest(
        output: OutputStream,
        request: HttpRequest,
        host: String,
        absoluteTarget: Boolean
    ) {
        val target =
            if (absoluteTarget) {
                runCatching {
                    val uri =
                        URI(
                            request.target
                        )

                    buildString {
                        append(
                            if (
                                uri.rawPath
                                    .isNullOrEmpty()
                            ) {
                                "/"
                            } else {
                                uri.rawPath
                            }
                        )

                        if (
                            !uri.rawQuery
                                .isNullOrEmpty()
                        ) {
                            append("?")
                            append(
                                uri.rawQuery
                            )
                        }
                    }
                }.getOrDefault("/")
            } else {
                request.target
            }

        writeAscii(
            output,
            request.method +
                " " +
                target +
                " " +
                request.version +
                "\r\n"
        )

        var hasHost =
            false

        request.headers
            .forEach {
                (name, value) ->

                when (
                    name.lowercase(
                        Locale.US
                    )
                ) {
                    "proxy-connection",
                    "connection",
                    "accept-encoding" -> {
                    }

                    "host" -> {
                        hasHost = true

                        writeAscii(
                            output,
                            "Host: " +
                                value +
                                "\r\n"
                        )
                    }

                    else -> {
                        writeAscii(
                            output,
                            name +
                                ": " +
                                value +
                                "\r\n"
                        )
                    }
                }
            }

        if (!hasHost) {
            writeAscii(
                output,
                "Host: " +
                    host +
                    "\r\n"
            )
        }

        writeAscii(
            output,
            "Accept-Encoding: identity\r\n"
        )

        writeAscii(
            output,
            "Connection: close\r\n"
        )

        writeAscii(
            output,
            "\r\n"
        )

        if (
            request.body
                .isNotEmpty()
        ) {
            output.write(
                request.body
            )
        }

        output.flush()
    }

    private fun relayResponse(
        clientOutput: OutputStream,
        upstreamInput: InputStream,
        requestMethod: String,
        host: String
    ) {
        val statusLine =
            readLine(
                upstreamInput
            )
                ?: return

        val headers =
            readHeaders(
                upstreamInput
            )

        val statusCode =
            statusLine
                .split(" ")
                .getOrNull(1)
                ?.toIntOrNull()
                ?: 0

        val noBody =
            requestMethod.equals(
                "HEAD",
                ignoreCase = true
            ) ||
                statusCode in 100..199 ||
                statusCode == 204 ||
                statusCode == 304

        val contentType =
            headerValue(
                headers,
                "Content-Type"
            ).orEmpty()

        val encoding =
            headerValue(
                headers,
                "Content-Encoding"
            )
                ?.trim()
                ?.lowercase(
                    Locale.US
                )
                .orEmpty()

        val transferEncoding =
            headerValue(
                headers,
                "Transfer-Encoding"
            )
                ?.lowercase(
                    Locale.US
                )
                .orEmpty()

        val contentLength =
            headerValue(
                headers,
                "Content-Length"
            )
                ?.toLongOrNull()

        val injectable =
            !noBody &&
                contentType
                    .lowercase(
                        Locale.US
                    )
                    .startsWith(
                        "text/html"
                    ) &&
                (
                    encoding.isEmpty() ||
                        encoding ==
                            "identity"
                    ) &&
                !transferEncoding
                    .contains(
                        "chunked"
                    ) &&
                contentLength != null &&
                contentLength >= 0 &&
                contentLength <=
                    MAX_HTML_BYTES

        if (injectable) {
            val body =
                readExact(
                    upstreamInput,
                    contentLength
                        .toInt()
                )

            val filtered =
                injectContent(
                    body,
                    contentType,
                    host
                )

            writeAscii(
                clientOutput,
                statusLine +
                    "\r\n"
            )

            headers.forEach {
                (name, value) ->

                val lower =
                    name.lowercase(
                        Locale.US
                    )

                if (
                    lower ==
                        "content-length" ||
                    lower ==
                        "transfer-encoding" ||
                    lower ==
                        "connection" ||
                    (
                        isAnimeHost(host) &&
                            lower ==
                                "content-security-policy"
                        )
                ) {
                    return@forEach
                }

                writeAscii(
                    clientOutput,
                    name +
                        ": " +
                        value +
                        "\r\n"
                )
            }

            writeAscii(
                clientOutput,
                "Content-Length: " +
                    filtered.size +
                    "\r\n"
            )

            writeAscii(
                clientOutput,
                "Connection: close\r\n"
            )

            writeAscii(
                clientOutput,
                "\r\n"
            )

            clientOutput.write(
                filtered
            )

            clientOutput.flush()
            return
        }

        writeAscii(
            clientOutput,
            statusLine +
                "\r\n"
        )

        headers.forEach {
            (name, value) ->

            if (
                name.equals(
                    "Connection",
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            writeAscii(
                clientOutput,
                name +
                    ": " +
                    value +
                    "\r\n"
            )
        }

        writeAscii(
            clientOutput,
            "Connection: close\r\n"
        )

        writeAscii(
            clientOutput,
            "\r\n"
        )

        if (!noBody) {
            val buffer =
                ByteArray(16 * 1024)

            while (true) {
                val count =
                    upstreamInput.read(
                        buffer
                    )

                if (count < 0) {
                    break
                }

                if (count == 0) {
                    continue
                }

                clientOutput.write(
                    buffer,
                    0,
                    count
                )
            }
        }

        clientOutput.flush()
    }

    private fun injectContent(
        bytes: ByteArray,
        contentType: String,
        host: String
    ): ByteArray {
        val charset =
            parseCharset(
                contentType
            )

        val html =
            runCatching {
                String(
                    bytes,
                    charset
                )
            }.getOrElse {
                return bytes
            }

        val payload =
            cosmeticPayload(
                host
            )

        val headRegex =
            Regex(
                "<head(?:\\s[^>]*)?>",
                RegexOption.IGNORE_CASE
            )

        val match =
            headRegex.find(
                html
            )

        val filtered =
            if (match != null) {
                val at =
                    match.range.last + 1

                html.substring(
                    0,
                    at
                ) +
                    payload +
                    html.substring(
                        at
                    )
            } else {
                payload +
                    html
            }

        return filtered.toByteArray(
            charset
        )
    }

    private fun cosmeticPayload(
        host: String
    ): String {
        val anime =
            isAnimeHost(
                host
            )

        return buildString {
            append(
                "<style id=\"adshield-proxy-style\">"
            )

            append(
                ".ads-300,.ads_player,.pc-catfixx," +
                    ".mobile-catfixx,.mobile-catfish-top," +
                    ".Ads,.Adv,#invideo_wrapper," +
                    "#_preload-ads-1," +
                    "[class*='banner-ads']," +
                    "[class*='banner_ads']," +
                    "[class*='floating-ad']," +
                    "[class*='popup-ad']{" +
                    "display:none!important;" +
                    "visibility:hidden!important;" +
                    "opacity:0!important;" +
                    "pointer-events:none!important;" +
                    "}"
            )

            append(
                "</style>"
            )

            append(
                "<script>(function(){" +
                    "if(window.__adShieldProxy)return;" +
                    "window.__adShieldProxy=1;" +
                    "var norm=function(v){try{return String(v||'')" +
                    ".toLowerCase().normalize('NFD')" +
                    ".replace(/[\\u0300-\\u036f]/g,'')" +
                    ".replace(/đ/g,'d').replace(/\\s+/g,' ').trim();" +
                    "}catch(e){return String(v||'').toLowerCase();}};" +
                    "var hide=function(e){if(!e||!e.style)return;" +
                    "e.style.setProperty('display','none','important');" +
                    "e.style.setProperty('visibility','hidden','important');};" +
                    "var clean=function(root){try{" +
                    "(root||document).querySelectorAll(" +
                    "'.ads-300,.ads_player,.pc-catfixx,.mobile-catfixx," +
                    ".mobile-catfish-top,.Ads,.Adv,#invideo_wrapper," +
                    "#_preload-ads-1,[class*=\\\"pause-ad\\"]," +
                    "[class*=\\\"video-ad\\"]').forEach(hide);" +
                    "var els=(root||document).querySelectorAll(" +
                    "'button,a,[role=button],div,span');" +
                    "var n=0;for(var i=0;i<els.length&&n<350;i++,n++){" +
                    "var t=norm(els[i].innerText);" +
                    "if(t==='dong quang cao'||t==='dong va xem tiep'){" +
                    "var p=els[i];for(var d=0;d<8&&p;d++,p=p.parentElement){" +
                    "var pt=norm(p.innerText);" +
                    "if(pt.indexOf('dong quang cao')>=0&&" +
                    "pt.indexOf('dong va xem tiep')>=0){hide(p);break;}}}}" +
                    "}catch(e){}};" +
                    "try{window.open=function(){return null;};}catch(e){};" +
                    "document.addEventListener('pause',function(e){" +
                    "if(e.target&&e.target.tagName==='VIDEO'){" +
                    "[0,50,140,350,700].forEach(function(ms){" +
                    "setTimeout(function(){clean(document);},ms);});}},true);" +
                    "try{new MutationObserver(function(ms){" +
                    "ms.forEach(function(m){m.addedNodes.forEach(function(n){" +
                    "if(n&&n.nodeType===1)clean(n);});});" +
                    "}).observe(document.documentElement||document," +
                    "{childList:true,subtree:true});}catch(e){};" +
                    "if(document.readyState==='loading'){" +
                    "document.addEventListener('DOMContentLoaded'," +
                    "function(){clean(document);},{once:true});}else clean(document);"
            )

            if (anime) {
                append(
                    "try{if('PopupManager' in window){" +
                        "window.PopupManager={open:function(){},show:function(){}," +
                        "init:function(){}};}}catch(e){};"
                )
            }

            append(
                "})();</script>"
            )
        }
    }

    private fun isAnimeHost(
        host: String
    ): Boolean =
        host.lowercase(
            Locale.US
        )
            .contains(
                "animevietsub"
            )

    private fun parseCharset(
        contentType: String
    ): Charset {
        val match =
            Regex(
                "charset=([^;\\s]+)",
                RegexOption.IGNORE_CASE
            )
                .find(
                    contentType
                )

        val raw =
            match
                ?.groupValues
                ?.getOrNull(1)
                ?.trim(
                    '"',
                    '\''
                )

        return runCatching {
            if (
                raw.isNullOrBlank()
            ) {
                Charsets.UTF_8
            } else {
                Charset.forName(
                    raw
                )
            }
        }.getOrDefault(
            Charsets.UTF_8
        )
    }

    private fun readRequest(
        input: InputStream
    ): HttpRequest? {
        val first =
            readLine(
                input
            )
                ?: return null

        if (first.isBlank()) {
            return null
        }

        val parts =
            first.split(
                " ",
                limit = 3
            )

        if (parts.size < 3) {
            return null
        }

        val headers =
            readHeaders(
                input
            )

        val length =
            headerValue(
                headers,
                "Content-Length"
            )
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0

        if (
            length >
                MAX_REQUEST_BODY_BYTES
        ) {
            return null
        }

        val body =
            if (length > 0) {
                readExact(
                    input,
                    length
                )
            } else {
                ByteArray(0)
            }

        return HttpRequest(
            parts[0],
            parts[1],
            parts[2],
            headers,
            body
        )
    }

    private fun readHeaders(
        input: InputStream
    ): List<Pair<String, String>> {
        val headers =
            mutableListOf<
                Pair<String, String>
            >()

        repeat(
            MAX_HEADER_LINES
        ) {
            val line =
                readLine(
                    input
                )
                    ?: return@repeat

            if (line.isEmpty()) {
                return headers
            }

            val colon =
                line.indexOf(
                    ':'
                )

            if (colon <= 0) {
                return@repeat
            }

            headers.add(
                line.substring(
                    0,
                    colon
                ).trim() to
                    line.substring(
                        colon + 1
                    ).trim()
            )
        }

        return headers
    }

    private fun headerValue(
        headers:
            List<Pair<String, String>>,
        name: String
    ): String? =
        headers
            .firstOrNull {
                it.first.equals(
                    name,
                    ignoreCase = true
                )
            }
            ?.second

    private fun readLine(
        input: InputStream
    ): String? {
        val bytes =
            ByteArrayOutputStream()

        var previous =
            -1

        while (
            bytes.size() <
                MAX_LINE_BYTES
        ) {
            val current =
                input.read()

            if (current < 0) {
                if (
                    bytes.size() == 0
                ) {
                    return null
                }

                break
            }

            if (
                previous == '\r'.code &&
                current == '\n'.code
            ) {
                val array =
                    bytes.toByteArray()

                val content =
                    if (
                        array.isNotEmpty() &&
                        array.last() ==
                            '\r'.code.toByte()
                    ) {
                        array.copyOf(
                            array.size - 1
                        )
                    } else {
                        array
                    }

                return String(
                    content,
                    Charsets.ISO_8859_1
                )
            }

            bytes.write(
                current
            )

            previous =
                current
        }

        return String(
            bytes.toByteArray(),
            Charsets.ISO_8859_1
        )
    }

    private fun readExact(
        input: InputStream,
        length: Int
    ): ByteArray {
        val result =
            ByteArray(
                length
            )

        var offset =
            0

        while (
            offset < length
        ) {
            val count =
                input.read(
                    result,
                    offset,
                    length - offset
                )

            if (count < 0) {
                return result.copyOf(
                    offset
                )
            }

            offset +=
                count
        }

        return result
    }

    private fun parseHostPort(
        value: String,
        defaultPort: Int
    ): Pair<String, Int>? {
        val clean =
            value.trim()

        if (clean.isEmpty()) {
            return null
        }

        if (
            clean.startsWith("[")
        ) {
            val end =
                clean.indexOf(
                    ']'
                )

            if (end <= 0) {
                return null
            }

            val host =
                clean.substring(
                    1,
                    end
                )

            val port =
                clean.substring(
                    end + 1
                )
                    .removePrefix(
                        ":"
                    )
                    .toIntOrNull()
                    ?: defaultPort

            return host to port
        }

        val lastColon =
            clean.lastIndexOf(
                ':'
            )

        if (
            lastColon > 0 &&
            clean.indexOf(':') ==
                lastColon
        ) {
            val port =
                clean.substring(
                    lastColon + 1
                )
                    .toIntOrNull()

            if (port != null) {
                return clean.substring(
                    0,
                    lastColon
                ) to port
            }
        }

        return clean to
            defaultPort
    }

    private fun writeSimpleResponse(
        output: OutputStream,
        code: Int,
        message: String
    ) {
        val body =
            message.toByteArray(
                Charsets.UTF_8
            )

        writeAscii(
            output,
            "HTTP/1.1 " +
                code +
                " " +
                message +
                "\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " +
                body.size +
                "\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        )

        output.write(
            body
        )

        output.flush()
    }

    private fun writeAscii(
        output: OutputStream,
        value: String
    ) {
        output.write(
            value.toByteArray(
                Charsets.ISO_8859_1
            )
        )
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val version: String,
        val headers:
            List<Pair<String, String>>,
        val body: ByteArray
    ) {
        fun header(
            name: String
        ): String? =
            headers
                .firstOrNull {
                    it.first.equals(
                        name,
                        ignoreCase = true
                    )
                }
                ?.second
    }

    companion object {
        const val PORT =
            8877

        private const val CONNECT_TIMEOUT_MS =
            10_000

        private const val UPSTREAM_TIMEOUT_MS =
            20_000

        private const val CLIENT_TIMEOUT_MS =
            20_000

        private const val MAX_LINE_BYTES =
            16 * 1024

        private const val MAX_HEADER_LINES =
            200

        private const val MAX_REQUEST_BODY_BYTES =
            8 * 1024 * 1024

        private const val MAX_HTML_BYTES =
            4L * 1024L * 1024L
    }
}
