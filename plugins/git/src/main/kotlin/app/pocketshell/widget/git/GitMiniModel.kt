package app.pocketshell.widget.git

/**
 * The MINI card's pure model: which repositories show, in what order, how many
 * fit the card, and the one-line truth under them. JVM-tested; no Compose.
 *
 * The card answers "is anything waiting on me?" at a glance — repositories
 * that need attention (unreadable, uncommitted changes, unpushed or unpulled
 * commits) first, then the quiet ones — and a tap opens that repository.
 */
internal object MiniModel {

    /** Row heights incl. padding + rule: one line (COMPACT) or name + summary + last commit (ROOMY). */
    fun rowDp(roomy: Boolean): Float = if (roomy) 54f else 30f

    private const val HEADER_DP = 44f
    private const val FOOTER_DP = 30f

    /** How many rows fit the card's real height — at least one, never more than there are repositories. */
    fun rowsThatFit(heightDp: Float, roomy: Boolean, total: Int): Int =
        ((heightDp - HEADER_DP - FOOTER_DP) / rowDp(roomy)).toInt().coerceIn(1, maxOf(1, total))

    /** True when the repository is waiting on the user. */
    fun needsAttention(repo: RepoSnapshot): Boolean {
        val s = repo.status ?: return true // unreadable is the loudest kind of attention
        return s.dirty || (s.ahead ?: 0) > 0 || (s.behind ?: 0) > 0
    }

    /** Attention first (unreadable, then dirty, then ahead/behind), each group alphabetical. */
    fun order(repos: List<RepoSnapshot>): List<RepoSnapshot> =
        repos.sortedWith(
            compareBy<RepoSnapshot> { rank(it) }.thenBy { it.name.lowercase() },
        )

    private fun rank(repo: RepoSnapshot): Int {
        val s = repo.status ?: return 0
        return when {
            s.dirty -> 1
            (s.ahead ?: 0) > 0 || (s.behind ?: 0) > 0 -> 2
            else -> 3
        }
    }

    /** "9 repos · 2 dirty · ↑47 to push · +4 more" — facts only. */
    fun footer(repos: List<RepoSnapshot>, hidden: Int): String {
        val dirty = repos.count { it.status?.dirty == true }
        val ahead = repos.sumOf { it.status?.ahead ?: 0 }
        val behind = repos.sumOf { it.status?.behind ?: 0 }
        val parts = buildList {
            add(if (repos.size == 1) "1 repo" else "${repos.size} repos")
            if (dirty > 0) add("$dirty dirty")
            if (ahead > 0) add("↑$ahead to push")
            if (behind > 0) add("↓$behind to pull")
            if (hidden > 0) add("+$hidden more")
        }
        return parts.joinToString(" · ")
    }
}
