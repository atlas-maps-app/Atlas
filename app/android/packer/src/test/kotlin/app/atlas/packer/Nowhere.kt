package app.atlas.packer

import app.atlas.places.OpeningHoursContext

/**
 * Opening hours read nowhere in particular: no public holidays, the sun's fixed hours ([OpeningHoursContext.Default]).
 * The tests of the hours themselves, and those whose places keep no holidays on the days they ask about, read them so;
 * the app always passes the context of where a place is (the reviewer, 17 November, finding 7).
 */
internal val Nowhere: OpeningHoursContext = OpeningHoursContext.Default
