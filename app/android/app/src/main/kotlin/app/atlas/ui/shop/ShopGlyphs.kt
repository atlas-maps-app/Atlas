package app.atlas.ui.shop

/** The boards' small symbol for each kind of shop: what a list line, a card or a stop shows beside its name. */
internal val ShopKind.glyph: String
    get() = when (this) {
        ShopKind.SUPERMARKET, ShopKind.CONVENIENCE -> "grocery.supermarket"
        ShopKind.GREENGROCER, ShopKind.HEALTH_FOOD -> "grocery.produce"
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
        ShopKind.NEWSAGENT -> "place.books"
        ShopKind.PET, ShopKind.VARIETY -> "place.shop"
        ShopKind.HARDWARE -> "place.tools"
    }
