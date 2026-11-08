package app.retranslate.providers.free

/**
 * Default instance lists, verified live from the build sandbox during P1
 * (2026-10-27 — full evidence table in build-logs/P1-REPORT.md).
 *
 * Order is deliberate: instances that returned real translations today
 * come first; instances that answered but were rate-limiting the shared
 * sandbox egress IP follow (they are the flagship public instances and
 * serve residential IPs normally — the hub's circuit breakers demote
 * them automatically if they misbehave for a given user). Instances that
 * proved dead, parked, or bot-walled are NOT listed.
 */
object DefaultInstances {

    val MOZHI: List<String> = listOf(
        // Re-verified live 2026-11-08: the first two returned real
        // translations; the rest answered but rate-limited the shared
        // sandbox IP or served a bot-wall/landing page from it — the
        // hub's circuit breakers demote them automatically per user.
        "https://mozhi.adminforge.de",   // verified 2026-11-08: real translations
        "https://mozhi.ducks.party",     // verified 2026-11-08: real translations
        "https://mozhi.aryak.me",        // alive, rate-limited from the sandbox IP
        "https://translate.projectsegfau.lt", // alive, rate-limited from the sandbox IP
        "https://mozhi.catsarch.com",    // bot-wall page from the sandbox IP (2026-11-08)
        "https://translate.privacyredirect.com" // HTML landing page from the sandbox IP (2026-11-08)
    )

    val LINGVA: List<String> = listOf(
        "https://lingva.lunar.icu",           // API alive (language list verified); translation unverified from sandbox IP
        "https://translate.plausibility.cloud" // API alive; translation unverified from sandbox IP
    )

    val SIMPLYTRANSLATE: List<String> = listOf(
        "https://simplytranslate.org" // alive, correct response shape; empty translations from sandbox IP (unverified)
    )
}
