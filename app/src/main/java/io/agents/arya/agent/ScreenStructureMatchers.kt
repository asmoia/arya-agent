package io.agents.arya.agent

/**
 * Overflow #6 — structure-first matchers that prefer role/id over raw text.
 * Pure helpers used by ScreenDsl / accessibility tools.
 */
object ScreenStructureMatchers {
    data class NodeHint(
        val id: String? = null,
        val role: String? = null,
        val text: String? = null,
        val desc: String? = null,
    )

    fun score(hint: NodeHint, wantRole: String?, wantText: String?): Int {
        var s = 0
        if (!wantRole.isNullOrBlank() && hint.role.equals(wantRole, ignoreCase = true)) s += 5
        if (!hint.id.isNullOrBlank()) s += 2
        val t = listOfNotNull(hint.text, hint.desc).joinToString(" ").lowercase()
        if (!wantText.isNullOrBlank() && t.contains(wantText.lowercase())) s += 3
        return s
    }

    fun best(nodes: List<NodeHint>, wantRole: String?, wantText: String?): NodeHint? {
        return nodes.maxByOrNull { score(it, wantRole, wantText) }?.takeIf {
            score(it, wantRole, wantText) > 0
        }
    }

    /** Replaces 3 common text-heuristics: send, search, back. */
    fun isSend(hint: NodeHint): Boolean =
        hint.role.equals("button", true) &&
            listOf("send", "ارسال", "ارسال پیام").any {
                (hint.text ?: "").contains(it, true) || (hint.desc ?: "").contains(it, true)
            } || hint.id?.contains("send", true) == true

    fun isSearch(hint: NodeHint): Boolean =
        hint.role.equals("edittext", true) &&
            listOf("search", "جستجو").any {
                (hint.text ?: "").contains(it, true) || (hint.desc ?: "").contains(it, true)
            } || hint.id?.contains("search", true) == true

    fun isBack(hint: NodeHint): Boolean =
        hint.role.equals("button", true) &&
            listOf("back", "بازگشت", "navigate up").any {
                (hint.desc ?: "").contains(it, true) || (hint.text ?: "").contains(it, true)
            }
}
