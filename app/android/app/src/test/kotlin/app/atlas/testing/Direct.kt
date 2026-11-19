package app.atlas.testing

import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * A test's client that no proxy of the JVM's can carry out past the test's own `Dns`: no proxy for OkHttp, and
 * sockets that ask for none either. [Proxy.NO_PROXY] alone is not enough: a plain socket goes through `socksProxyHost`
 * by itself (19 Nov 2026: a test's request reached a SOCKS proxy set for the JVM with OkHttp's proxy off).
 */
fun OkHttpClient.Builder.direct(): OkHttpClient.Builder = proxy(Proxy.NO_PROXY).socketFactory(DirectSockets)

/** Sockets that connect straight to their address, whatever the JVM's proxy settings. */
object DirectSockets : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
}
