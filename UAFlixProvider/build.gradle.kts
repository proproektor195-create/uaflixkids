version = 28

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"

    description =
        "UAFlix Kids: мультфільми, мультсеріали та вибрані YouTube-канали"

    authors = listOf(
        "proproektor195-create"
    )

    status = 1

    tvTypes = listOf(
        "Cartoon",
        "Movie",
        "TvSeries"
    )

    iconUrl =
        "https://www.google.com/s2/favicons?domain=uafix.net&sz=%size%"
}
