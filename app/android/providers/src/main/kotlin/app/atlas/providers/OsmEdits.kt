package app.atlas.providers

import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/** An OpenStreetMap element's kind, as the API names it. */
enum class OsmType(val api: String) { NODE("node"), WAY("way"), RELATION("relation") }

/** A relation's member as the API lists it. */
data class OsmMember(val type: OsmType, val ref: Long, val role: String)

/**
 * An element as the API returns it: everything an upload must send back, its version, its tags, and its geometry as it is
 * (a node's place, a way's nodes, a relation's members). An edit of its tags sends the rest unchanged.
 */
data class OsmElement(
    val type: OsmType,
    val id: Long,
    val version: Int,
    val tags: Map<String, String>,
    val lat: Double? = null,
    val lon: Double? = null,
    val nodes: List<Long> = emptyList(),
    val members: List<OsmMember> = emptyList(),
)

/**
 * One edit of an element's tags, as the user made it: the values it sets (null removes the tag) and, for the same keys,
 * the values the user saw (null: the tag was absent). It applies only while every one of those still holds, so an edit
 * never overwrites what someone else changed meanwhile.
 */
data class TagEdit(val type: OsmType, val id: Long, val set: Map<String, String?>, val seen: Map<String, String?>) {
    init {
        require(set.isNotEmpty()) { "An edit changes at least one tag" }
        require(seen.keys == set.keys) { "An edit says what the user saw for each key it changes" }
    }
}

/** What a send did: its changeset (null when nothing went), the edits it applied, and those whose element changed meanwhile. */
data class EditsSent(val changeset: Long?, val applied: List<TagEdit>, val conflicts: List<TagEdit>)

/**
 * Signing in to OpenStreetMap with OAuth 2.0 and PKCE (RFC 7636). The app opens [authorizeUrl] in the browser, the user
 * signs in on openstreetmap.org, and the redirect to [redirectUri] brings a code back, which [exchange] turns into a
 * token. The app's client id is registered on openstreetmap.org by the user; the token belongs in the phone's keystore.
 */
class OsmAuth(
    private val http: Http,
    private val clientId: String,
    private val redirectUri: String,
    private val web: String = "https://www.openstreetmap.org",
    private val api: String = "https://api.openstreetmap.org",
) {
    /** The page that asks the user to let Atlas edit with their account: read their preferences, write to the map. */
    fun authorizeUrl(challenge: String, state: String, scopes: List<String> = SCOPES): String =
        "$web/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scopes.joinToString(" "))
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build().toString()

    /** The token for the [code] the redirect brought, proven by the [verifier] whose challenge the sign-in page saw. */
    suspend fun exchange(code: String, verifier: String): String {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val res = http.execute(http.request("$web/oauth2/token").post(form).build())
        if (!res.ok) throw ProviderException(ID, "OpenStreetMap did not sign you in (HTTP ${res.code})")
        return res.json().asObject()?.str("access_token") ?: throw ProviderException(ID, "OpenStreetMap's answer has no token")
    }

    /** The display name of the user the [token] belongs to, as edits show it. */
    suspend fun userName(token: String): String {
        val res = http.execute(http.request("$api/api/0.6/user/details.json").header("Authorization", "Bearer $token").build())
        if (res.code == 401) throw ProviderException(ID, "OpenStreetMap no longer accepts this sign-in")
        return res.requireOk().json().asObject()?.obj("user")?.str("display_name")
            ?: throw ProviderException(ID, "OpenStreetMap's answer has no user name")
    }

    companion object {
        const val ID = "osm-auth"
        val SCOPES = listOf("read_prefs", "write_api")

        /** A new code verifier: 32 random bytes, base64url without padding (43 characters, as RFC 7636 asks). */
        fun verifier(random: SecureRandom = SecureRandom()): String = base64url(ByteArray(32).also(random::nextBytes))

        /** The S256 challenge of [verifier]: base64url(SHA-256(its ASCII bytes)), without padding. */
        fun challenge(verifier: String): String =
            base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Edits to OpenStreetMap with the user's own account (API 0.6): each element is read as it is now, the user's tag edits
 * applied where what they saw still holds, and the result sent in one changeset (open, upload one osmChange, close),
 * tagged with what made it and how it was surveyed. Nothing here runs unless the app calls [send] after the user's tap
 * on the review; the requests ([changesetXml], [osmChangeXml]) can be shown first.
 */
class OsmEdits(
    private val http: Http,
    private val base: String = "https://api.openstreetmap.org",
    private val createdBy: String = "Atlas",
) : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    /** The element as it is now, or null where it was deleted. */
    suspend fun element(type: OsmType, id: Long): OsmElement? {
        val res = http.execute(http.request("$base/api/0.6/${type.api}/$id.json").build())
        if (res.code == 404 || res.code == 410) return null
        val o = res.requireOk().json().asObject() ?: throw ProviderException(ID, "OpenStreetMap answered something unexpected")
        return o.arr("elements").firstOrNull()?.asObject()?.let(::parse)
            ?: throw ProviderException(ID, "OpenStreetMap's answer has no ${type.api} $id")
    }

    /** [element] with [edit] applied, or null where one of its keys no longer holds what the user saw. */
    fun applied(element: OsmElement, edit: TagEdit): OsmElement? {
        if (element.type != edit.type || element.id != edit.id) return null
        if (edit.seen.any { (k, v) -> element.tags[k] != v }) return null
        val tags = LinkedHashMap(element.tags)
        for ((k, v) in edit.set) if (v == null) tags.remove(k) else tags[k] = v
        return element.copy(tags = tags)
    }

    /** The body that opens a changeset: what made it, the user's [comment], and how the edits were surveyed. */
    fun changesetXml(comment: String, source: String = "survey"): String = buildString {
        append("<osm><changeset>")
        tag("created_by", createdBy)
        tag("comment", comment)
        tag("source", source)
        append("</changeset></osm>")
    }

    /** The osmChange that modifies [elements] in [changeset], each whole as the API requires. */
    fun osmChangeXml(changeset: Long, elements: List<OsmElement>): String = buildString {
        append("<osmChange version=\"0.6\" generator=\"").append(escape(createdBy)).append("\"><modify>")
        for (e in elements) {
            append('<').append(e.type.api).append(" id=\"").append(e.id).append("\" version=\"").append(e.version)
            append("\" changeset=\"").append(changeset).append('"')
            if (e.type == OsmType.NODE) {
                append(" lat=\"").append(coordinate(e.lat ?: error("node ${e.id} has no place"))).append('"')
                append(" lon=\"").append(coordinate(e.lon ?: error("node ${e.id} has no place"))).append('"')
            }
            append('>')
            for (n in e.nodes) append("<nd ref=\"").append(n).append("\"/>")
            for (m in e.members) {
                append("<member type=\"").append(m.type.api).append("\" ref=\"").append(m.ref)
                append("\" role=\"").append(escape(m.role)).append("\"/>")
            }
            for ((k, v) in e.tags) tag(k, v)
            append("</").append(e.type.api).append('>')
        }
        append("</modify></osmChange>")
    }

    /**
     * Sends [edits] in one changeset with the user's [token] and [comment]. Edits of one element apply in their order;
     * an edit whose element changed what the user saw is left out and returned as a conflict. When nothing applies, no
     * changeset is opened. Where the upload finds an element changed between the read and the upload, the elements are
     * read again and the upload tried once more.
     */
    suspend fun send(token: String, edits: List<TagEdit>, comment: String): EditsSent {
        var plan = plan(edits)
        if (plan.elements.isEmpty()) return EditsSent(null, emptyList(), plan.conflicts)
        val changeset = open(token, comment)
        try {
            if (!upload(token, changeset, plan.elements)) {
                plan = plan(edits)
                if (plan.elements.isNotEmpty() && !upload(token, changeset, plan.elements)) {
                    throw ProviderException(ID, "The map changed under these edits twice; try again later")
                }
            }
        } finally {
            close(token, changeset)
        }
        return EditsSent(changeset, plan.applied, plan.conflicts)
    }

    private class Plan(val elements: List<OsmElement>, val applied: List<TagEdit>, val conflicts: List<TagEdit>)

    /** Each edited element read now and every edit of it applied in order, the conflicts apart. */
    private suspend fun plan(edits: List<TagEdit>): Plan {
        val now = LinkedHashMap<Pair<OsmType, Long>, OsmElement?>()
        val applied = ArrayList<TagEdit>()
        val conflicts = ArrayList<TagEdit>()
        for (edit in edits) {
            val key = edit.type to edit.id
            if (key !in now) now[key] = element(edit.type, edit.id)
            val next = now[key]?.let { applied(it, edit) }
            if (next == null) {
                conflicts += edit
            } else {
                now[key] = next
                applied += edit
            }
        }
        val touched = applied.map { it.type to it.id }.toSet()
        return Plan(now.filterKeys { it in touched }.values.filterNotNull(), applied, conflicts)
    }

    private suspend fun open(token: String, comment: String): Long {
        val req = authed(token, "$base/api/0.6/changeset/create").put(changesetXml(comment).toRequestBody(XML)).build()
        val res = http.execute(req)
        if (res.code == 401) throw ProviderException(ID, "OpenStreetMap no longer accepts this sign-in")
        return res.requireOk().text.trim().toLongOrNull() ?: throw ProviderException(ID, "OpenStreetMap opened no changeset")
    }

    /** True when the upload went in; false where an element changed between the read and the upload (HTTP 409). */
    private suspend fun upload(token: String, changeset: Long, elements: List<OsmElement>): Boolean {
        val body = osmChangeXml(changeset, elements).toRequestBody(XML)
        val res = http.execute(authed(token, "$base/api/0.6/changeset/$changeset/upload").post(body).build())
        if (res.code == 409) return false
        res.requireOk()
        return true
    }

    private suspend fun close(token: String, changeset: Long) {
        runCatching { http.execute(authed(token, "$base/api/0.6/changeset/$changeset/close").put(ByteArray(0).toRequestBody()).build()) }
    }

    private fun authed(token: String, url: String): Request.Builder =
        http.request(url).header("Authorization", "Bearer $token").header("Accept", "*/*")

    private fun StringBuilder.tag(k: String, v: String) {
        append("<tag k=\"").append(escape(k)).append("\" v=\"").append(escape(v)).append("\"/>")
    }

    companion object {
        const val ID = "osm-edits"
        private val XML = "text/xml; charset=utf-8".toMediaType()

        val INFO = ProviderInfo(
            id = ID, name = "OpenStreetMap edits", licence = "ODbL", attribution = "© OpenStreetMap contributors",
            url = "https://www.openstreetmap.org",
            note = "Edits are sent with your own OpenStreetMap account, only after you review them; they are public and show your user name.",
            hosts = listOf("api.openstreetmap.org", "www.openstreetmap.org"), switchable = false,
        )

        /** An element of the API's JSON (`elements[]`). */
        internal fun parse(o: JsonObject): OsmElement? {
            val type = OsmType.entries.firstOrNull { it.api == o.str("type") } ?: return null
            val tags = o.obj("tags")?.mapNotNull { (k, v) -> v.asString()?.let { k to it } }?.toMap(LinkedHashMap()) ?: emptyMap()
            return OsmElement(
                type = type,
                id = o.long("id") ?: return null,
                version = o.int("version") ?: return null,
                tags = tags,
                lat = o["lat"].asDouble(),
                lon = o["lon"].asDouble(),
                nodes = o.arr("nodes").mapNotNull { it.asLong() },
                members = o.arr("members").mapNotNull { it.asObject() }.mapNotNull { m ->
                    val t = OsmType.entries.firstOrNull { it.api == m.str("type") } ?: return@mapNotNull null
                    OsmMember(t, m.long("ref") ?: return@mapNotNull null, m["role"].asString().orEmpty())
                },
            )
        }

        /** An XML attribute's text: the five escapes, and tabs and line breaks kept as character references. */
        internal fun escape(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    '\t' -> append("&#9;")
                    '\n' -> append("&#10;")
                    '\r' -> append("&#13;")
                    else -> append(c)
                }
            }
        }

        /** A coordinate as OSM keeps it: at most seven decimals, trailing zeros dropped. */
        internal fun coordinate(v: Double): String = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')
    }
}
