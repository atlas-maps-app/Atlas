# Licences of what the shop package bundles and uses

| What | Where | Licence |
|---|---|---|
| Overpass (variable font, Delve Withrow, Dave Bailey, Thomas Jockin; the highway-sign face), from github.com/google/fonts `ofl/overpass` | `app/src/main/res/font/shop_overpass.ttf` | SIL Open Font License 1.1 |
| Caveat, Courier Prime, Special Elite, Tilt Neon (the handwriting, receipts, typewriter and neon) | bundled by the eat package, `app/src/main/res/font/eat_*.ttf` | SIL Open Font License 1.1 (see `ui/eat/LICENSES.md`) |
| Open Prices: the prices around you for the basket compare (https://prices.openfoodfacts.org), asked at run time, no key | `OpenPrices.kt` | Open Database Licence (ODbL), © Open Food Facts contributors |
| An Overpass extract of the shops, errands, water points, toilets and bins within 1.6 km of the Westerkerk, fetched 15 October 2026, used only by the tests | `app/src/test/resources/shop/westerkerk_shops.json` | ODbL, © OpenStreetMap contributors |
| A page of Open Prices answers recorded on 15 October 2026, used only by the tests | `app/src/test/resources/shop/open_prices_amsterdam.json` | ODbL, © Open Food Facts contributors |

The shops, their opening hours, the bins and what they take, the water points and toilets are OpenStreetMap data
(© OpenStreetMap contributors, ODbL); ratings come from Mangrove (CC BY 4.0). The drawings are the boards' own, taken
from their SVG: the symbols through `:places`, and the objects it does not hold in `ShopBoards.kt` (generated from the
boards, see the note at the top of that file). The prices in `FakePrices` (tests only) are made up for layout checks and
never shown in the app.
