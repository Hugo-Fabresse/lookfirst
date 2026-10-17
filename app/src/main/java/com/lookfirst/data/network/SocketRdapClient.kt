package com.lookfirst.data.network

import com.lookfirst.domain.open.CanonicalUrl
import com.lookfirst.domain.rdap.DomainAge
import com.lookfirst.domain.rdap.RDAP_MAX_BODY_BYTES
import com.lookfirst.domain.rdap.RDAP_MAX_REDIRECTS
import com.lookfirst.domain.rdap.RDAP_TIMEOUT_MILLIS
import com.lookfirst.domain.rdap.RdapClient
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapTarget
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executor

private const val HTTPS = "https"
private const val HTTPS_PORT = 443
private const val STATUS_OK = 200
private const val STATUS_NOT_FOUND = 404
private const val ACCEPT_RDAP = "application/rdap+json"
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * [RdapClient] over the same hardened building blocks as the redirect tracer ([BoundedWork], [CheckedConnector],
 * [TlsHopTransport]): the registry host is resolved once, every address checked, the connection goes to a checked IP
 * on port 443 with SNI and certificate checked for the host. The request is `GET {base}domain/{ldh}` with
 * `Accept: application/rdap+json`, no cookie, credential or referrer, the constant User-Agent, no proxy.
 *
 * A redirect is followed only to the same host, over https, at most [RDAP_MAX_REDIRECTS] times; anything else
 * (another host, `http`, another port, an unusable `Location`) is a failure (decision D-rdap). The answer is read
 * up to [RDAP_MAX_BODY_BYTES] only and parsed by [RdapResponse]. HTTP 404 is "unknown to the registry"; every other
 * status, every refusal of the transport and every unusable body is a failure that changes nothing.
 */
internal class SocketRdapClient(
    resolver: HostResolver,
    transport: HopTransport,
    executor: Executor,
    clock: MonotonicClock = MonotonicClock { System.nanoTime() / NANOS_PER_MILLI },
    timeoutMillis: Long = RDAP_TIMEOUT_MILLIS,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : RdapClient {
    private val connector = CheckedConnector(resolver, transport)
    private val work = BoundedWork(executor, clock, timeoutMillis)

    override suspend fun lookup(target: RdapTarget): RdapOutcome {
        if (!isLdhName(target.domain)) return RdapOutcome.Failed(RdapFailure.BAD_ANSWER)
        return work.run(::failed) { budget, abort -> Query(target, budget, abort).run() }
    }

    private fun failed(failure: WorkFailure): RdapOutcome = RdapOutcome.Failed(
        when (failure) {
            WorkFailure.TIMEOUT -> RdapFailure.TIMEOUT
            WorkFailure.REJECTED -> RdapFailure.BUSY
            WorkFailure.NETWORK -> RdapFailure.NETWORK
        },
    )

    /** One request and its same-host redirects: its state lives and dies with it. */
    private inner class Query(
        private val target: RdapTarget,
        private val budget: TimeBudget,
        private val abort: AbortHandle,
    ) {
        private val host = target.server.host
        private val resolved = HashMap<String, List<InetAddress>>()

        fun run(): RdapOutcome {
            var current = firstUrl()
            var redirects = 0
            while (true) {
                val head = request(current)
                when {
                    head.status == STATUS_OK -> return interpret(head.body)
                    head.status == STATUS_NOT_FOUND -> return RdapOutcome.NotFound
                    head.isRedirect && redirects < RDAP_MAX_REDIRECTS -> {
                        redirects++
                        current = sameHost(current, head) ?: return bad()
                    }
                    else -> return RdapOutcome.Failed(RdapFailure.NETWORK)
                }
            }
        }

        private fun firstUrl(): CanonicalUrl {
            val path = "${target.server.path}domain/${target.domain}"
            return CanonicalUrl("$HTTPS://$host$path", HTTPS, host, null)
        }

        private fun request(url: CanonicalUrl): ResponseHead {
            val addresses = connector.checkedAddresses(host, budget, resolved) ?: throw IOException("blocked address")
            val bytes = try {
                HttpHead.get(requestTarget(url), host, ACCEPT_RDAP)
            } catch (invalid: IllegalArgumentException) {
                throw IOException("unusable request", invalid)
            }
            return connector.exchange(host, addresses, bytes, budget, abort, RDAP_MAX_BODY_BYTES)
        }

        /** The next address when the answer is one `Location` that stays on this host over https on port 443. */
        private fun sameHost(current: CanonicalUrl, head: ResponseHead): CanonicalUrl? {
            val location = head.locations.distinct().singleOrNull() ?: return null
            val next = (LocationResolver.resolve(current, location) as? NextHop.Secure)?.url
            val stays = next != null && next.host == host && (next.port ?: HTTPS_PORT) == HTTPS_PORT
            return if (stays) next else null
        }

        private fun interpret(body: ByteArray): RdapOutcome {
            val now = wallClock()
            val registered = RdapResponse.registrationOf(body, target.domain, now) ?: return bad()
            return RdapOutcome.Registered(registered, DomainAge.daysBetween(registered, now))
        }

        private fun bad() = RdapOutcome.Failed(RdapFailure.BAD_ANSWER)
    }

    private companion object {
        /** Path and query as sent on the request line; the canonical form is percent-encoded and starts with `/`. */
        fun requestTarget(url: CanonicalUrl): String {
            val rest = url.value.removePrefix(LocationResolver.origin(url)).substringBefore('#')
            return if (rest.startsWith("/")) rest else "/$rest"
        }

        /**
         * At least two labels of 1 to 63 lowercase letters, digits or hyphens, none starting or ending with a
         * hyphen: nothing that could end a path segment or a request line.
         */
        fun isLdhName(name: String): Boolean {
            if (name.length > MAX_NAME_LENGTH) return false
            val labels = name.split('.')
            return labels.size >= 2 && labels.all { isLabel(it) }
        }

        fun isLabel(label: String): Boolean = label.length in 1..MAX_LABEL_LENGTH &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
            label[0] != '-' &&
            label.last() != '-'

        const val MAX_NAME_LENGTH = 253
        const val MAX_LABEL_LENGTH = 63
    }
}
