// build.gradle.kts
version = 1

cloudstream {
    language = "id"
    
    description = "AlQanime - Nonton Anime Subtitle Indonesia"
    authors = listOf("YourName")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=alqanime.net&sz=%size%"
}
