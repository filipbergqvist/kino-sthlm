package se.kinosthlm.app.data.source

/**
 * Lookup from [CinemaSource.id] to the implementation.
 *
 * Adding a cinema chain is one line here plus the new class.
 */
object CinemaSourceRegistry {

  val all: List<CinemaSource> = listOf(
    FilmstadenSource(),
    SkandiaSource(),
    KulturhusetSource(),
    CapitolSource(),
    BioRioSource(),
    TellusSource(),
    FagelBlaSource(),
    KaskadSource(),
    ZitaSource(),
    CinemateketSource(),
  )

  private val byId: Map<String, CinemaSource> = all.associateBy { it.id }

  operator fun get(sourceId: String): CinemaSource? = byId[sourceId]
}
