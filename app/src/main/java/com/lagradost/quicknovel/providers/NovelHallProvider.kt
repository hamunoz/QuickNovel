package com.lagradost.quicknovel.providers

import android.util.Log
import com.lagradost.quicknovel.*
import org.jsoup.Jsoup

class NovelHallProvider : MainAPI() {

    override val name = "NovelHall"
    override val mainUrl = "https://www.novelhall.com"
    override val lang = "en"
    override val hasMainPage = true
  override val iconId = R.drawable.icon_novelhall
    override val hasSearch = true

    // -- MAIN PAGE --
    override suspend fun getMainPage(): HomePageResponse {
        val doc = Jsoup.connect(mainUrl).get()
        val lists = mutableListOf<HomePageList>()

        val latest = doc.select("#main .novel-item").map { li ->
            val a = li.selectFirst(".col-xs-7 a")!!
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            val imgSrc = li.selectFirst(".col-xs-3 img")?.attr("src")
            val cover = if (imgSrc.isNullOrBlank()) {
                // fallback imagen drawable local
                "android.resource://${app.packageName}/${R.drawable.default_cover}"
            } else {
                fixUrlNull(imgSrc)
            }

            HomePageListItem(
                name = title,
                url = link,
                posterUrl = cover
            )
        }

        lists.add(HomePageList("Latest Updates", latest))

        return HomePageResponse(lists, false)
    }

    // -- SEARCH --
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?s=book/search&keyword=$query"
        val doc = Jsoup.connect(url).get()

        return doc.select(".novel-item").map { li ->
            val a = li.selectFirst(".col-xs-7 a")!!
            val title = a.text().trim()
            val link = fixUrl(a.attr("href"))

            val imgSrc = li.selectFirst(".col-xs-3 img")?.attr("src")
            val cover = if (imgSrc.isNullOrBlank()) {
                "android.resource://${app.packageName}/${R.drawable.default_cover}"
            } else {
                fixUrlNull(imgSrc)
            }

            SearchResponse(title, link, cover)
        }
    }

    // -- LOAD NOVEL --
    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.connect(url).get()

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        val imgSrc = doc.selectFirst(".pic img")?.attr("src")

        val cover = if (imgSrc.isNullOrBlank()) {
            "android.resource://${app.packageName}/${R.drawable.default_cover}"
        } else {
            fixUrlNull(imgSrc)
        }

        val author = doc.select(".about .info a[href*=author]").text().ifBlank { "Unknown" }
        val summary = doc.select(".panel-body p").text().trim()

        val chapters = doc.select(".chapter-list li a").map { ch ->
            val chTitle = ch.text().trim()
            val chUrl = fixUrl(ch.attr("href"))
            ChapterData(chTitle, chUrl)
        }.reversed()

        return LoadResponse(
            name = title,
            author = author,
            url = url,
            posterUrl = cover,
            summary = summary,
            chapters = chapters
        )
    }

    // -- LOAD CHAPTER --
    override suspend fun loadChapter(url: String): ChapterData {
        val doc = Jsoup.connect(url).get()
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Chapter"
        val content = doc.select(".panel-body").html()

        return ChapterData(
            name = title,
            url = url,
            text = content
        )
    }
}
