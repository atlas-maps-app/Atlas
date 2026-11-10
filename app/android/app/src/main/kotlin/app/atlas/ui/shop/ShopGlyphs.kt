package app.atlas.ui.shop

import app.atlas.places.Symbols

/**
 * The boards' small symbol for each kind of shop: what a list line, a card or a stop shows beside its name. Where the
 * boards draw no glyph of the kind's own (health food, the newsagent, the pet shop, the variety store), the kind's symbol
 * from the one symbols table, as its list rows and pins wear it, not another kind's glyph.
 */
internal val ShopKind.glyph: String
    get() = when (this) {
        ShopKind.SUPERMARKET, ShopKind.CONVENIENCE -> "grocery.supermarket"
        ShopKind.GREENGROCER -> "grocery.produce"
        ShopKind.BAKERY -> "food.bread"
        ShopKind.BUTCHER -> "market.butcher"
        ShopKind.SEAFOOD -> "market.fish"
        ShopKind.CHEESE -> "grocery.cheese"
        ShopKind.DELI -> "market.deli"
        ShopKind.WORLD -> "grocery.world-foods"
        ShopKind.FARM -> "market.farm"
        ShopKind.DRINKS -> "market.wine"
        ShopKind.SWEETS -> "sweets.chocolate"
        ShopKind.COFFEE -> "place.cafe"
        ShopKind.FROZEN -> "sweets.frozen-yogurt"
        ShopKind.BULK -> "refill.jar"
        ShopKind.CHEMIST, ShopKind.PHARMACY -> "place.pharmacy"
        ShopKind.FLORIST -> "place.garden"
        ShopKind.HARDWARE -> "place.tools"
        ShopKind.HEALTH_FOOD -> kindSymbol("health_food")
        ShopKind.NEWSAGENT -> kindSymbol("newsagent")
        ShopKind.PET -> kindSymbol("pet")
        ShopKind.VARIETY -> kindSymbol("variety_store")
    }

private fun kindSymbol(shop: String): String = Symbols.symbolFor(mapOf("shop" to shop))
