package app.atlas.ui.eat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What a card's plate holds: one dish per cuisine family, noodles or sushi for the Japanese planks, nothing for a kitchen nobody mapped. */
enum class Dish { PASTA, PIZZA, SUSHI, RAMEN, DUMPLINGS, CURRY, RICE_BOWL, MEZZE, INJERA, BURGER, FRIES, PANCAKE, STEAK, SALAD, EMPTY }

/** The dish a place's plate shows, from its first telling cuisine. */
fun dishOf(p: EatPlace, country: String?): Dish {
    val cs = p.cuisines
    fun any(vararg v: String) = cs.any { it in v }
    return when {
        any("pizza") -> Dish.PIZZA
        any("sushi", "omakase") -> Dish.SUSHI
        any("ramen", "noodle", "udon", "soba", "pho", "vietnamese") -> Dish.RAMEN
        any("dumpling", "dim_sum", "gyoza") -> Dish.DUMPLINGS
        any("burger", "american", "diner") -> Dish.BURGER
        any("friture", "fries", "fish_and_chips", "belgian") -> Dish.FRIES
        any("pancake", "crepe", "dutch") -> Dish.PANCAKE
        any("steak_house", "argentinian", "barbecue", "grill", "brazilian") -> Dish.STEAK
        any("salad", "vegan", "vegetarian", "healthy", "poke") -> Dish.SALAD
        else -> when (CuisineFamily.of(CarteModel.lineOf(p, country), country)) {
            CuisineFamily.ITALY -> Dish.PASTA
            CuisineFamily.JAPAN -> Dish.SUSHI
            CuisineFamily.CHINA_KOREA -> Dish.DUMPLINGS
            CuisineFamily.SOUTHEAST_ASIA -> Dish.RICE_BOWL
            CuisineFamily.INDIA -> Dish.CURRY
            CuisineFamily.MIDDLE_EAST -> Dish.MEZZE
            CuisineFamily.AFRICA -> Dish.INJERA
            CuisineFamily.AMERICAS -> Dish.BURGER
            CuisineFamily.EUROPE -> Dish.STEAK
            CuisineFamily.MORE -> Dish.EMPTY
        }
    }
}

/** True for dishes eaten with chopsticks. */
private val Dish.chopsticks: Boolean get() = this == Dish.SUSHI || this == Dish.RAMEN || this == Dish.DUMPLINGS || this == Dish.RICE_BOWL

/**
 * A top-down plate of [dish] on a wooden table, filling the canvas: the Eat board's card picture (300 × 116) with its
 * planks, grain, shadow and plate, and its cutlery. Ramen comes in the board's red bowl on dark slate.
 */
fun DrawScope.drawPlate(dish: Dish, seed: Int) {
    val k = size.width / 300f
    if (dish == Dish.RAMEN) {
        drawRamenBowl(k)
        return
    }
    drawRect(Color(0xFFB97A48))
    for (y in listOf(18f, 41f, 67f, 94f)) drawLine(Color(0xFFA86B3C), Offset(0f, y * k), Offset(size.width, y * k), 2 * k, alpha = 0.6f)
    val grain = Path().apply {
        moveTo(0f, 30 * k); cubicTo(80 * k, 24 * k, 160 * k, 36 * k, 300 * k, 30 * k)
        moveTo(0f, 80 * k); cubicTo(90 * k, 85 * k, 200 * k, 74 * k, 300 * k, 82 * k)
    }
    drawPath(grain, Color(0xFFC88A57), style = Stroke(1.5f * k), alpha = 0.7f)
    val c = Offset(150 * k, 58 * k)
    drawOval(Color.Black.copy(alpha = 0.18f), Offset(80 * k, 12 * k), Size(140 * k, 100 * k))
    drawCircle(Color(0xFFF7F4EE), 52 * k, c)
    drawCircle(Color(0xFFE4DDD0), 52 * k, c, style = Stroke(3 * k))
    drawCircle(Color(0xFFEFE9DE), 37 * k, c)
    if (dish == Dish.PASTA) drawBoardPasta(k) else drawDish(dish, c, 35 * k, Random(seed))
    // The cutlery to the right, as the board lays it.
    drawLine(Color(0xFFC9CCD2), Offset(232 * k, 20 * k), Offset(240 * k, 90 * k), 3 * k, StrokeCap.Round)
    drawLine(Color(0xFFC9CCD2), Offset(238 * k, 20 * k), Offset(246 * k, 90 * k), 3 * k, StrokeCap.Round)
}

/** The board's pasta: a golden nest of tagliatelle, basil, and a sprinkle of cheese. */
private fun DrawScope.drawBoardPasta(k: Float) {
    fun p(block: Path.() -> Unit) = Path().apply(block)
    val nest = p {
        moveTo(126 * k, 58 * k); cubicTo(132 * k, 42 * k, 148 * k, 40 * k, 156 * k, 50 * k)
        cubicTo(164 * k, 60 * k, 152 * k, 72 * k, 142 * k, 66 * k)
        cubicTo(132 * k, 60 * k, 144 * k, 48 * k, 156 * k, 54 * k)
        cubicTo(168 * k, 60 * k, 166 * k, 70 * k, 154 * k, 74 * k)
        moveTo(130 * k, 46 * k); cubicTo(140 * k, 40 * k, 154 * k, 42 * k, 160 * k, 52 * k)
        moveTo(122 * k, 64 * k); cubicTo(132 * k, 76 * k, 150 * k, 78 * k, 160 * k, 66 * k)
        moveTo(136 * k, 72 * k); cubicTo(148 * k, 76 * k, 160 * k, 70 * k, 164 * k, 62 * k)
    }
    drawPath(nest, Color(0xFFEAC15C), style = Stroke(3.2f * k, cap = StrokeCap.Round))
    val shade = p {
        moveTo(128 * k, 52 * k); cubicTo(136 * k, 44 * k, 148 * k, 44 * k, 156 * k, 52 * k)
        moveTo(140 * k, 70 * k); cubicTo(148 * k, 72 * k, 156 * k, 70 * k, 162 * k, 64 * k)
    }
    drawPath(shade, Color(0xFFD9A83E), style = Stroke(2 * k, cap = StrokeCap.Round))
    drawPath(p { moveTo(162 * k, 40 * k); cubicTo(168 * k, 38 * k, 173 * k, 41 * k, 174 * k, 46 * k); cubicTo(168 * k, 48 * k, 163 * k, 45 * k, 162 * k, 40 * k); close() }, Color(0xFF3E8A3A))
    drawPath(p { moveTo(150 * k, 36 * k); cubicTo(153 * k, 31 * k, 159 * k, 31 * k, 162 * k, 35 * k); cubicTo(158 * k, 39 * k, 153 * k, 39 * k, 150 * k, 36 * k); close() }, Color(0xFF56A04C))
    drawCircle(Color.White, 1.4f * k, Offset(140 * k, 60 * k))
    drawCircle(Color.White, 1.2f * k, Offset(158 * k, 64 * k))
    drawCircle(Color.White, 1.2f * k, Offset(148 * k, 52 * k))
}

/** The board's ramen: a red bowl of broth, noodles, egg, nori and scallion on dark slate. */
private fun DrawScope.drawRamenBowl(k: Float) {
    drawRect(Color(0xFF2C2F36))
    for ((a, b) in listOf(Offset(0f, 0f) to Offset(300f, 116f), Offset(60f, 0f) to Offset(300f, 92f), Offset(0f, 30f) to Offset(220f, 116f))) {
        drawLine(Color(0xFF34373F), a * k, b * k, 10 * k)
    }
    drawOval(Color.Black.copy(alpha = 0.3f), Offset(84 * k, 18 * k), Size(132 * k, 92 * k))
    val c = Offset(150 * k, 58 * k)
    drawCircle(Color(0xFFB8322A), 52 * k, c)
    drawCircle(Color(0xFFE0A45E), 44 * k, c)
    for (y in listOf(50f, 62f)) {
        val wave = Path().apply {
            moveTo(118 * k - (if (y == 62f) 2 * k else 0f), y * k)
            var x = 118f - (if (y == 62f) 2f else 0f)
            repeat(2) {
                cubicTo((x + 10) * k, (y + 6) * k, (x + 20) * k, (y - 6) * k, (x + 30) * k, y * k)
                x += 30f
            }
        }
        drawPath(wave, Color(0xFFF4DE9A), style = Stroke(3 * k, cap = StrokeCap.Round))
    }
    val last = Path().apply {
        moveTo(122 * k, 74 * k)
        cubicTo(132 * k, 80 * k, 142 * k, 68 * k, 152 * k, 74 * k)
        cubicTo(162 * k, 80 * k, 168 * k, 70 * k, 174 * k, 74 * k)
    }
    drawPath(last, Color(0xFFF4DE9A), style = Stroke(3 * k, cap = StrokeCap.Round))
    drawOval(Color(0xFFFBF6E9), Offset(159 * k, 35 * k), Size(22 * k, 18 * k))
    drawCircle(Color(0xFFF2A91E), 5 * k, Offset(170 * k, 44 * k))
    rotate(-12f, Offset(124 * k, 43 * k)) {
        drawRoundRect(Color(0xFF1F2A22), Offset(116 * k, 30 * k), Size(16 * k, 26 * k), androidx.compose.ui.geometry.CornerRadius(2 * k))
    }
    drawCircle(Color(0xFF6BB04A), 3 * k, Offset(140 * k, 84 * k))
    drawCircle(Color(0xFF6BB04A), 2.5f * k, Offset(147 * k, 80 * k))
    drawCircle(Color(0xFF6BB04A), 2.5f * k, Offset(134 * k, 79 * k))
}

private fun DrawScope.drawDish(dish: Dish, c: Offset, r: Float, rnd: Random) {
    val d = density
    when (dish) {
        Dish.PASTA -> {
            for (i in 0 until 7) {
                val p = Path()
                val turns = 2.2f
                var first = true
                var t = 0f
                while (t < turns * 6.283f) {
                    val rr = r * (0.18f + 0.11f * t / 6.283f) * (0.9f + 0.2f * ((i * 37) % 5) / 5f)
                    val pt = c + Offset(cos(t + i) * rr * 1.05f, sin(t + i) * rr * 0.92f)
                    if (first) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                    first = false
                    t += 0.25f
                }
                drawPath(p, Color(0xFFE8C46A), style = Stroke(2.2f * d, cap = StrokeCap.Round))
            }
            drawCircle(Color(0xFFC0392B), r * 0.1f, c + Offset(r * 0.05f, -r * 0.05f))
            leaf(c + Offset(r * 0.3f, -r * 0.38f), r * 0.2f, Color(0xFF4F9A3E), 30f)
        }
        Dish.PIZZA -> {
            drawCircle(Color(0xFFE2B26A), r * 0.95f, c)
            drawCircle(Color(0xFFD2452E), r * 0.8f, c)
            repeat(7) {
                val a = rnd.nextFloat() * 6.283f
                val rr = rnd.nextFloat() * r * 0.6f
                drawCircle(Color(0xFFF6EBC8), r * 0.12f, c + Offset(cos(a) * rr, sin(a) * rr))
            }
            repeat(4) { leaf(c + Offset((rnd.nextFloat() - 0.5f) * r, (rnd.nextFloat() - 0.5f) * r), r * 0.12f, Color(0xFF3F8E36), rnd.nextFloat() * 180f) }
            for (i in 0 until 3) drawLine(Color(0x55A0522D), c, c + Offset(cos(i * 2.094f) * r * 0.95f, sin(i * 2.094f) * r * 0.95f), 1.2f * d)
        }
        Dish.SUSHI -> {
            for (i in 0 until 4) {
                val o = c + Offset((i - 1.5f) * r * 0.48f, if (i % 2 == 0) -r * 0.12f else r * 0.14f)
                rotate(-18f, o) {
                    drawRoundRect(Color(0xFFF8F6F0), Offset(o.x - r * 0.22f, o.y - r * 0.13f), Size(r * 0.44f, r * 0.26f), androidx.compose.ui.geometry.CornerRadius(r * 0.1f))
                    drawRoundRect(if (i % 2 == 0) Color(0xFFF08A5D) else Color(0xFFE8574A), Offset(o.x - r * 0.24f, o.y - r * 0.16f), Size(r * 0.48f, r * 0.17f), androidx.compose.ui.geometry.CornerRadius(r * 0.08f))
                    for (k in 0 until 3) drawLine(Color(0x66FFFFFF), Offset(o.x - r * 0.12f + k * r * 0.1f, o.y - r * 0.15f), Offset(o.x - r * 0.16f + k * r * 0.1f, o.y - r * 0.01f), 1f * d)
                }
            }
            drawCircle(Color(0xFF7FBF5A), r * 0.1f, c + Offset(r * 0.55f, r * 0.55f))
        }
        Dish.RAMEN -> {
            drawCircle(Color(0xFFE8B66B), r * 0.95f, c)
            for (i in 0 until 8) {
                val p = Path().apply {
                    moveTo(c.x - r * 0.7f, c.y - r * 0.3f + i * r * 0.09f)
                    cubicTo(c.x - r * 0.2f, c.y - r * 0.5f + i * r * 0.1f, c.x + r * 0.2f, c.y + i * r * 0.06f, c.x + r * 0.7f, c.y - r * 0.2f + i * r * 0.09f)
                }
                drawPath(p, Color(0xFFF5DC8E), style = Stroke(2.2f * d, cap = StrokeCap.Round))
            }
            drawCircle(Color.White, r * 0.2f, c + Offset(-r * 0.35f, r * 0.35f))
            drawCircle(Color(0xFFF2A93B), r * 0.1f, c + Offset(-r * 0.35f, r * 0.35f))
            drawRect(Color(0xFF2F3A2C), Offset(c.x + r * 0.25f, c.y - r * 0.75f), Size(r * 0.32f, r * 0.5f))
            repeat(8) { drawCircle(Color(0xFF6DB34A), r * 0.05f, c + Offset((rnd.nextFloat() - 0.5f) * r, (rnd.nextFloat() - 0.2f) * r * 0.6f)) }
        }
        Dish.DUMPLINGS -> for (i in 0 until 5) {
            val a = i * 1.2566f
            val o = c + Offset(cos(a) * r * 0.5f, sin(a) * r * 0.5f)
            val p = Path().apply {
                moveTo(o.x - r * 0.26f, o.y + r * 0.05f)
                cubicTo(o.x - r * 0.2f, o.y - r * 0.3f, o.x + r * 0.2f, o.y - r * 0.3f, o.x + r * 0.26f, o.y + r * 0.05f)
                close()
            }
            drawPath(p, Color(0xFFF7EEDC))
            drawPath(p, Color(0x22000000), style = Stroke(1f * d))
            for (k in 0 until 4) drawLine(Color(0x33000000), Offset(o.x - r * 0.12f + k * r * 0.08f, o.y - r * 0.16f), Offset(o.x - r * 0.1f + k * r * 0.08f, o.y - r * 0.08f), 1f * d)
        }
        Dish.CURRY -> {
            drawCircle(Color(0xFFF7F2E3), r * 0.55f, c + Offset(-r * 0.3f, 0f))
            drawCircle(Color(0xFFE07B26), r * 0.55f, c + Offset(r * 0.32f, r * 0.05f))
            repeat(6) { drawCircle(Color(0xFFF2C14E), r * 0.08f, c + Offset(r * 0.32f + (rnd.nextFloat() - 0.5f) * r * 0.6f, (rnd.nextFloat() - 0.5f) * r * 0.6f)) }
            leaf(c + Offset(r * 0.4f, -r * 0.3f), r * 0.15f, Color(0xFF3F8E36), 10f)
        }
        Dish.RICE_BOWL -> {
            drawCircle(Color(0xFFF8F5EC), r * 0.9f, c)
            drawArc(Color(0xFF6DAA45), 200f, 120f, true, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f))
            drawArc(Color(0xFFE9573F), 330f, 70f, true, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f))
            drawArc(Color(0xFFF2C14E), 50f, 80f, true, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f))
            drawCircle(Color(0xFFF0EBDD), r * 0.35f, c)
        }
        Dish.MEZZE -> {
            drawCircle(Color(0xFFE9CF9E), r * 0.6f, c + Offset(-r * 0.25f, -r * 0.15f))
            drawArc(Color(0xFFC79A55), 0f, 300f, false, Offset(c.x - r * 0.6f, c.y - r * 0.5f), Size(r * 0.7f, r * 0.7f), style = Stroke(2f * d))
            drawCircle(Color(0xFFB5452E), r * 0.08f, c + Offset(-r * 0.25f, -r * 0.15f))
            for (i in 0 until 3) drawCircle(Color(0xFF8A5A2B), r * 0.17f, c + Offset(r * 0.45f, -r * 0.35f + i * r * 0.36f))
            leaf(c + Offset(-r * 0.3f, r * 0.55f), r * 0.2f, Color(0xFF4F9A3E), 60f)
        }
        Dish.INJERA -> {
            drawCircle(Color(0xFFD9C09A), r * 0.95f, c)
            repeat(60) { drawCircle(Color(0x33000000), r * 0.015f, c + Offset((rnd.nextFloat() - 0.5f) * r * 1.6f, (rnd.nextFloat() - 0.5f) * r * 1.6f)) }
            drawCircle(Color(0xFF9C3B1E), r * 0.25f, c + Offset(-r * 0.3f, -r * 0.2f))
            drawCircle(Color(0xFFE2A93B), r * 0.22f, c + Offset(r * 0.3f, -r * 0.1f))
            drawCircle(Color(0xFF5E8C3A), r * 0.22f, c + Offset(0f, r * 0.35f))
        }
        Dish.BURGER -> {
            drawCircle(Color(0xFF5B3A1E), r * 0.66f, c + Offset(0f, r * 0.04f))
            drawCircle(Color(0xFF6FAE45), r * 0.7f, c, style = Stroke(r * 0.06f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFE8B064), Color(0xFFC8823A)), c - Offset(r * 0.15f, r * 0.15f), r * 0.7f), r * 0.6f, c)
            repeat(12) { drawCircle(Color(0xFFF6EBD0), r * 0.025f, c + Offset((rnd.nextFloat() - 0.5f) * r * 0.8f, (rnd.nextFloat() - 0.5f) * r * 0.8f)) }
        }
        Dish.FRIES -> {
            for (i in 0 until 14) {
                val a = rnd.nextFloat() * 180f
                val o = c + Offset((rnd.nextFloat() - 0.5f) * r * 1.1f, (rnd.nextFloat() - 0.5f) * r * 1.1f)
                rotate(a, o) { drawRect(Color(0xFFF2C25A), Offset(o.x - r * 0.3f, o.y - r * 0.04f), Size(r * 0.6f, r * 0.09f)) }
            }
            drawCircle(Color(0xFFF7F3E6), r * 0.2f, c + Offset(r * 0.45f, r * 0.45f))
        }
        Dish.PANCAKE -> {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFEBC27A), Color(0xFFD69A4E)), c, r * 0.95f), r * 0.92f, c)
            repeat(14) { drawCircle(Color(0x33803F10), r * (0.04f + rnd.nextFloat() * 0.05f), c + Offset((rnd.nextFloat() - 0.5f) * r * 1.3f, (rnd.nextFloat() - 0.5f) * r * 1.3f)) }
            repeat(18) { drawCircle(Color.White, r * 0.02f, c + Offset((rnd.nextFloat() - 0.5f) * r * 1.2f, (rnd.nextFloat() - 0.5f) * r * 1.2f)) }
        }
        Dish.STEAK -> {
            val p = Path().apply {
                moveTo(c.x - r * 0.6f, c.y - r * 0.1f)
                cubicTo(c.x - r * 0.5f, c.y - r * 0.6f, c.x + r * 0.4f, c.y - r * 0.55f, c.x + r * 0.45f, c.y - r * 0.05f)
                cubicTo(c.x + r * 0.5f, c.y + r * 0.35f, c.x - r * 0.55f, c.y + r * 0.4f, c.x - r * 0.6f, c.y - r * 0.1f)
            }
            drawPath(p, Color(0xFF7A3B22))
            for (k in 0 until 4) drawLine(Color(0x55301008), Offset(c.x - r * 0.4f + k * r * 0.22f, c.y - r * 0.35f), Offset(c.x - r * 0.55f + k * r * 0.22f, c.y + r * 0.2f), 2f * d)
            for (i in 0 until 6) {
                val o = c + Offset(r * (0.1f + i * 0.08f), r * 0.55f)
                rotate(70f + i * 8f, o) { drawRect(Color(0xFFF2C25A), Offset(o.x - r * 0.2f, o.y - r * 0.03f), Size(r * 0.4f, r * 0.07f)) }
            }
            leaf(c + Offset(-r * 0.45f, r * 0.45f), r * 0.18f, Color(0xFF4F9A3E), -20f)
        }
        Dish.EMPTY -> Unit
        Dish.SALAD -> repeat(16) {
            val o = c + Offset((rnd.nextFloat() - 0.5f) * r * 1.3f, (rnd.nextFloat() - 0.5f) * r * 1.3f)
            leaf(o, r * (0.15f + rnd.nextFloat() * 0.12f), listOf(Color(0xFF6FAE45), Color(0xFF4F9A3E), Color(0xFF9CCB5A))[it % 3], rnd.nextFloat() * 360f)
            if (it % 5 == 0) drawCircle(Color(0xFFE5483A), r * 0.08f, o)
        }
    }
}

/** A leaf: two arcs meeting at the tips, turned by [degrees]. */
private fun DrawScope.leaf(c: Offset, len: Float, colour: Color, degrees: Float) {
    rotate(degrees, c) {
        val p = Path().apply {
            moveTo(c.x - len, c.y)
            quadraticTo(c.x, c.y - len * 0.6f, c.x + len, c.y)
            quadraticTo(c.x, c.y + len * 0.6f, c.x - len, c.y)
        }
        drawPath(p, colour)
        drawLine(Color(0x33000000), Offset(c.x - len * 0.8f, c.y), Offset(c.x + len * 0.8f, c.y), 0.8f * density)
    }
}
