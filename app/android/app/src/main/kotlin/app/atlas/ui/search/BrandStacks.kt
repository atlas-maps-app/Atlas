package app.atlas.ui.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R

/** A stack of chains, and the kind's disc that leads one dealt out: for tests to find. */
internal const val BRAND_STACK_TAG = "brand-stack"
internal const val BRAND_STACK_HEAD_TAG = "brand-stack-head"

/** A stack by its kind's name, the one the category tree gives its places; its first chain's name where it has none. */
internal fun BrandStack.name(lang: String): String = kind?.name(lang) ?: brandName(chains.first().brand)

/**
 * A chain's sign raised off the paper, [size] wide, how many of its branches are near on a tab at its corner, its name
 * under it and, where given, [sub] under that (on the Brands board, its kind); [dimmed] fades a hidden one shown. The
 * search's shelf of brands around, the Brands board and a brand's page (the chains of its kind around) draw their
 * chains so.
 */
@Composable
internal fun ChainSign(
    chain: BrandBranches,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    dimmed: Boolean = false,
) {
    val c = indexColors()
    val name = brandName(chain.brand)
    val said = stringResource(R.string.brands_branches, name, chain.branches.size)
    val corner = cornerOf(size)
    SignColumn(onClick, modifier.alpha(if (dimmed) DIMMED else 1f).semantics(mergeDescendants = true) { contentDescription = said }) {
        Box {
            Box(Modifier.raised(RoundedCornerShape(corner), c, y = 2.dp, blur = 6.dp).edge(c, RoundedCornerShape(corner))) { BrandLogo(chain.brand, size, corner = corner) }
            if (chain.branches.size > 1) Text(
                "${chain.branches.size}",
                Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-6).dp).raised(CircleShape, c, y = 1.dp, blur = 3.dp).padding(horizontal = 6.dp),
                style = boardText(11f, 700, 18f, tnum = true), color = c.ink,
            )
        }
        SignWords(name, sub)
    }
}

/**
 * A kind's chains on one stack, [size] wide, lying as a deck's cards do: the sign of the one with the most branches on
 * top, the next two behind it, each a step up and to the right and rimmed in the card's colour, so that the deck reads
 * even of signs in one colour; [name], the kind's, under it, and [sub] under that. Pressed, it is dealt out
 * ([StackHead]).
 */
@Composable
internal fun ChainStack(
    stack: BrandStack,
    name: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val c = indexColors()
    val said = "$name: " + stack.chains.joinToString { brandName(it.brand) }
    val cards = stack.chains.take(DECK)
    val card = size - DECK_STEP * (DECK - 1)
    val corner = cornerOf(card)
    SignColumn(
        onClick,
        modifier.testTag(BRAND_STACK_TAG).semantics(mergeDescendants = true) { contentDescription = said },
        label = stringResource(R.string.find_brands_deal),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            val step = DECK_STEP * (cards.size - 1)
            Box(Modifier.size(card + step)) {
                // the last first, so that the top one covers the rest
                for (i in cards.indices.reversed()) {
                    Box(
                        Modifier
                            .offset(x = DECK_STEP * i, y = step - DECK_STEP * i)
                            .raised(RoundedCornerShape(corner), c, y = 1.dp, blur = 3.dp)
                            .edge(c, RoundedCornerShape(corner))
                            .padding(CARD_RIM),
                    ) { BrandLogo(cards[i].brand, card - CARD_RIM * 2, corner = corner - CARD_RIM) }
                }
            }
        }
        SignWords(name, sub)
    }
}

/** The head of a stack dealt out: its kind's disc, [size] wide, over [name] (and [sub]); pressed, it stacks it again. */
@Composable
internal fun StackHead(stack: BrandStack, name: String, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    val kind = stack.kind
    SignColumn(onClick, modifier.testTag(BRAND_STACK_HEAD_TAG), label = stringResource(R.string.find_brands_gather)) {
        if (kind != null) KindDisc(kind.symbol, kind.colour, tone = null, size = size) else Box(Modifier.size(size))
        SignWords(name, sub)
    }
}

/** A sign and its words as one button, rounded where it is pressed; [modifier] gives it its width. */
@Composable
internal fun SignColumn(onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = label, role = Role.Button, onClick = onClick).padding(top = 7.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** A sign's name, and what it is under it where [sub] says. */
@Composable
private fun SignWords(name: String, sub: String?) {
    val c = indexColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = SIGN_NAME, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = SIGN_SUB, color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A sign's edge on dark paper, a light hairline: a sign as dark as the paper (the neutral sign of a brand whose colour
 * is not known, or a black one) would melt into it. On light paper its shadow is its edge.
 */
private fun Modifier.edge(c: IndexColors, shape: Shape): Modifier = if (c.dark) border(EDGE, EDGE_ON_DARK, shape) else this

private val EDGE = 1.dp
private val EDGE_ON_DARK = Color.White.copy(alpha = 0.16f)

/** A sign's corner for its side, as a brand's sign has it. */
internal fun cornerOf(size: Dp): Dp = size * 0.29f

/** A sign's name, and the words under it. */
internal val SIGN_NAME = boardText(11.5f, 600, 14f)
private val SIGN_SUB = boardText(10.5f, 450, 13f)

/** A stack's cards: the most it shows, the step between them and each card's rim. */
private const val DECK = 3
private val DECK_STEP = 4.dp
private val CARD_RIM = 2.dp

/** A hidden brand, shown. */
private const val DIMMED = 0.4f
