package eu.kanade.tachiyomi.extension.en.readcomiconline

import android.app.Application
import android.content.SharedPreferences
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Readcomiconline : ConfigurableSource, ParsedHttpSource() {

    override val name = "ReadComicOnline"

    override val baseUrl = "https://readcomiconline.li"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(::captchaInterceptor)
        .build()

    private fun captchaInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val location = response.header("Location")
        if (location?.startsWith("/Special/AreYouHuman") == true) {
            captchaUrl = "$baseUrl/Special/AreYouHuman"
            throw Exception("Solve captcha in WebView")
        }

        return response
    }

    private var captchaUrl: String? = null

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularMangaSelector() = ".list-comic > .item > a:first-child"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/ComicList/MostPopular?page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/ComicList/LatestUpdate?page=$page", headers)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.text()
            thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
        }
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun popularMangaNextPageSelector() = "ul.pager > li > a:contains(Next)"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request { // publisher > writer > artist + sorting for both if else
        if (query.isEmpty() && (if (filters.isEmpty()) getFilterList() else filters).filterIsInstance<GenreList>().all { it.included.isEmpty() && it.excluded.isEmpty() }) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                var pathSegmentAdded = false

                for (filter in if (filters.isEmpty()) getFilterList() else filters) {
                    when (filter) {
                        is PublisherFilter -> {
                            if (filter.state.isNotEmpty()) {
                                addPathSegments("Publisher/${filter.state.replace(" ", "-")}")
                                pathSegmentAdded = true
                            }
                        }
                        is WriterFilter -> {
                            if (filter.state.isNotEmpty()) {
                                addPathSegments("Writer/${filter.state.replace(" ", "-")}")
                                pathSegmentAdded = true
                            }
                        }
                        is ArtistFilter -> {
                            if (filter.state.isNotEmpty()) {
                                addPathSegments("Artist/${filter.state.replace(" ", "-")}")
                                pathSegmentAdded = true
                            }
                        }
                        else -> {}
                    }

                    if (pathSegmentAdded) {
                        break
                    }
                }
                addPathSegment((if (filters.isEmpty()) getFilterList() else filters).filterIsInstance<SortFilter>().first().selected.toString())
                addQueryParameter("page", page.toString())
            }.build()
            return GET(url, headers)
        } else {
            val url = "$baseUrl/AdvanceSearch".toHttpUrl().newBuilder().apply {
                addQueryParameter("comicName", query.trim())
                addQueryParameter("page", page.toString())
                for (filter in if (filters.isEmpty()) getFilterList() else filters) {
                    when (filter) {
                        is Status -> addQueryParameter("status", arrayOf("", "Completed", "Ongoing")[filter.state])
                        is GenreList -> {
                            addQueryParameter("ig", filter.included.joinToString(","))
                            addQueryParameter("eg", filter.excluded.joinToString(","))
                        }
                        else -> {}
                    }
                }
            }.build()
            return GET(url, headers)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.barContent").first()!!

        val manga = SManga.create()
        manga.artist = infoElement.select("p:has(span:contains(Artist:)) > a").first()?.text()
        manga.author = infoElement.select("p:has(span:contains(Writer:)) > a").first()?.text()
        manga.genre = infoElement.select("p:has(span:contains(Genres:)) > *:gt(0)").text()
        manga.description = infoElement.select("p:has(span:contains(Summary:)) ~ p").text()
        manga.status = infoElement.select("p:has(span:contains(Status:))").first()?.text().orEmpty().let { parseStatus(it) }
        manga.thumbnail_url = document.select(".rightBox:eq(0) img").first()?.absUrl("src")
        return manga
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(realMangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    private fun realMangaDetailsRequest(manga: SManga): Request =
        super.mangaDetailsRequest(manga)

    override fun mangaDetailsRequest(manga: SManga): Request =
        captchaUrl?.let { GET(it, headers) }.also { captchaUrl = null }
            ?: super.mangaDetailsRequest(manga)

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "table.listing tr:gt(1)"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()!!

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.select("td:eq(1)").first()?.text()?.let {
            SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).parse(it)?.time ?: 0L
        } ?: 0
        return chapter
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val qualitySuffix = if ((qualitypref() != "lq" && serverpref() != "s2") || (qualitypref() == "lq" && serverpref() == "s2")) "&s=${serverpref()}&quality=${qualitypref()}" else "&s=${serverpref()}"
        return GET(baseUrl + chapter.url + qualitySuffix, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        if (rguardUrl == null) {
            rguardUrl = document.selectFirst("script[src*='rguard.min.js']")?.absUrl("src")
        }

        val script = document.selectFirst("script:containsData(beau)")?.data()
            ?: throw Exception("Failed to find image URLs")

        val cleanedScript = removeComments(script)

        val variableName = ARRAY_VAR.findAll(cleanedScript).map { it.groupValues[1] }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }

        return QuickJs.create().use { qjs ->
            qjs.execute(rguardBytecode)
            qjs.evaluate(script)

            variableName.forEach {
                val bool = qjs.evaluate(
                    """
                        var _0x49b8c2=_0x123c;function _0x123c(_0x3a3f70,_0x153a9a){var _0x500ddc=_0x500d();return _0x123c=function(_0x123c39,_0x130bcf){_0x123c39=_0x123c39-0x6c;var _0x40ac71=_0x500ddc[_0x123c39];return _0x40ac71;},_0x123c(_0x3a3f70,_0x153a9a);}function _0x500d(){var _0x30f66a=['12914343dbcuCG','8tTIdhc','39LHaBuI','11272136IRkEic','isArray','828602mqTUSC','7SBvdKh','232292tbxHGL','10035420aRjSoi','664765erNydn','1990674vTmFDr'];_0x500d=function(){return _0x30f66a;};return _0x500d();}(function(_0x214853,_0x338d23){var _0x1dd529=_0x123c,_0x48f320=_0x214853();while(!![]){try{var _0x51e687=parseInt(_0x1dd529(0x72))/0x1+parseInt(_0x1dd529(0x74))/0x2*(-parseInt(_0x1dd529(0x6f))/0x3)+parseInt(_0x1dd529(0x6e))/0x4*(parseInt(_0x1dd529(0x76))/0x5)+parseInt(_0x1dd529(0x6c))/0x6+parseInt(_0x1dd529(0x73))/0x7*(parseInt(_0x1dd529(0x70))/0x8)+-parseInt(_0x1dd529(0x6d))/0x9+parseInt(_0x1dd529(0x75))/0xa;if(_0x51e687===_0x338d23)break;else _0x48f320['push'](_0x48f320['shift']());}catch(_0xe6b639){_0x48f320['push'](_0x48f320['shift']());}}}(_0x500d,0xda445));try{Array[_0x49b8c2(0x71)]($it);}catch(_0x15c758){![];}
                    """.trimIndent(),
                ) as Boolean

                if (bool) {
                    return@use (qjs.evaluate(it) as Array<*>).map { it as String }.toList()
                }
            }
            emptyList()
        }
            .mapIndexed { i, imageUrl -> Page(i, "", imageUrl) }
    }

    override fun imageUrlParse(document: Document) = ""

    private class Status : Filter.TriState("Completed")
    private class Genre(name: String, val gid: String) : Filter.TriState(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.gid }

        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.gid }
    }
    open class SelectFilter(displayName: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        open val selected get() = options[state].second.takeUnless { it.isEmpty() }
    }

    private class PublisherFilter : Filter.Text("Publisher")
    private class WriterFilter : Filter.Text("Writer")
    private class ArtistFilter : Filter.Text("Artist")
    private class SortFilter : SelectFilter(
        "Sort By",
        arrayOf(
            Pair("Alphabet", ""),
            Pair("Popularity", "MostPopular"),
            Pair("Latest Update", "LatestUpdate"),
            Pair("New Comic", "Newest"),
        ),
    )

    override fun getFilterList() = FilterList(
        Status(),
        GenreList(getGenreList()),
        Filter.Separator(),
        Filter.Header("Filters below is ignored when Status,Genre or the queue is not empty."),
        SortFilter(),
        PublisherFilter(),
        WriterFilter(),
        ArtistFilter(),
    )

    // $("select[name=\"genres\"]").map((i,el) => `Genre("${$(el).next().text().trim()}", ${i})`).get().join(',\n')
    // on https://readcomiconline.li/AdvanceSearch
    private fun getGenreList() = listOf(
        Genre("Action", "1"),
        Genre("Adventure", "2"),
        Genre("Anthology", "38"),
        Genre("Anthropomorphic", "46"),
        Genre("Biography", "41"),
        Genre("Children", "49"),
        Genre("Comedy", "3"),
        Genre("Crime", "17"),
        Genre("Drama", "19"),
        Genre("Family", "25"),
        Genre("Fantasy", "20"),
        Genre("Fighting", "31"),
        Genre("Graphic Novels", "5"),
        Genre("Historical", "28"),
        Genre("Horror", "15"),
        Genre("Leading Ladies", "35"),
        Genre("LGBTQ", "51"),
        Genre("Literature", "44"),
        Genre("Manga", "40"),
        Genre("Martial Arts", "4"),
        Genre("Mature", "8"),
        Genre("Military", "33"),
        Genre("Mini-Series", "56"),
        Genre("Movies & TV", "47"),
        Genre("Music", "55"),
        Genre("Mystery", "23"),
        Genre("Mythology", "21"),
        Genre("Personal", "48"),
        Genre("Political", "42"),
        Genre("Post-Apocalyptic", "43"),
        Genre("Psychological", "27"),
        Genre("Pulp", "39"),
        Genre("Religious", "53"),
        Genre("Robots", "9"),
        Genre("Romance", "32"),
        Genre("School Life", "52"),
        Genre("Sci-Fi", "16"),
        Genre("Slice of Life", "50"),
        Genre("Sport", "54"),
        Genre("Spy", "30"),
        Genre("Superhero", "22"),
        Genre("Supernatural", "24"),
        Genre("Suspense", "29"),
        Genre("Thriller", "18"),
        Genre("Vampires", "34"),
        Genre("Video Games", "37"),
        Genre("War", "26"),
        Genre("Western", "45"),
        Genre("Zombies", "36"),
    )
    // Preferences Code

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val qualitypref = androidx.preference.ListPreference(screen.context).apply {
            key = QUALITY_PREF_TITLE
            title = QUALITY_PREF_TITLE
            entries = arrayOf("High Quality", "Low Quality")
            entryValues = arrayOf("hq", "lq")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(QUALITY_PREF, entry).commit()
            }
        }
        screen.addPreference(qualitypref)
        val serverpref = androidx.preference.ListPreference(screen.context).apply {
            key = SERVER_PREF_TITLE
            title = SERVER_PREF_TITLE
            entries = arrayOf("Server 1", "Server 2")
            entryValues = arrayOf("", "s2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(SERVER_PREF, entry).commit()
            }
        }
        screen.addPreference(serverpref)
    }

    private fun qualitypref() = preferences.getString(QUALITY_PREF, "hq")

    private fun serverpref() = preferences.getString(SERVER_PREF, "")

    private var rguardUrl: String? = null

    private val rguardBytecode: ByteArray by lazy {
        val cacheDays = if (rguardUrl == null) 1 else 7
        val cacheControl = CacheControl.Builder()
            .maxAge(cacheDays, TimeUnit.DAYS)
            .build()

        val scriptUrl = rguardUrl ?: "$baseUrl/Scripts/rguard.min.js"
        val scriptRequest = GET(scriptUrl, headers, cache = cacheControl)
        val scriptResponse = client.newCall(scriptRequest).execute()
        val scriptBody = scriptResponse.body.string()

        QuickJs.create().use {
            it.compile(FUNNY_JS_SCRIPT + scriptBody + ATOB_SCRIPT, "?")
        }
    }

    // Adapted from https://www.removecomments.com
    private fun removeComments(input: String): String {
        val regx = "\\s".toRegex()
        var full = input
        var finalText = ""
        val comment = "//"
        val bcOpen = "/*"
        val bcClose = "*/"
        val bcOpenIndexes = mutableListOf<Int>()
        val bcCloseIndexes = mutableListOf<Int>()
        var o = -1
        var c = -1
        if (bcOpen.isNotEmpty()) {
            o = full.indexOf(bcOpen)
            while (o != -1) {
                bcOpenIndexes.add(o)
                o = full.indexOf(bcOpen, o + 1)
            }
        }
        if (bcClose.isNotEmpty()) {
            c = full.indexOf(bcClose)
            while (c != -1) {
                bcCloseIndexes.add(c)
                c = full.indexOf(bcClose, c + 1)
            }
        }
        var d = 0
        var s = 0
        var bc = 0
        var record = 0
        for (i in full.indices) {
            if (full[i] == '"' && d == 0 && s == 0) {
                d++
            } else if (full[i] == '"' && d == 1 && s == 0) {
                d--
            }
            if (full[i] == '\'' && d == 0 && s == 0) {
                if (!bcOpenIndexes.contains(i)) {
                    s++
                }
            } else if (full[i] == '\'' && d == 0 && s == 1) {
                if (!bcCloseIndexes.contains(i)) {
                    s--
                }
            } else if (full[i] == '\n') {
                d = 0
                s = 0
            }
            if (bcOpenIndexes.contains(i) && d == 0 && s == 0 && bc == 0) {
                finalText += full.substring(record, i)
                bc = 1
            } else if (bcClose.isNotEmpty() && bcCloseIndexes.contains(i) && bc == 1) {
                record = i + bcClose.length
                bc = 0
            } else if (i == full.length - 1 && bc == 0) {
                finalText += full.substring(record)
            }
        }
        if (comment.isNotEmpty()) {
            val lines = finalText.split('\n')
            val remComArr = mutableListOf<String>()
            lines.forEach { line ->
                var rem = line
                if (line.contains(comment)) {
                    val comIndexes = mutableListOf<Int>()
                    var a = -1
                    a = line.indexOf(comment)
                    while (a != -1) {
                        comIndexes.add(a)
                        a = line.indexOf(comment, a + 1)
                    }
                    var d = 0
                    var s = 0
                    for (i in line.indices) {
                        if (line[i] == '"' && d == 0 && s == 0) {
                            d++
                        } else if (line[i] == '"' && d == 1 && s == 0) {
                            d--
                        }
                        if (line[i] == '\'' && d == 0 && s == 0) {
                            s++
                        } else if (line[i] == '\'' && d == 0 && s == 1) {
                            s--
                        }
                        if (comIndexes.contains(i) && d == 0 && s == 0) {
                            rem = line.substring(0, i)
                            break
                        }
                    }
                }
                if (rem.replace(regx, "").isEmpty()) {
                    rem = "\n"
                }
                remComArr.add(rem)
            }
            finalText = remComArr.joinToString("\n")
        }
        while (finalText.contains("\n\n\n")) {
            finalText = finalText.replace("\n\n\n", "\n\n")
        }
        while (finalText.startsWith("\n")) {
            finalText = finalText.substring(1)
        }
        return finalText
    }

    companion object {
        private const val QUALITY_PREF_TITLE = "Image Quality Selector"
        private const val QUALITY_PREF = "qualitypref"
        private const val SERVER_PREF_TITLE = "Server Preference"
        private const val SERVER_PREF = "serverpref"

        private val ARRAY_VAR = Regex("""(\w+)\s*\.\s*push\s*\(""")
        private val FUNNY_JS_SCRIPT = """(function(_0x55d059,_0x2a4b53){const _0x46f9ed=_0x2c15,_0x5fbbf3=_0x55d059();while(!![]){try{const _0x250626=-parseInt(_0x46f9ed(0x1c9))/0x1+-parseInt(_0x46f9ed(0x1cb))/0x2+parseInt(_0x46f9ed(0x1c1))/0x3+parseInt(_0x46f9ed(0x1bf))/0x4*(-parseInt(_0x46f9ed(0x1c0))/0x5)+parseInt(_0x46f9ed(0x1c5))/0x6*(-parseInt(_0x46f9ed(0x1c7))/0x7)+parseInt(_0x46f9ed(0x1c6))/0x8*(parseInt(_0x46f9ed(0x1be))/0x9)+-parseInt(_0x46f9ed(0x1cc))/0xa*(-parseInt(_0x46f9ed(0x1c8))/0xb);if(_0x250626===_0x2a4b53)break;else _0x5fbbf3['push'](_0x5fbbf3['shift']());}catch(_0x4a075f){_0x5fbbf3['push'](_0x5fbbf3['shift']());}}}(_0x1a8f,0xcadac));function _0x1a8f(){const _0x2ff1a8=['GwhMz','813232hXddIs','6011150dGcjKz','7851879KPoNdN','12uSzWoa','1917785LUgHvu','4699299IvmJAE','eeMSA','lHqYE','href','54wYnYWU','8jCuDDH','430220FnRNoq','22mbOOqO','699775vAmAff'];_0x1a8f=function(){return _0x2ff1a8;};return _0x1a8f();}function _0x2c15(_0x241167,_0x106b3f){const _0x1a8f75=_0x1a8f();return _0x2c15=function(_0x2c15f8,_0x3d6d3c){_0x2c15f8=_0x2c15f8-0x1be;let _0x4aab42=_0x1a8f75[_0x2c15f8];return _0x4aab42;},_0x2c15(_0x241167,_0x106b3f);}const handler={'get':function(_0x463020,_0x343d72){const _0x101204=_0x2c15;if(_0x343d72==_0x101204(0x1c4))return _0x101204(0x1c2)!==_0x101204(0x1c3)?'':new _0xaf0d53(()=>{},_0x122164);if(_0x343d72 in _0x463020)return _0x101204(0x1ca)==='dQvmR'?_0x4db6a4[_0x4cee20]:_0x463020[_0x343d72];return new Proxy(()=>{},handler);},'apply':function(_0x391c1e,_0x1ae889,_0x2daceb){return new Proxy(()=>{},handler);},'set':function(_0x49ec09,_0x4cac39,_0x48e10f){return _0x49ec09[_0x4cac39]=_0x48e10f,!![];},'construct':function(_0x41a3c1,_0x67ea52){return new Proxy(()=>{},handler);}};document=new Proxy({},handler),window=new Proxy({},handler),console=new Proxy({},handler),${'$'}=new Proxy(function(){},handler),location=new Proxy({},handler);        """.trimIndent()

        /*
         * The MIT License (MIT)
         * Copyright (c) 2014 MaxArt2501
         */
        private val ATOB_SCRIPT = """
            var b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=",
                b64re = /^(?:[A-Za-z\d+\/]{4})*?(?:[A-Za-z\d+\/]{2}(?:==)?|[A-Za-z\d+\/]{3}=?)?$/;

            atob = function(string) {
                // atob can work with strings with whitespaces, even inside the encoded part,
                // but only \t, \n, \f, \r and ' ', which can be stripped.
                string = String(string).replace(/[\t\n\f\r ]+/g, "");
                if (!b64re.test(string))
                    throw new TypeError("Failed to execute 'atob' on 'Window': The string to be decoded is not correctly encoded.");

                // Adding the padding if missing, for semplicity
                string += "==".slice(2 - (string.length & 3));
                var bitmap, result = "", r1, r2, i = 0;
                for (; i < string.length;) {
                    bitmap = b64.indexOf(string.charAt(i++)) << 18 | b64.indexOf(string.charAt(i++)) << 12
                            | (r1 = b64.indexOf(string.charAt(i++))) << 6 | (r2 = b64.indexOf(string.charAt(i++)));

                    result += r1 === 64 ? String.fromCharCode(bitmap >> 16 & 255)
                            : r2 === 64 ? String.fromCharCode(bitmap >> 16 & 255, bitmap >> 8 & 255)
                            : String.fromCharCode(bitmap >> 16 & 255, bitmap >> 8 & 255, bitmap & 255);
                }
                return result;
            };
        """.trimIndent()
    }
}
