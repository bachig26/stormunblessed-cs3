package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

open class BflixProvider : MainAPI() {
    override var mainUrl = "https://bflix.ru"
    override var name = "Bflix"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    //override val uniqueId: Int by lazy { "BflixProvider".hashCode() }

    companion object {
        private const val nineAnimeKey =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private const val mainKey = "DZmuZuXqa9O0z3b7"

        fun encodeVrf(text: String, mainKey: String): String {
            return encode(
                encrypt(
                    cipher(mainKey, encode(text)),
                    nineAnimeKey
                )
            )
        }

        fun decodeVrf(text: String, mainKey: String): String {
            return decode(
                decrypt(
                    cipher(mainKey, encode(text)),
                    nineAnimeKey
                )
            )
        }

        fun encrypt(input: String, key: String): String {
            if (input.any { it.code > 255 }) throw Exception("illegal characters!")
            var output = ""
            for (i in input.indices step 3) {
                val a = intArrayOf(-1, -1, -1, -1)
                a[0] = input[i].code shr 2
                a[1] = (3 and input[i].code) shl 4
                if (input.length > i + 1) {
                    a[1] = a[1] or (input[i + 1].code shr 4)
                    a[2] = (15 and input[i + 1].code) shl 2
                }
                if (input.length > i + 2) {
                    a[2] = a[2] or (input[i + 2].code shr 6)
                    a[3] = 63 and input[i + 2].code
                }
                for (n in a) {
                    if (n == -1) output += "="
                    else {
                        if (n in 0..63) output += key[n]
                    }
                }
            }
            return output
        }

        fun cipher(key: String, text: String): String {
            val arr = IntArray(256) { it }

            var u = 0
            var r: Int
            arr.indices.forEach {
                u = (u + arr[it] + key[it % key.length].code) % 256
                r = arr[it]
                arr[it] = arr[u]
                arr[u] = r
            }
            u = 0
            var c = 0

            return text.indices.map { j ->
                c = (c + 1) % 256
                u = (u + arr[c]) % 256
                r = arr[c]
                arr[c] = arr[u]
                arr[u] = r
                (text[j].code xor arr[(arr[c] + arr[u]) % 256]).toChar()
            }.joinToString("")
        }

        private fun decrypt(input: String, key: String): String {
            val t = if (input.replace("""[\t\n\f\r]""".toRegex(), "").length % 4 == 0) {
                input.replace("""==?$""".toRegex(), "").replace("%2F","/")
            } else input.replace("%2F","/")
            if (t.length % 4 == 1 || t.contains("""[^+/0-9A-Za-z]""".toRegex())) throw Exception("bad input")
            var i: Int
            var r = ""
            var e = 0
            var u = 0
            for (o in t.indices) {
                e = e shl 6
                i = key.indexOf(t[o])
                e = e or i
                u += 6
                if (24 == u) {
                    r += ((16711680 and e) shr 16).toChar()
                    r += ((65280 and e) shr 8).toChar()
                    r += (255 and e).toChar()
                    e = 0
                    u = 0
                }
            }
            return if (12 == u) {
                e = e shr 4
                r + e.toChar()
            } else {
                if (18 == u) {
                    e = e shr 2
                    r += ((65280 and e) shr 8).toChar()
                    r += (255 and e).toChar()
                }
                r
            }
        }

        fun encode(input: String): String =
            java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20").replace(" ","%20")

        private fun decode(input: String): String = java.net.URLDecoder.decode(input, "utf-8")
    }


    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get("$mainUrl/home").document
        val testa = listOf(
            Pair("Movies", "div.tab-content[data-name=movies] div.filmlist div.item"),
            Pair("Shows", "div.tab-content[data-name=shows] div.filmlist div.item"),
            Pair("Trending", "div.tab-content[data-name=trending] div.filmlist div.item"),
            Pair(
                "Latest Movies",
                "div.container section.bl:contains(Latest Movies) div.filmlist div.item"
            ),
            Pair(
                "Latest TV-Series",
                "div.container section.bl:contains(Latest TV-Series) div.filmlist div.item"
            ),
        )

        testa.apmap {(name, element) ->
            val test = soup.select(element).map {
                val title = it.selectFirst("h3 a")!!.text()
                val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                val qualityInfo = it.selectFirst("div.quality")!!.text()
                val quality = getQualityFromString(qualityInfo)
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst("a.poster img")!!.attr("src"),
                    null,
                    null,
                    quality = quality
                )
            }
            items.add(HomePageList(name, test))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }


    data class QuickSearchResult(
        @JsonProperty("html") val html: String? = null,
        //@JsonProperty("linkMore") val linkMore: String? = null
    )
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val encodedquery = encodeVrf(query, mainKey)
        val url = "$mainUrl/ajax/film/search?vrf=$encodedquery&keyword=$query"
        val response = app.get(url).parsedSafe<QuickSearchResult>()
        val elementa = if (mainUrl.contains("fmovies")) "a.item" else "a"
        val document = Jsoup.parse(response?.html ?: return null)
        return document.select(elementa).mapNotNull {element ->
            val link = fixUrl(element?.attr("href") ?: return@mapNotNull null)
            val title = (element.selectFirst("div.title") ?: element.selectFirst("div.name"))?.text() ?: return@mapNotNull null
            val img = (element.selectFirst("div.poster img") ?: element.selectFirst("img"))?.attr("src") ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, link){
                this.posterUrl = img
            }
        }
    }
    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedquery = encodeVrf(query, mainKey)
        val url = "$mainUrl/search?keyword=$query&vrf=$encodedquery"
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        return document.select(".filmlist div.item").map {
            val title = it.selectFirst("h3 a")!!.text()
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image = it.selectFirst("a.poster img")!!.attr("src")
            val isMovie = href.contains("/movie/")
            val qualityInfo = it.selectFirst("div.quality")!!.text()
            val quality = getQualityFromString(qualityInfo)

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null,
                    quality = quality
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null,
                    quality = quality
                )
            }
        }
    }

    data class Response(
        @JsonProperty("html") val html: String
    )

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document
        val movieid = soup.selectFirst("div#watch")!!.attr("data-id")
        val movieidencoded = encodeVrf(movieid, mainKey)
        val title = soup.selectFirst("div.info h1")!!.text()
        val description = soup.selectFirst(".info .desc")?.text()?.trim()
        val poster: String? = try {
            soup.selectFirst("img.poster")!!.attr("src")
        } catch (e: Exception) {
            soup.selectFirst(".info .poster img")!!.attr("src")
        }

        val tags = soup.select("div.info .meta div:contains(Genre) a").map { it.text() }
        val vrfUrl = "$mainUrl/ajax/film/servers?id=$movieid&vrf=$movieidencoded"
        val episodes = Jsoup.parse(
            app.get(
                vrfUrl
            ).parsed<Response>().html
        ).select("div.episode").map {
            val a = it.selectFirst("a")
            val href = fixUrl(a!!.attr("href"))
            val extraData = a.attr("data-kname").let { str ->
                str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
            }
            val isValid = extraData.size == 2
            val episode = if (isValid) extraData.getOrNull(1) else null
            val season = if (isValid) extraData.getOrNull(0) else null
            val secondhref = if (episode == null || season == null) "$url/1-full" else "$url/$season-$episode"
            val eptitle = it.selectFirst(".episode a span.name")!!.text()
            val secondtitle = it.selectFirst(".episode a span")!!.text()
                .replace(Regex("(Episode (\\d+):|Episode (\\d+)-|Episode (\\d+))"), "") ?: ""
            Episode(
                secondhref,
                secondtitle + eptitle,
                season,
                episode,
            )
        }
        val tvType =
            if (url.contains("/movie/") && episodes.size == 1) TvType.Movie else TvType.TvSeries
        val recommendations =
            soup.select("div.bl-2 section.bl div.content div.filmlist div.item")
                .mapNotNull { element ->
                    val recTitle = element.select("h3 a").text() ?: return@mapNotNull null
                    val image = element.select("a.poster img")?.attr("src")
                    val recUrl = fixUrl(element.select("a").attr("href"))
                    MovieSearchResponse(
                        recTitle,
                        recUrl,
                        this.name,
                        if (recUrl.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                        image,
                        year = null
                    )
                }
        val rating = soup.selectFirst(".info span.imdb")?.text()?.toRatingInt()
        val durationdoc = soup.selectFirst("div.info div.meta").toString()
        val durationregex = Regex("((\\d+) min)")
        val yearegex = Regex("<span>(\\d+)</span>")
        val duration = if (durationdoc.contains("na min")) null
        else durationregex.find(durationdoc)?.destructured?.component1()?.replace(" min", "")
            ?.toIntOrNull()
        val year = if (mainUrl == "https://bflix.ru") {
            yearegex.find(durationdoc)?.destructured?.component1()
                ?.replace(Regex("<span>|</span>"), "")
        } else null
        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    poster,
                    year?.toIntOrNull(),
                    description,
                    null,
                    rating,
                    tags,
                    recommendations = recommendations,
                    duration = duration,
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    year?.toIntOrNull(),
                    description,
                    rating,
                    tags,
                    recommendations = recommendations,
                    duration = duration
                )
            }
            else -> null
        }
    }


    data class Subtitles(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val kind: String
    )

    data class Links(
        @JsonProperty("url") val url: String
    )

    data class Servers(
        @JsonProperty("28") val mcloud: String?,
        @JsonProperty("35") val mp4upload: String?,
        @JsonProperty("40") val streamtape: String?,
        @JsonProperty("41") val vidstream: String?,
        @JsonProperty("43") val videovard: String?
    )

    class ServersID(elements: Map<String, String>) : HashMap<String, String>(elements)



    private suspend fun getStream(
        streamLink: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    )  {
        return M3u8Helper.generateM3u8(
            this.name,
            streamLink,
            referer
        ).forEach { sub ->
            callback(
                ExtractorLink(
                    this.name,
                    name,
                    sub.url,
                    referer,
                    getQualityFromName(sub.quality.toString()),
                    true
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val soup = app.get(data).document
        val movieid = encode(soup.selectFirst("div#watch")?.attr("data-id") ?: return false)
        val movieidencoded = encodeVrf(movieid, mainKey)
        val vidstream = app.get(data, interceptor = JsInterceptor("41"))
        val vidsurl = vidstream.url
        val mcloud = app.get(data, interceptor = JsInterceptor("28"))
        val murl = mcloud.url
        val lll = listOf(vidsurl, murl)
        lll.apmap {  link->
            val vv = link.contains("mcloud")
            val name = if (vv) "Mcloud" else "Vidstream"
            val ref = if (vv) "https://mcloud.to/" else ""
            getStream(link, name, ref, callback)
        }
        Jsoup.parse(
            parseJson<Response>(
                app.get(
                    "$mainUrl/ajax/film/servers?id=$movieid&vrf=$movieidencoded"
                ).text
            ).html
        )
            .select("html body #episodes").map {
                val cleandata = data.replace(mainUrl, "")
                val a = it.select("a").map {
                    it.attr("data-kname")
                }
                val tvType =
                    if (data.contains("movie/") && a.size == 1) TvType.Movie else TvType.TvSeries
                val servers = if (tvType == TvType.Movie) it.select(".episode a").attr("data-ep")
                else
                    it.select(".episode a[href=$cleandata]").attr("data-ep")
                        ?: it.select(".episode a[href=${cleandata.replace("/1-full", "")}]")
                            .attr("data-ep")
                val jsonservers = parseJson<Servers?>(servers) ?: return@map
                val sublink =
                    app.get("$mainUrl/ajax/episode/subtitles/${jsonservers.mcloud}").text
                val jsonsub = parseJson<List<Subtitles>>(sublink)
                jsonsub.forEach { subtitle ->
                    subtitleCallback(
                        SubtitleFile(subtitle.label, subtitle.file)
                    )
                }
            }

        return true
    }
}
