package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName

class KamyrollProvider: MainAPI() {
    companion object {
        var latestHeader: Map<String, String> = emptyMap()
    }
    override var name = "Kamyroll BETA"
    override var mainUrl = "https://api.kamyroll.tech" //apirurl
    override val instantLinkLoading = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
    )

    data class Token (
        @JsonProperty("access_token" ) val accessToken : String,
        @JsonProperty("token_type"   ) val tokenType   : String,
        @JsonProperty("expires_in"   ) val expiresIn   : Int?=null
    )

    private suspend fun getToken(): Map<String, String> {
        //Thanks to https://github.com/saikou-app/saikou/blob/main/app/src/main/java/ani/saikou/parsers/anime/Kamyroll.kt
        val tokenrequest = app.post("$mainUrl/auth/v1/token",
            data = mapOf(
                "device_id" to "com.service.data",
                "device_type" to "cloudstream",
                "access_token" to "HMbQeThWmZq4t7w",
            )
        ).parsed<Token>()
        val header = mapOf(
            "Authorization" to "${tokenrequest.tokenType} ${tokenrequest.accessToken}"
        )
        latestHeader = header
        return latestHeader
    }


    data class KamyrollMain (
        @JsonProperty("items"     ) var items   : ArrayList<Items> = arrayListOf()
    )
    data class Items (
        @JsonProperty("__class__"         ) var _class_         : String?  = null,
        @JsonProperty("__href__"          ) var _href_          : String?  = null,
        @JsonProperty("id"                ) var id              : String?  = null,
        @JsonProperty("channel_id"        ) var channelId       : String?  = null,
        @JsonProperty("series_id"         ) var seriesId        : String?  = null,
        @JsonProperty("series_title"      ) var seriesTitle     : String?  = null,
        @JsonProperty("series_slug_title" ) var seriesSlugTitle : String?  = null,
        @JsonProperty("season_id"         ) var seasonId        : String?  = null,
        @JsonProperty("season_title"      ) var seasonTitle     : String?  = null,
        @JsonProperty("season_slug_title" ) var seasonSlugTitle : String?  = null,
        @JsonProperty("season_number"     ) var seasonNumber    : Int?     = null,
        @JsonProperty("episode"           ) var episode         : String?  = null,
        @JsonProperty("episode_number"    ) var episodeNumber   : Int?     = null,
        @JsonProperty("sequence_number"   ) var sequenceNumber  : Int?     = null,
        @JsonProperty("title"             ) var title           : String?  = null,
        @JsonProperty("slug_title"        ) var slugTitle       : String?  = null,
        @JsonProperty("description"       ) var description     : String?  = null,
        @JsonProperty("is_mature"         ) var isMature        : Boolean? = null,
        @JsonProperty("episode_air_date"  ) var episodeAirDate  : String?  = null,
        @JsonProperty("is_subbed"         ) var isSubbed        : Boolean? = null,
        @JsonProperty("is_dubbed"         ) var isDubbed        : Boolean? = null,
        @JsonProperty("is_clip"           ) var isClip          : Boolean? = null,
        @JsonProperty("images"            ) var images          : Images?  = Images(),
        @JsonProperty("duration_ms"       ) var durationMs      : Int?     = null,
        @JsonProperty("is_premium_only"   ) var isPremiumOnly   : Boolean? = null,
    )

    data class Images (
        @JsonProperty("poster_tall" ) var posterTall : ArrayList<PosterTall> = arrayListOf(),
    )
    data class PosterTall (
        @JsonProperty("height" ) var height : Int?    = null,
        @JsonProperty("source" ) var source : String? = null,
        @JsonProperty("type"   ) var type   : String? = null,
        @JsonProperty("width"  ) var width  : Int?    = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        getToken()
        val res = app.get("$mainUrl/content/v1/updated",
            headers = latestHeader,
            params = mapOf(
                "channel_id" to "crunchyroll"
            )
        ).parsedSafe<KamyrollMain>()
        val home = res?.items?.map {
            val title = it.seasonTitle
            val seriesID = it.seriesId
            val seasonID = it.seasonId
            val dubStat = if (it.isDubbed == true) DubStatus.Dubbed else DubStatus.Subbed
            val posterstring = it.images?.posterTall.toString()
            val posterregex = Regex("height=2340.*source=(.*),.*type=poster_tall")
            val poster = posterregex.find(posterstring)?.destructured?.component1() ?: ""
            val epNum = it.episodeNumber
            val desc = it.description
            val data = "{\"title\":\"$title\",\"description\":\"${desc}\",\"id\":\"$seriesID\",\"poster\":\"$poster\"}"
            newAnimeSearchResponse(title!!, data) {
                this.posterUrl = poster
                addDubStatus(dubStat, epNum)
            }
        }
        items.add(HomePageList("Updated",home!!))

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    private data class KamySearchResponse(
        @JsonProperty("total") val total: Long? = null,
        @JsonProperty("items") val items: List<ResponseItem>? = null
    )
    data class ResponseItem(
        @JsonProperty("items") val items: List<ItemItem>

    )
    data class ItemItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("media_type") val type: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("images") val images: Images? = null,
        @JsonProperty("description") val desc: String?,
    )
    override suspend fun search(query: String): List<SearchResponse> {
        getToken()
        val main = app.get("$mainUrl/content/v1/search",
            headers = latestHeader,
            params = mapOf(
                "query" to query,
                "channel_id" to "crunchyroll"
            )
        ).parsed<KamySearchResponse>()
        val search = ArrayList<SearchResponse>()
        val jsonsearch = main.items?.map {
            val aaa = it.items.map {
                val title = it.title
                val id = it.id
                val posterstring = it.images?.posterTall.toString()
                val posterregex = Regex("height=2340.*source=(.*),.*type=poster_tall")
                val poster = posterregex.find(posterstring)?.destructured?.component1() ?: ""
                val desc = it.desc?.replace("...","\\u2026")?.replace("'","\\u0027")
                    ?.replace("","")
                val data = "{\"title\":\"$title\",\"description\":\"$title\",\"id\":\"$id\",\"poster\":\"$poster\"}"
                search.add(newAnimeSearchResponse(title, data){
                    this.posterUrl = poster
                })
            }
        }
        return search
    }
    data class KamyLoadJson (
        @JsonProperty("title"       ) var title       : String? = null,
        @JsonProperty("description" ) var description : String? = null,
        @JsonProperty("id"          ) var id          : String? = null,
        @JsonProperty("poster"      ) var poster      : String? = null
    )
    data class KamySeasons (
        @JsonProperty("items"     ) var items   : ArrayList<ItemsSeason> = arrayListOf()
    )
    data class ItemsSeason (
        @JsonProperty("id"            ) var id           : String?             = null,
        @JsonProperty("channel_id"    ) var channelId    : String?             = null,
        @JsonProperty("title"         ) var title        : String?             = null,
        @JsonProperty("slug_title"    ) var slugTitle    : String?             = null,
        @JsonProperty("series_id"     ) var seriesId     : String?             = null,
        @JsonProperty("season_number" ) var seasonNumber : Int?                = null,
        @JsonProperty("description"   ) var description  : String?             = null,
        @JsonProperty("episodes"      ) var episodes     : ArrayList<Episodes> = arrayListOf(),
        @JsonProperty("episode_count" ) var episodeCount : Int?                = null
    )

    data class Episodes (
        @JsonProperty("id"                ) var id              : String?  = null,
        @JsonProperty("channel_id"        ) var channelId       : String?  = null,
        @JsonProperty("series_id"         ) var seriesId        : String?  = null,
        @JsonProperty("series_title"      ) var seriesTitle     : String?  = null,
        @JsonProperty("series_slug_title" ) var seriesSlugTitle : String?  = null,
        @JsonProperty("season_id"         ) var seasonId        : String?  = null,
        @JsonProperty("season_title"      ) var seasonTitle     : String?  = null,
        @JsonProperty("season_slug_title" ) var seasonSlugTitle : String?  = null,
        @JsonProperty("season_number"     ) var seasonNumber    : Int?     = null,
        @JsonProperty("episode"           ) var episode         : String?  = null,
        @JsonProperty("episode_number"    ) var episodeNumber   : Int?     = null,
        @JsonProperty("sequence_number"   ) var sequenceNumber  : Int?     = null,
        @JsonProperty("title"             ) var title           : String?  = null,
        @JsonProperty("slug_title"        ) var slugTitle       : String?  = null,
        @JsonProperty("description"       ) var description     : String?  = null,
        @JsonProperty("hd_flag"           ) var hdFlag          : Boolean? = null,
        @JsonProperty("is_mature"         ) var isMature        : Boolean? = null,
        @JsonProperty("episode_air_date"  ) var episodeAirDate  : String?  = null,
        @JsonProperty("is_subbed"         ) var isSubbed        : Boolean? = null,
        @JsonProperty("is_dubbed"         ) var isDubbed        : Boolean? = null,
        @JsonProperty("is_clip"           ) var isClip          : Boolean? = null,
        @JsonProperty("type"              ) var type            : String?  = null,
        @JsonProperty("images"            ) var images          : ImagesEps?  = ImagesEps(),
        @JsonProperty("duration_ms"       ) var durationMs      : Int?     = null,
        @JsonProperty("is_premium_only"   ) var isPremiumOnly   : Boolean? = null
    )

    data class ImagesEps (
        @JsonProperty("thumbnail" ) var thumbnail : ArrayList<Thumbnail> = arrayListOf()
    )
    data class Thumbnail (
        @JsonProperty("width"  ) var width  : Int?    = null,
        @JsonProperty("height" ) var height : Int?    = null,
        @JsonProperty("type"   ) var type   : String? = null,
        @JsonProperty("source" ) var source : String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val fixdata = url.replace("https://api.kamyroll.tech/","")
        val testt = fixdata.toJson()
        val json = parseJson<KamyLoadJson>(testt)
        val title = json.title
        val desc = json.description
        val poster = json.poster
        val id = json.id
        val seasonsresponse = app.get("$mainUrl/content/v1/seasons",
            headers = latestHeader,
            params = mapOf(
                "channel_id" to "crunchyroll",
                "id" to id!!
            )
        ).parsed<KamySeasons>()
        val eps = ArrayList<Episode>()
        val dubeps = ArrayList<Episode>()
        val test = seasonsresponse.items.forEach {
            val dubTitle = it.title
            it.episodes.forEach {
                val dub = it.isDubbed == true
                val eptitle = it.title
                val epthumb = it.images?.thumbnail?.getOrNull(6)?.source
                val epdesc = if (dub) "$dubTitle ${it.description}" else it.description
                val epnum = it.episodeNumber
                val seasonID = it.seasonNumber
                val epID = it.id
                val ep = Episode(
                    data = epID!!,
                    description = epdesc,
                    name = eptitle,
                    posterUrl = epthumb,
                    season = seasonID
                )
                if (dub) dubeps.add(ep) else eps.add(ep)
            }
        }


        val link = "https://www.crunchyroll.com/series/$id/"
        return newAnimeLoadResponse(title!!, link, TvType.Anime){
            addEpisodes(DubStatus.Subbed,eps)
            addEpisodes(DubStatus.Dubbed,dubeps)
            this.posterUrl = poster
            this.plot = desc
        }
    }

    data class KamyStreams (
        @JsonProperty("channel_id" ) var channelId : String?              = null,
        @JsonProperty("media_id"   ) var mediaId   : String?              = null,
        @JsonProperty("subtitles"  ) var subtitles : ArrayList<Subtitles> = arrayListOf(),
        @JsonProperty("streams"    ) var streams   : ArrayList<Streams>   = arrayListOf()
    )

    data class Subtitles (
        @JsonProperty("locale" ) var locale : String? = null,
        @JsonProperty("url"    ) var url    : String? = null,
        @JsonProperty("format" ) var format : String? = null
    )
    data class Streams (
        @JsonProperty("type"           ) var type          : String? = null,
        @JsonProperty("audio_locale"   ) var audioLocale   : String? = null,
        @JsonProperty("hardsub_locale" ) var hardsubLocale : String? = null,
        @JsonProperty("url"            ) var url           : String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamsrequest = app.get("$mainUrl/videos/v1/streams",
            headers = latestHeader,
            params = mapOf(
                "id" to data,
                "channel_id" to "crunchyroll",
                "type" to "adaptive_hls"
            )).parsed<KamyStreams>()
        streamsrequest.streams.forEach {
            val urlstream = it.url!!
            if (it.hardsubLocale == null) generateM3u8(
                this.name,
                urlstream,
                ""
            ).forEach { dub ->
                callback(
                    ExtractorLink(
                        this.name,
                        "Kamyroll Dubbed ${it.audioLocale}",
                        dub.url,
                        "",
                        getQualityFromName(dub.quality.toString()),
                        true
                    )
                )
            } else
                generateM3u8(
                    this.name,
                    urlstream,
                    ""
                ).forEach { sub ->
                    callback(
                        ExtractorLink(
                            this.name,
                            "Kamyroll ${it.hardsubLocale}",
                            sub.url,
                            "",
                            getQualityFromName(sub.quality.toString()),
                            true
                        )
                    )
                }
        }
        return true
    }
}