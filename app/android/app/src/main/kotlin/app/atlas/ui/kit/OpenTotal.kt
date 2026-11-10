package app.atlas.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R

/**
 * A total of open places as shown: those surely open, by hours of their own ("263"); those open only by hours Atlas
 * guessed ([guessed] of [open]) are left to the rows, which mark each. Where none is surely open but some are by
 * guessed hours, "≈7", so a total never says none where some are likely open (the owner, 5 November: "Isnt that too
 * much text everywhere? Should it primarily show whats surely open only?").
 */
fun openTotal(open: Int, guessed: Int): String {
    val sure = open - guessed
    return if (sure <= 0 && guessed > 0) "≈$open" else sure.coerceAtLeast(0).toString()
}

/** A time some places keep ("4:00"), after "≈" when only guessed hours give it. */
fun guessedTime(time: String, guessed: Boolean): String = if (guessed) "≈$time" else time

/** [openTotal] as a screen reader says it: "about 7" for "≈7". */
@Composable
fun openTotalSaid(open: Int, guessed: Int): String =
    openTotal(open, guessed).let { if (it.startsWith("≈")) stringResource(R.string.kit_about, open) else it }
