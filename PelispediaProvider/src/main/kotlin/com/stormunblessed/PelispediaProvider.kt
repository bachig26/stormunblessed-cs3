package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.gms.dynamite.DynamiteModule.LoadingException
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor

class PelispediaProvider:MainAPI() {
    override var mainUrl = "https://pelispedia.is"
    override var name = "Pelispedia"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    data class PelispediaHome (

        @JsonProperty("post_id"    ) val postId    : Int?    = null,
        @JsonProperty("post_title" ) val postTitle : String? = null,
        @JsonProperty("post_name"  ) val postName  : String? = null,
        @JsonProperty("post_image" ) val postImage : String? = null,
        @JsonProperty("post_type"     ) val postType     : String? = null,

        )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Películas","$mainUrl/hcapi/homepage/movies/"),
            Pair("Series","$mainUrl/hcapi/homepage/series/"),
        )
        urls.apmap { (name, url) ->
            val json = app.get(url).text
            val aaa = parseJson<List<PelispediaHome>>(json)
            val home = aaa.map {
                val title = it.postTitle
                val image = it.postImage
                val postlink = if (url.contains("movies")) "$mainUrl/hcapi/movie/${it.postId}" else "$mainUrl/hcapi/serie/${it.postId}"
                TvSeriesSearchResponse(
                    title!!,
                    postlink,
                    name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/hcapi/search/$query/"
        val text = app.get(url).text
        val json = parseJson<List<PelispediaHome>>(text)
        return json.map {
            val title = it.postTitle
            val image = it.postImage
            val postlink = if (it.postType!!.contains("movies")) "$mainUrl/hcapi/movie/${it.postId}" else "$mainUrl/hcapi/serie/${it.postId}"
            TvSeriesSearchResponse(
                title!!,
                postlink,
                name,
                TvType.TvSeries,
                image,
                null,
                null
            )
        } }

    data class PelispediaMetadata (
        @JsonProperty("Movie"       ) val movie       : TestMetadata?                 = TestMetadata(),
        @JsonProperty("Serie"       ) val serie       : TestMetadata?                 = TestMetadata(),

        )

    data class MovieStreams (
        @JsonProperty("type"    ) val type    : Int?    = null,
        @JsonProperty("server"  ) val server  : Int?    = null,
        @JsonProperty("lang"    ) val lang    : String? = null,
        @JsonProperty("quality" ) val quality : Int?    = null,
        @JsonProperty("link"    ) val link    : String? = null
    )


    data class SerieTemps (
        @JsonProperty("temp_num"    ) val tempNum    : Int?    = null,
        @JsonProperty("temp_name"   ) val tempName   : String? = null,
        @JsonProperty("temp_poster" ) val tempPoster : String? = null
    )

    data class TestMetadata (
        @JsonProperty("movie_name"       ) val movieName       : String?                 = null,
        @JsonProperty("movie_content"    ) val movieContent    : String?                 = null,
        @JsonProperty("movie_release"    ) val movieRelease    : String?                 = null,
        @JsonProperty("movie_backdrop"   ) val movieBackdrop   : String?                 = null,
        @JsonProperty("movie_poster"     ) val moviePoster     : String?                 = null,
        @JsonProperty("movie_trailer"    ) val movieTrailer    : String?                 = null,
        @JsonProperty("movie_duration"   ) val movieDuration   : String?                 = null,
        @JsonProperty("movie_categories" ) val movieCategories : String?                 = null,
        @JsonProperty("movie_streams"    ) val movieStreams    : ArrayList<MovieStreams> = arrayListOf(),
        @JsonProperty("serie_id"         ) val serieId         : Int?                  = null,
        @JsonProperty("serie_name"       ) val serieName       : String?               = null,
        @JsonProperty("serie_content"    ) val serieContent    : String?               = null,
        @JsonProperty("serie_backdrop"   ) val serieBackdrop   : String?               = null,
        @JsonProperty("serie_poster"     ) val seriePoster     : String?               = null,
        @JsonProperty("serie_categories" ) val serieCategories : String?               = null,
        @JsonProperty("serie_temps"      ) val serieTemps      : ArrayList<SerieTemps> = arrayListOf()
    )



    data class EpisodesMetadata (
        @JsonProperty("episode_id"       ) val episodeId       : Int?    = null,
        @JsonProperty("episode_number"   ) val episodeNumber   : Int?    = null,
        @JsonProperty("episode_poster"   ) val episodePoster   : String? = null,
        @JsonProperty("episode_name"     ) val episodeName     : String? = null,
        @JsonProperty("episode_overview" ) val episodeOverview : String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val text = app.get(url).text
        val tvType = if (url.contains("movie")) TvType.Movie else TvType.TvSeries
        val isMovie = tvType == TvType.Movie
        val jsonfix = if (url.contains("movie")) "{\"Movie\":$text}" else "{\"Serie\":$text}"
        val json = tryParseJson<PelispediaMetadata>(jsonfix) ?: throw ErrorLoadingException("Intenta de nuevo")
        val metadata: TestMetadata? = if (isMovie) json.movie else json.serie
        val title = metadata?.movieName ?: metadata?.serieName
        val description = metadata?.movieContent ?: metadata?.serieContent
        val poster = metadata?.moviePoster ?: metadata?.seriePoster
        val realposter = "https://image.tmdb.org/t/p/w1280/$poster"
        val tags = metadata?.movieCategories ?: metadata?.serieCategories
        val realtags = tags?.split(",")
        val episodes = ArrayList<Episode>()
        if (!isMovie) {
            val seasonlinks = metadata?.serieTemps?.map { temp -> "$url/temp/${temp.tempNum}" }?.reversed()
            seasonlinks?.apmap { seasonlink ->
                val textseason = app.get(seasonlink).text
                val jsonseasons = parseJson<List<EpisodesMetadata>>(textseason)
                val season = seasonlink.substringAfter("/temp/").toIntOrNull()
                println("SEASON $season")
                jsonseasons.map { epdata ->
                    val epnum = epdata.episodeNumber
                    val eplink = "$mainUrl/hcapi/serie/cap/${epdata.episodeId}"
                    val epposter = "https://image.tmdb.org/t/p/w500/${epdata.episodePoster}"
                    val epname = epdata.episodeName
                    val epdesc = epdata.episodeOverview
                    episodes.add(Episode(
                        eplink,
                        epname,
                        season,
                        epnum,
                        posterUrl = epposter,
                        description = epdesc
                    ))
                }
            }
        }

        return when (tvType){
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title!!, url, tvType, episodes,) {
                    this.plot = description
                    this.posterUrl = realposter
                    this.tags = realtags
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title!!, url, tvType, metadata?.movieStreams,) {
                    this.plot = description
                    this.posterUrl = realposter
                    this.tags = realtags
                    this.year = metadata?.movieRelease?.toIntOrNull()
                }
            }
            else -> null
        }

    }

    private fun getStream(MovieStream: List<MovieStreams>,
                          callback: (ExtractorLink) -> Unit,
                          subtitleCallback: (SubtitleFile) -> Unit
    ): List<Unit> = MovieStream.apmap {
        for (extractor in extractorApis) {
            val reallang = it.lang?.replace("VOSE", "Subtitulado")
            if (it.link!!.startsWith(extractor.mainUrl)) {
                extractor.getSafeUrl2(it.link)?.forEach {
                    it.name += " $reallang"
                    callback(it)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tvType = if (data.contains("serie")) TvType.TvSeries else TvType.Movie
        val isMovie = tvType == TvType.Movie
        if (isMovie) {
            val jsonmovie = parseJson<List<MovieStreams>>(data)
            getStream(jsonmovie, callback, subtitleCallback)
        } else {
            val response = app.get(data).text
            val jsonserie = parseJson<List<MovieStreams>>(response)
            getStream(jsonserie, callback, subtitleCallback)
        }
        return true
    }
}