package app.retranslate.core.network

import app.retranslate.core.error.AppError
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.1/v1.1 (§10): the two diagnostics guarantees — IPv4 is tried
 * before IPv6 without dropping any address, and a transport failure's
 * technical detail reaches the produced [AppError] verbatim, so the
 * UI's "Details:" line can name the real cause.
 */
class NetworkDiagnosticsTest {

    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun v6(seed: Int): InetAddress =
        InetAddress.getByAddress(ByteArray(16) { if (it == 15) seed.toByte() else 0 })

    private fun dnsOf(addresses: List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }

    @Test
    fun `ipv4 addresses come first and every address is kept`() {
        val v6a = v6(1)
        val v4a = v4(192, 168, 1, 10)
        val v6b = v6(2)
        val v4b = v4(10, 0, 0, 5)
        val dns = Ipv4FirstDns(dnsOf(listOf(v6a, v4a, v6b, v4b)))

        val result = dns.lookup("example.org")

        // IPv4 first (stable within the family), IPv6 kept as fallback.
        assertEquals(listOf(v4a, v4b, v6a, v6b), result)
    }

    @Test
    fun `ipv6 only and ipv4 only lists pass through unchanged`() {
        val only6 = listOf(v6(1), v6(2))
        assertEquals(only6, Ipv4FirstDns(dnsOf(only6)).lookup("example.org"))
        val only4 = listOf(v4(1, 2, 3, 4), v4(5, 6, 7, 8))
        assertEquals(only4, Ipv4FirstDns(dnsOf(only4)).lookup("example.org"))
    }

    @Test
    fun `timeout detail reaches the ProviderDown error`() {
        val error = mapTransportException(
            "GEMINI", SocketTimeoutException("connect timed out")
        )
        val down = error as AppError.ProviderDown
        assertEquals("GEMINI", down.providerId)
        assertTrue(down.detail!!.startsWith("SocketTimeoutException"))
        assertTrue(down.detail!!.contains("connect timed out"))
    }

    @Test
    fun `io exception detail reaches the ProviderDown error`() {
        val error = mapTransportException(
            "MOZHI", IOException("Connection reset by peer")
        )
        val down = error as AppError.ProviderDown
        assertTrue(down.detail!!.startsWith("IOException"))
        assertTrue(down.detail!!.contains("Connection reset"))
    }

    @Test
    fun `unknown host stays NoNetwork - airplane mode speaks plainly`() {
        val error = mapTransportException("GEMINI", UnknownHostException("no route"))
        assertTrue(error is AppError.NoNetwork)
    }
}
