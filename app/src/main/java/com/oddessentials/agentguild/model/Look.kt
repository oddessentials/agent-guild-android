package com.oddessentials.agentguild.model

/** The Yard's light or dark theme on a TV. [System] leaves the choice to the page. */
enum class Theme(val value: String?) {
    System(null),
    Light("light"),
    Dark("dark");

    companion object {
        /** Light or dark from the page's `data-theme`; anything else is null. */
        fun explicit(value: String?) = entries.firstOrNull { it.value != null && it.value == value }
    }
}

/** A skin as the page lists it in `window.agentGuildSkins`. */
data class Skin(val id: String, val name: String)

/** The skin and theme a TV shows the compact Yard with. A null skin is the page's own choice. */
data class Look(val skin: String? = null, val theme: Theme = Theme.System) {
    init {
        require(skin == null || Looks.isSkinId(skin)) { "not a skin id" }
    }

    companion object {
        val Default = Look()
    }
}

/**
 * What a loaded page shows: the skins it offers, and the skin and theme on its `<html>`. [skin] is
 * the shown skin when it is one of [skins]; [shownSkin] is the shown skin's id whenever it is a valid
 * one, so Cancel can put it back even when the list left it out.
 */
data class PageLook(val skins: List<Skin>, val skin: String?, val theme: Theme?, val shownSkin: String? = skin)

/**
 * The page's side of a [Look]. Skin ids and names come from the page, so they are checked here
 * before they reach a URL, a script or the screen.
 */
object Looks {
    private val SKIN_ID = Regex("[a-z]{1,40}")
    const val MAX_NAME = 40
    const val MAX_SKINS = 32

    fun isSkinId(id: String) = SKIN_ID.matches(id)

    /** Reads the skins and the shown look; evaluated in the page, its result arrives as JSON text. */
    const val READ_SCRIPT = """(() => {
  const root = document.documentElement;
  return { skins: window.agentGuildSkins ?? null, skin: root.dataset.skin ?? null, theme: root.dataset.theme ?? null };
})()"""

    /**
     * [READ_SCRIPT]'s result, or null when it isn't an object. A skin counts only with a valid id and
     * a name of at most [MAX_NAME] characters; a repeated id keeps the first.
     */
    fun parse(json: String?): PageLook? {
        val root = Json.parseOrNull(json ?: return null) as? Map<*, *> ?: return null
        val skins = (root["skins"] as? List<*>).orEmpty().asSequence()
            .mapNotNull { entry ->
                val fields = entry as? Map<*, *> ?: return@mapNotNull null
                val id = (fields["id"] as? String)?.takeIf(::isSkinId) ?: return@mapNotNull null
                val name = (fields["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return@mapNotNull null
                Skin(id, name)
            }
            .distinctBy { it.id }
            .take(MAX_SKINS)
            .toList()
        val shown = (root["skin"] as? String)?.takeIf(::isSkinId)
        val skin = shown?.takeIf { id -> skins.any { it.id == id } }
        return PageLook(skins, skin, Theme.explicit(root["theme"] as? String), shown)
    }

    /**
     * Shows [skin] and [theme] without loading the page again. [Theme.System] resolves as the page's
     * `theme.js` does without `&theme=`: a theme the page saved, or else the system's.
     */
    fun previewScript(skin: String?, theme: Theme): String {
        require(skin == null || isSkinId(skin)) { "not a skin id" }
        val resolve = when (theme) {
            Theme.System -> """
              let theme = null;
              try { theme = localStorage.getItem('agentGuild.theme'); } catch (e) {}
              if (theme !== 'light' && theme !== 'dark') theme = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';"""
            else -> """
              const theme = '${theme.value}';"""
        }
        return """
            (() => {
              const root = document.documentElement;
              const skin = ${skin?.let { "'$it'" } ?: "null"};
              if (skin !== null) root.dataset.skin = skin;$resolve
              root.dataset.theme = theme;
            })()
        """.trimIndent()
    }

    fun restoreScript(skin: String?, theme: Theme?): String {
        require(skin == null || isSkinId(skin)) { "not a skin id" }
        require(theme != Theme.System) { "System is no attribute" }
        val setSkin = skin?.let { "root.dataset.skin = '$it';" } ?: "delete root.dataset.skin;"
        val setTheme = theme?.let { "root.dataset.theme = '${it.value}';" } ?: "delete root.dataset.theme;"
        return """
            (() => {
              const root = document.documentElement;
              $setSkin
              $setTheme
            })()
        """.trimIndent()
    }

    /** The saved looks as JSON text, keyed by computer id or [LOOK_DEMO]. */
    fun encode(looks: Map<String, Look>): String = looks.entries.joinToString(",", "{\"looks\":{", "}}") { (key, look) ->
        val fields = listOfNotNull(look.skin?.let { "\"skin\":${Json.quote(it)}" }, look.theme.value?.let { "\"theme\":${Json.quote(it)}" })
        "${Json.quote(key)}:{${fields.joinToString(",")}}"
    }

    /** Null when the text isn't saved looks. An invalid skin or theme reads as the page's own. */
    fun decode(text: String): Map<String, Look>? {
        val root = Json.parseOrNull(text) as? Map<*, *> ?: return null
        val looks = root["looks"] as? Map<*, *> ?: return null
        return looks.entries.mapNotNull { (key, value) ->
            val fields = value as? Map<*, *> ?: return@mapNotNull null
            val skin = (fields["skin"] as? String)?.takeIf(::isSkinId)
            (key as String) to Look(skin, Theme.explicit(fields["theme"] as? String) ?: Theme.System)
        }.toMap()
    }

    const val LOOK_DEMO = "demo"
}
