"""What the boards use each symbol for, by the catalogue's geometry hash (cache/look-ref/symbols/symbols.json).

NAMES gives a symbol its semantic id: "ui." controls, "tab." the tab bar, "cat." the Map home's categories, "need."
the needs, "fact." place facts, "mode." ways to travel, "nav." guidance, "weather.", "bin." waste, "place." kinds of
place, "food.", "pin." map pins, and so on. Geometries that share a name become <id>, <id>.2, <id>.3 in catalogue
order (the most used first). A symbol without a name here is written as board.<n>. The number after each entry is
its place on the catalogue's contact sheets (page1-3.html.png) when this table was written.

DRAWINGS are maps, charts, gauges showing one reading, textures and instrument layers: not symbols, left out.
SYMBOLS are kept although their size would make them drawings (tall illustrations of one thing).
ALIASES make the older ids (Lucide ui-*, Maki, Temaki, Pinhead) draw the board symbol of the same meaning.
"""

NAMES = {
    '4cd2d4dbb5db': 'ui.back',  # 1
    'e5f815e41c1d': 'ui.moon',  # 2
    'c6cba0781cad': 'mode.walk',  # 3
    'e618b373c8a2': 'ui.check',  # 4
    '343e09606ace': 'ui.close',  # 5
    '8540549d0f36': 'cat.eat',  # 6
    '857a8ad62ebd': 'nav.direction',  # 7
    '4d1c413b12f3': 'ui.chevron-right',  # 8
    '9d493456329c': 'fact.wheelchair',  # 9
    '3f8bb07485cf': 'ui.clock',  # 10
    'fdf9ffcc2cca': 'ui.layers',  # 11
    '2719a8d23a27': 'ui.charge',  # 12
    'c3ac0a6a6a0d': 'fact.new',  # 13
    'e18d709c53cb': 'fact.terrace',  # 14
    '9be87d09692b': 'tab.map',  # 15
    '06e9adf49512': 'ui.chevron-up',  # 16
    '78cd699b37c9': 'weather.rain',  # 22
    'd5545ebd0757': 'ui.crosshair',  # 23
    '1f095017dbd3': 'weather.partly-cloudy',  # 24
    'b6f2b5620225': 'fact.vegan',  # 25
    'dcd76fef4e5f': 'fact.open-until',  # 26
    'cb78fc65ae4a': 'park.bike',  # 29
    '25b306a70acf': 'need.water',  # 30
    '2f5b3185e958': 'fact.vegan',  # 31
    'c834373781b0': 'ui.star',  # 32
    'fecd8f4b35fb': 'ui.calendar',  # 33
    '39454f30e23d': 'ui.mic',  # 34
    'dbbb5f27a572': 'fact.trending',  # 35
    '791f18f87506': 'tab.go',  # 36
    '2438ef157662': 'weather.sun',  # 37
    '1dc9a29bfa21': 'cat.charge',  # 38
    '66d48e902442': 'place.cafe',  # 39
    'acf134174602': 'park.parking',  # 40
    'eaef9a64e0d4': 'weather.cloud',  # 41
    '31460153ef11': 'mode.moto',  # 42
    '806e3db25e33': 'cat.shops',  # 43
    '218306f01811': 'ui.phone',  # 45
    '2caf98c4b82b': 'ui.wrench',  # 46
    '935c188fe599': 'ui.camera',  # 47
    '438f582e2bcc': 'mode.car',  # 48
    'b0647e8b84f0': 'ui.offline',  # 49
    '8151487b25e5': 'fact.wifi-signal',  # 50
    '2a073294b180': 'place.cafe',  # 51
    '1302a2160c29': 'mode.camper',  # 52
    '88dd1021351e': 'mode.boat',  # 53
    '4e34e04ef64e': 'tab.local',  # 55
    'd80d88b21a03': 'place.fire-hydrant',  # 56
    '59838caf3ecc': 'nature.tree',  # 57
    'd5ec6c79568e': 'weather.cloud',  # 58
    'aa013c8ce92d': 'fact.delivery',  # 59
    'fd4c150ac9dd': 'place.fuel',  # 60
    'c87cd0b499f4': 'ui.order',  # 61
    '7a60b70e6a5e': 'fact.second-hand',  # 62
    '6e4816d7f696': 'mode.truck',  # 63
    '843c2c4737ee': 'mode.bus',  # 64
    '479334d9b808': 'ui.route',  # 65
    '59504e07ead1': 'mode.car',  # 66
    '7178fa6b0005': 'ui.search',  # 67
    '6b724124c55c': 'cat.stay',  # 68
    '897a9e95fa57': 'place.park',  # 69
    'a365fde5a943': 'ui.lock',  # 70
    'b3481e193481': 'ui.bell',  # 71
    'b696d8f9e722': 'nature.peak',  # 72
    '56f4fa40d30a': 'fact.cash',  # 73
    'e748bd71192c': 'fact.groups',  # 74
    'd661adb82df0': 'place.drinking-water',  # 75
    'ca6f44e3d1a5': 'fact.dog',  # 76
    'c84bb850c3f3': 'food.hot',  # 77
    'cfb3ee5a6651': 'need.food',  # 78
    '99e703473845': 'zoom.street',  # 79
    '1942dc4902ed': 'zoom.galaxy',  # 80
    '66ba279ad07b': 'ui.pause',  # 81
    '1fb90a78afed': 'ui.play',  # 82
    '7725fafeac2b': 'tab.soon',  # 83
    'ae7e3621cafa': 'tab.mine',  # 84
    '69c090cd2b10': 'cat.fun',  # 85
    '26be5cbf64d9': 'fact.wifi',  # 86
    'f659c170c512': 'mode.cargo-bike',  # 87
    '9d5ac686b5dd': 'mode.scooter',  # 88
    'ca5a8225868c': 'ui.key',  # 89
    'f68a60f54599': 'weather.wind',  # 92
    'a03416088f46': 'ui.volume',  # 93
    'd2cd66cdbbed': 'ui.globe',  # 94
    '25c76b8f1ecb': 'mode.fly',  # 95
    'e7a4d642c768': 'need.water',  # 96
    'e08fe959c25a': 'weather.rain',  # 97
    '6dce01f371a6': 'cat.nature',  # 98
    '97d72c63f2d0': 'mode.bike',  # 99
    'cec9e1995509': 'ui.bookmark',  # 100
    '2d44249f69c6': 'place.bike-dock',  # 101
    'ec0fb5e27558': 'fact.wheelchair',  # 104
    'c3e6b9064289': 'fact.queue',  # 105
    '97003d0eae3a': 'ui.clock',  # 106
    '76fc126a9dda': 'ui.pin',  # 107
    '786ec618ed06': 'place.library',  # 108
    '0dc183a01d29': 'fact.socket',  # 109
    '842b4b14c24b': 'ui.heart',  # 110
    'cd54d33522db': 'place.pharmacy',  # 111
    '7abc601f7df8': 'place.tools',  # 112
    '4d40cbeb117d': 'ui.share',  # 113
    '1ddf98377b2e': 'mode.walk',  # 114
    'c6ead08c3f3c': 'ui.eye',  # 115
    'cf2d2b1bbae0': 'ui.sparkles',  # 116
    '8e2db239acf3': 'ui.plus',  # 117
    '5656d1e9a766': 'ui.refresh',  # 118
    '78527e3445a6': 'ui.search',  # 119
    'de60b455bd4e': 'ui.clock',  # 120
    '41f06eccf1ff': 'need.toilet',  # 121
    '6d4d3acc886b': 'street.taco',  # 122
    '850cb9acf3d2': 'place.museum',  # 123
    '740f7e4ecd91': 'ui.note',  # 124
    'f52e28e8155e': 'zoo.gorilla',  # 125
    '7aa95add7b7a': 'ui.arrow-right',  # 126
    '49cc1a8815f1': 'ui.star',  # 127
    '762be29e0fff': 'ui.list',  # 128
    'e1d1d665453e': 'mode.truck',  # 129
    'f2b847e39f0d': 'sign.danger',  # 130
    'fe2dea38d187': 'fact.tap-water',  # 131
    '78463cfc581d': 'fact.kids',  # 132
    'f9e8b45dd05b': 'place.cinema',  # 133
    '36fac1f5858e': 'food.ramen',  # 134
    '9247e030f052': 'place.books',  # 136
    'f8b440b3b6d5': 'fact.independent',  # 137
    '81449e2dd242': 'ui.layers',  # 138
    '6bb88aca6ade': 'fact.quiet',  # 139
    'fd59cca247c7': 'place.camp',  # 140
    'eefc5f6e197b': 'food.wine',  # 141
    'eb22263125b8': 'mode.van',  # 142
    '5092366ab309': 'ui.volume',  # 143
    'be231030847e': 'ui.gauge',  # 144
    '5dcee887d6a5': 'ui.volume',  # 145
    '0708f5f64d14': 'nav.arrow',  # 146
    '84bcd40c644b': 'drop.battery',  # 147
    '277424f94db0': 'food.pizza',  # 148
    '090942f8a551': 'food.bar',  # 149
    '8dced808d141': 'mode.fly',  # 150
    'e029115462ad': 'fact.step-free',  # 151
    'e250c1571018': 'place.fridge',  # 153
    '9b6e7d016df2': 'ui.flag',  # 154
    'e4e347ad5f3b': 'sky.satellite',  # 157
    'b235ab101482': 'mode.transit',  # 158
    '715e2fe2b07f': 'mode.hitch',  # 159
    '9850e78de756': 'place.bench',  # 160
    'a5cf82cf1bba': 'fact.shade',  # 161
    'b42eb1e45845': 'ui.speak',  # 162
    '0a997327ab7b': 'ui.lock',  # 163
    'f8edf0e9494f': 'ui.edit',  # 164
    'e21183598cac': 'ui.add-person',  # 165
    '2f3db36ce778': 'weather.sun',  # 166
    '226cedf1e078': 'food.bread',  # 167
    '22639032f275': 'ui.locate',  # 168
    '8c88688ea422': 'mode.walk',  # 169
    'b0d99f5f83c5': 'fact.baby',  # 170
    'ca328af038f9': 'transit.bus',  # 171
    'ee4458c097a4': 'ui.code',  # 172
    '338c80e16bd3': 'fact.card',  # 173
    'a80068a59ded': 'place.picnic',  # 174
    '11af7b2279ae': 'place.playground',  # 175
    '6b70638d23b3': 'place.shop-front',  # 176
    'c6ba2b646f15': 'fact.person',  # 177
    '845a30ed318b': 'fact.container',  # 178
    '68e72b43eaef': 'place.repair',  # 179
    'b00c46d9a339': 'weather.snow',  # 180
    '519a7b286e51': 'place.pharmacy',  # 181
    'debe0527734f': 'weather.rain',  # 182
    '1f7ee888286e': 'trend.up',  # 183
    '04d928c00fc2': 'fact.too-short',  # 184
    '525f0a2124d2': 'place.garden',  # 185
    '2899cc0e5502': 'place.playground',  # 186
    'b7c9dc2ddd64': 'place.gift',  # 187
    '9eb1f2e28ff1': 'pin.cafe',  # 188
    'bfc7445adda4': 'pin.restaurant',  # 189
    '877332283e1a': 'pin.tickets',  # 190
    'c95a9019dc2f': 'fact.pram',  # 191
    '9090e75cccea': 'ui.eye-off',  # 192
    '880c28ae4ae4': 'ui.warning',  # 193
    '94f11a4f1671': 'brand.harbor',  # 194
    '91119ca8d591': 'brand.nordby',  # 195
    '1b77bd14341d': 'brand.greencart',  # 196
    '850197c2d788': 'place.fuel',  # 197
    'b0cfc77e7498': 'ui.query-open',  # 198
    '3ca19fd4f76b': 'nature.spring',  # 199
    '24ae1110ab63': 'ui.eye',  # 200
    'eed66f869ba6': 'fact.language',  # 201
    '0f35cb93fb64': 'place.bike-repair',  # 202
    'b4fd6e8c36a5': 'place.bike-shop',  # 203
    '4c6a595766fb': 'place.games',  # 204
    '543e4e8468f0': 'brand.loop',  # 205
    '7b4cffa0a195': 'ui.hide',  # 206
    'a4b0a3545d31': 'transit.tram',  # 207
    'b72ee98a2fbc': 'ui.no-signal',  # 208
    '9c2b7e75806b': 'weather.sunrise',  # 209
    'c76977880192': 'food.coffee',  # 210
    '209d762bd4a7': 'ui.arrow-right',  # 211
    'd5e8fd24b256': 'fact.power',  # 212
    '6fac5a79d41f': 'nav.turn-left',  # 213
    '2e731caba5d8': 'nav.lane',  # 214
    'bd089932e805': 'plug.ccs2',  # 215
    '4460d56e71da': 'plug.type2',  # 216
    '28362ff2dc86': 'ui.reserve',  # 217
    '52a7757c09c3': 'bin.plastic',  # 218
    'bad7c0691ef2': 'bin.hazardous',  # 219
    'd33018a78b21': 'sign.warning',  # 220
    '66ec04bb66e8': 'ui.smartphone',  # 221
    '2548023d4a56': 'transit.tram',  # 222
    'dfa9fbeab558': 'nav.straight',  # 223
    '31e87fb12bd8': 'ui.speaking',  # 224
    '26838c5eda1f': 'drop.battery',  # 225
    '52e5e9e0bcc0': 'ui.pin',  # 226
    '90a4f5bab400': 'food.burger',  # 227
    '1b8ff2d4674a': 'place.tailor',  # 229
    '4e70d0ae5606': 'fact.dog',  # 230
    'f895fd7a2e24': 'place.playground',  # 231
    '6d0ebb8aef2c': 'fact.open',  # 233
    '3c978d9b23c5': 'fact.drone',  # 234
    '2e6deb5ead67': 'ui.phone',  # 235
    'cd03bee9d282': 'ui.globe',  # 236
    '0f57c3d86a83': 'mode.walk',  # 237
    'ed9b63e8fd6a': 'food.apple',  # 238
    'e66e5c62168f': 'place.market',  # 239
    'e2ac39828777': 'cat.fun',  # 240
    '17699642c5e4': 'nav.straight',  # 241
    'fbd2b7c7723c': 'power.wind',  # 242
    '5be51665e1e8': 'power.solar',  # 243
    'e6a88787c0c9': 'power.nuclear',  # 244
    'ded6fba75a4e': 'power.coal',  # 245
    '75d11d065aa5': 'power.hydro',  # 246
    '218b4c7efb03': 'ui.download',  # 247
    '1598e9ec5db0': 'ui.bell',  # 248
    '62f841104f4a': 'mode.walk',  # 249
    '8a070372770c': 'ui.tag',  # 251
    '5eb2ca7441fb': 'ui.shield-check',  # 252
    '8df36d77052f': 'ui.coins',  # 253
    'af8a15ac2024': 'nature.season',  # 254
    '34825473997b': 'ui.camera',  # 255
    'a226dfb7f8a0': 'ui.compass',  # 256
    '1c1560b197e0': 'ui.3d',  # 257
    '19ffe77e65f0': 'brand.bagels',  # 258
    '158db38ac497': 'cat.groceries',  # 259
    '59424769d7ec': 'cat.water',  # 260
    '508983f9be2f': 'ui.query',  # 261
    '41f8f6e25c3e': 'food.burger',  # 262
    '2d2c727fce58': 'ui.flag',  # 263
    '542ca2331c22': 'place.school',  # 264
    '5656ba0fef10': 'fact.bottle-refill',  # 265
    'c695038c368b': 'ui.trash',  # 266
    '28865f0d8084': 'place.entrance',  # 267
    'f141d6a63b02': 'mode.boat',  # 268
    '61417b451c7b': 'ui.hexagon',  # 269
    '170d806dc7e8': 'ui.web',  # 270
    '338df1f3f20b': 'place.playground',  # 271
    '681469356bed': 'place.fitness',  # 272
    'dabe4fb968e2': 'nav.road',  # 273
    '9d99bc55ed8d': 'fact.cobbles',  # 274
    'b0e863a0318f': 'place.water-play',  # 275
    '4090cc21d145': 'power.bank',  # 276
    '3d825b49e1ab': 'place.car-wash',  # 277
    '49228186ffc5': 'ui.subitem',  # 278
    'b4efa8499887': 'ui.radius',  # 279
    'd865c04eabea': 'mode.bike',  # 280
    '037cda0bdfcb': 'place.sauna',  # 281
    'd9cfe3036e45': 'mode.car',  # 282
    'c4853f5a05a5': 'fact.covered',  # 283
    '6a4d64bd55d1': 'weather.wind',  # 284
    '685b528c9228': 'nav.jam',  # 285
    '3b9a0c1f585e': 'pin.place',  # 286
    'ad39185e98af': 'ui.trending-up',  # 287
    '37cef36f36a3': 'ui.message',  # 288
    '8ee1b766d81a': 'sail.buoy-green',  # 289
    'f36d45accc82': 'ui.no-signal',  # 290
    '318029b97742': 'place.park',  # 291
    '028854a5e517': 'weather.sun',  # 292
    '474646c04f40': 'weather.partly-cloudy',  # 293
    '37d2471002b0': 'place.tap',  # 294
    'b08670b9e279': 'place.pool',  # 295
    '9f0644bbd179': 'place.water-tower',  # 296
    '3ea4a1273f6b': 'trend.down',  # 297
    'cbc54f3bfedc': 'ui.trash',  # 298
    'a368e0dac942': 'place.theatre',  # 299
    'ebf8ee5cab4e': 'place.art',  # 300
    '143054f879b6': 'place.fountain',  # 301
    'fd18a193c0f5': 'place.cafe',  # 302
    'b2bdafdfd5b1': 'food.sweets',  # 303
    'bd9cddab1a16': 'place.bench',  # 304
    'b66009829901': 'place.picnic',  # 305
    '7fff04373324': 'park.bike-covered',  # 306
    '622541982ba6': 'park.bike-garage',  # 307
    '36b6b1bfe937': 'zoo.elephant',  # 308
    '6b2f08209c79': 'zoo.giraffe',  # 309
    'd22acc17dfeb': 'zoo.lion',  # 310
    'ee8a514a5c44': 'zoo.penguin',  # 311
    '81f5499a6897': 'pin.hotel',  # 312
    'f4a58f7de78d': 'pin.shop',  # 313
    'ba15d9da51be': 'fact.walker',  # 314
    '568fe3f1ad0f': 'fact.calm',  # 315
    '3c1059a70c3a': 'place.lift',  # 317
    '95f4cd02a853': 'place.treetop',  # 319
    '24532d44dd66': 'place.climbing',  # 320
    '485760af68a7': 'place.bouldering',  # 321
    '82bc7db22688': 'weather.sun',  # 322
    'f857b31fe292': 'brand.greencart',  # 323
    '28c889a6dabd': 'shop.self-checkout',  # 324
    '607d92987804': 'mode.lorry',  # 327
    '8316337009ff': 'ui.timer',  # 328
    'b3da77efb545': 'ui.return',  # 329
    'ab8038db093c': 'food.bar',  # 331
    '746d39701f8b': 'fact.screen',  # 332
    '4177216d363e': 'bike.pump',  # 333
    '3e98af37bc4b': 'bike.stand',  # 334
    '2979b5e34cbe': 'bike.shop',  # 335
    'adb0482ca753': 'bike.ebike-charge',  # 336
    'ba8806382c3b': 'bike.flat',  # 337
    'dd809924c05b': 'bike.repair-cafe',  # 338
    'eda882c25c4f': 'bike.tubes',  # 339
    '4e9458b77f0f': 'bike.parts',  # 340
    '483f6b741c6d': 'bike.chain-lube',  # 341
    '5033b25c6e3c': 'bike.chain',  # 342
    '221fa536d35b': 'bike.brake-front',  # 344
    'c7c3392abfaf': 'bike.brake-rear',  # 345
    '6d35167955c4': 'bike.tyre',  # 346
    'ec779c94843f': 'bin.trash',  # 347
    '09aff2a4b346': 'bin.recycling',  # 348
    '10e7f665660d': 'bin.plastic',  # 349
    'cd9c20ea4359': 'bin.paper',  # 350
    '80474d726ced': 'bin.organic',  # 351
    '72f3c3d73e69': 'bin.battery',  # 352
    'd7b5af8008c9': 'bin.textile',  # 353
    'c66bca25c85e': 'bin.electronics',  # 354
    '272b10cbcdd3': 'bin.deposit',  # 355
    '9337e769ea2a': 'bin.pizza-box',  # 356
    'e2bcf103cfc1': 'bin.plastic-bag',  # 357
    '7610627979b8': 'bin.takeout-tub',  # 358
    'e24affbb766f': 'place.records',  # 359
    '18df34d3575e': 'place.records',  # 360
    'e6135f8208d9': 'ui.link',  # 361
    '1dcc4ddbd343': 'brand.tessel',  # 362
    'c159f6439c51': 'brand.arbor',  # 363
    'cdba7cfa51a0': 'brand.kite',  # 364
    '9b57b295977e': 'brand.lumen',  # 365
    'db2d677a597f': 'brand.orbit',  # 366
    '593c7078de96': 'brand.sprout',  # 367
    'b43c1bc6e09a': 'brand.parcel',  # 368
    '70c1fc1b7016': 'brand.pick',  # 369
    '53f2c9bfd912': 'brand.fieldhouse',  # 370
    '03cdd0f01b09': 'tab.map',  # 371
    '6e56da3329c8': 'ui.ticket',  # 372
    'aa0ee8f10214': 'cat.shops',  # 373
    '70808d00b795': 'ui.arrow-right',  # 374
    '51eeec1e2c95': 'ui.3d',  # 375
    '82f10fc7469e': 'ui.ear',  # 376
    '99871f1a1614': 'ui.heart',  # 377
    '5e49d11d933c': 'ui.near',  # 378
    'a5ca4c4561b8': 'ui.sliders',  # 379
    '13f25f608048': 'fact.takeaway-cup',  # 382
    'dc5528c847c3': 'place.hut',  # 386
    'da7124eb6c11': 'place.wild-camping',  # 387
    '65cbcb738f5f': 'place.national-park',  # 388
    '8541414691a4': 'place.shower',  # 389
    'e2a3bfab6622': 'sign.low-bridge',  # 391
    '3c20ea5172a3': 'camper.grey-water',  # 392
    'd7ea4ec916ad': 'camper.toilet-dump',  # 393
    '7788568d0ba2': 'camper.overnight',  # 394
    '7a6f40b266cd': 'camper.trailer',  # 395
    '880a62c0423c': 'ui.speaking',  # 396
    'd92bc6b3ef30': 'nav.arrow',  # 397
    '9190e8cfeb9d': 'ui.surprise',  # 398
    'e23eb1c9a37b': 'need.toilet',  # 401
    'c65e1127b6c8': 'ui.appointment',  # 402
    'a3afcce25470': 'bin.paper',  # 403
    '13f5f4b925bf': 'bin.bulky',  # 404
    '084c6ef96e9b': 'brand.greencart',  # 405
    '29c98aac83d2': 'brand.harbor',  # 406
    'ebab5ea9a229': 'flag.nordland',  # 407
    '3fb00b7e5278': 'ui.swap',  # 408
    '4b9fc3422414': 'flag.veloria',  # 409
    '09c952a5a8be': 'food.coffee',  # 411
    'dbe8b3204f19': 'food.beer',  # 412
    '2eb10aefa4d2': 'cat.eat',  # 413
    '9bd5bd5988dd': 'shop.cart',  # 414
    '674590ec6bc9': 'transit.train',  # 415
    'c81d7732262c': 'place.cinema',  # 416
    'a12f735cf9f3': 'place.home',  # 417
    'd165443fd660': 'money.banknote',  # 418
    '9e1001ddc2c6': 'food.breakfast',  # 431
    'be0394d6dfc4': 'ui.charge',  # 433
    '232f5324472c': 'place.arcade',  # 434
    '421734ec1e1b': 'nav.turn-right',  # 435
    '96534f234d0c': 'diy.hardware',  # 436
    '9ab7589d2d06': 'diy.paint',  # 437
    '02adca3bc65c': 'diy.garden',  # 438
    '621f3c4cc788': 'diy.lighting',  # 439
    'f95b9bcbaef6': 'diy.furniture',  # 440
    '479ea10cba8f': 'diy.kitchen',  # 441
    'f6367f7b7494': 'diy.keys',  # 442
    '316fbf6b5439': 'diy.tool-hire',  # 443
    'c4e4159d21d8': 'ui.shield',  # 444
    'f8ac314b91c3': 'ui.osm',  # 445
    'c3984cbaff9c': 'water.filter',  # 446
    '26d1085600a8': 'nav.turn-left',  # 447
    '360f827828ce': 'nav.lane-left',  # 448
    'f8a34787ad20': 'nav.turn-left',  # 449
    'aed3761230e2': 'nav.destination',  # 450
    '958c8e768d6a': 'sign.roadworks',  # 451
    '05ae72b96688': 'ui.bulb',  # 452
    '16ee729ad170': 'ears.headphones',  # 453
    '54f25ad39537': 'ears.around',  # 455
    'a2cce22f8de3': 'ears.beacon',  # 456
    '07bd68d5e981': 'food.ramen',  # 457
    '699d000a14ac': 'food.taco',  # 458
    'e59e1c13d3fb': 'food.sushi',  # 459
    '38dfb5a4a6ef': 'food.bread',  # 460
    '142d2772626a': 'food.coffee',  # 464
    '4fc8da84776b': 'ebike.charge',  # 466
    '9e32a764c9f0': 'ebike.battery-locker',  # 467
    '7f56975ddabd': 'mode.ebike',  # 468
    'e956e914f35c': 'ui.grid',  # 470
    'e76c349f0546': 'place.dry-cleaning',  # 471
    'e089b7996ef1': 'ui.mail',  # 472
    '45317f64db62': 'place.laundry',  # 473
    '081a99d646da': 'place.key-cutting',  # 474
    '9cb659a0cae3': 'place.print',  # 475
    '34d1f49b8b26': 'place.shoe-repair',  # 476
    'd1b1c9ddee55': 'place.zoo',  # 478
    'c285043b3432': 'place.aquarium',  # 479
    '96078d0f873a': 'place.theme-park',  # 480
    '183a8addb894': 'place.farm',  # 481
    '139308e76756': 'place.paddling-pool',  # 482
    'e83a351da6f2': 'place.indoor-play',  # 483
    'd70659b72d00': 'fact.baby',  # 485
    'e79683c2754f': 'place.picnic',  # 486
    'de18a13799da': 'zoo.feeding',  # 487
    '84192ff27aed': 'fashion.women',  # 488
    '0b9bb104213e': 'fashion.men',  # 489
    '48dfc578552c': 'fashion.kids',  # 490
    'a0f9c12fe312': 'fashion.shoes',  # 491
    '048b0b0c86b7': 'fashion.vintage',  # 492
    '53aa096b9592': 'fashion.tailor',  # 493
    'a67afb9feb91': 'roof.gable',  # 496
    '794881299b8e': 'roof.hipped',  # 497
    'f0b0f99c61b0': 'roof.flat',  # 498
    'abb946de06fa': 'place.bbq',  # 500
    'a7a0a5d1ce6e': 'fact.smoking',  # 501
    'c0e078847aea': 'place.fire-station',  # 502
    'cb80f46e283f': 'place.fire-hydrant',  # 503
    '43df5a4bfa09': 'fly.security',  # 507
    '92e4fa0808e0': 'fact.quiet',  # 508
    'b2564dedba2a': 'fact.socket',  # 509
    '0b46dbb2d317': 'sign.warning',  # 511
    'e6dc1bcae495': 'ui.source',  # 512
    '3584d9f42cf2': 'ui.bluetooth',  # 515
    '8a5aefaa40c3': 'fresh.greengrocer',  # 517
    '1c80659e39b3': 'fresh.farm-shop',  # 518
    '8e81bc7d061f': 'fresh.market',  # 519
    '7ac94d48d023': 'fresh.stand',  # 520
    'b536d3ecec1b': 'fresh.orchard',  # 521
    '3b976747f1be': 'food.pear',  # 522
    '5d959c4db00f': 'food.squash',  # 523
    '2c50dc5d6616': 'food.greens',  # 524
    '711bb094b472': 'food.grapes',  # 525
    '3ade15064693': 'fact.honesty-box',  # 526
    '6814850a9ff8': 'fact.farm-direct',  # 527
    'f8913947b435': 'food.greens',  # 528
    '312da6078d56': 'fresh.stand',  # 529
    '1ad916af55ff': 'place.cinema',  # 530
    '114fe0acfc96': 'place.theatre',  # 531
    '0efdfb4ea260': 'ui.film',  # 532
    '342d13f1e541': 'place.festival',  # 533
    'ee11f08423d2': 'place.gaming',  # 534
    '8d8ae908cbb1': 'food.bar',  # 535
    'f0e4d9d4e40b': 'place.club',  # 536
    '6d7c9be6f0f1': 'fact.no-drinking',  # 537
    '842f5df54640': 'ui.activity',  # 538
    '3d14f275a2fb': 'game.found',  # 539
    '17674f8f4437': 'ui.compass',  # 541
    'e3f0a2fa5ea6': 'game.hide-seek',  # 542
    'fbf762908edd': 'game.ruins',  # 543
    '827569a288aa': 'park.family',  # 544
    '4fa21a91515c': 'ui.calendar',  # 545
    '31877157b829': 'fact.locker',  # 548
    'b22be1f2acd6': 'nav.roadworks',  # 549
    'f5c9852af72b': 'ui.arrow-right',  # 550
    '9b5cf1e5c4a1': 'nature.ginkgo',  # 551
    '1e5ac5e2432f': 'power.substation',  # 552
    'f236f37661d0': 'place.town',  # 553
    '7a238546d225': 'guide.emblem',  # 554
    '6b64a0418021': 'fact.cash',  # 555
    '2d9ce6f1f032': 'guide.plug',  # 556
    '961289597dcb': 'guide.drive-right',  # 557
    '545e9e3bd24c': 'guide.tap-water',  # 558
    '13178b9f2079': 'guide.tradition',  # 559
    '108e62317ac4': 'place.assembly-point',  # 563
    'd531f20ef54a': 'place.shelter',  # 564
    '458b8f493a31': 'place.defibrillator',  # 565
    'd1e8bd5fd7bf': 'place.hospital',  # 566
    'bb8e8a9f44e7': 'place.doctor',  # 567
    '54e6ffba84ab': 'place.dentist',  # 568
    '237924ddeac9': 'place.food-bank',  # 570
    'fec5da02aa9e': 'place.food-bank',  # 571
    '137a91a75058': 'place.soup-kitchen',  # 572
    '0d53608a447e': 'ui.heart',  # 573
    '48a16914ab0c': 'place.shop',  # 574
    '710a80e2941a': 'shop.fashion',  # 575
    'dab6920cfa2c': 'shop.shoes',  # 576
    '208d7ded5fe6': 'shop.books',  # 577
    'f26f920575af': 'shop.tech',  # 578
    '57ba6c8c6570': 'shop.diy',  # 579
    'b7e05600aa88': 'shop.florist',  # 580
    'ec3c938e07a4': 'shop.health',  # 581
    '205a2855d5cb': 'shop.sport',  # 582
    '930eb6e0fb7c': 'shop.kids',  # 583
    '3e38d3084b72': 'shop.pets',  # 584
    '76a75ee71e90': 'shop.vintage',  # 585
    '22445d629bc9': 'shop.errands',  # 586
    '64e431805100': 'ui.brands',  # 587
    '2a2e4798bede': 'mode.hitch',  # 588
    'a989e1074826': 'ui.plate',  # 589
    '12dffdb6df30': 'guide.stamp',  # 590
    '1b7c5d2c46a0': 'place.water-suction',  # 612
    '1d6655906918': 'ui.scan',  # 616
    '553a11cd039e': 'kit.slope',  # 619
    'dd754efa04d5': 'kit.bearing',  # 620
    'cd0a8f4c1463': 'kit.sun-moon',  # 621
    '35c8f44ee3f6': 'kit.boil',  # 622
    '6b360cb85fe9': 'kit.whistle',  # 623
    'efd0ce1b1706': 'kit.torch',  # 624
    'cd2f8968417c': 'kit.signal',  # 625
    'bce9717b0875': 'kit.cliff',  # 626
    '7a64f092e0e2': 'kit.solar-tilt',  # 627
    '6311a0eeb7ba': 'late.cat',  # 628
    'c942dca5e7f2': 'grocery.world-foods',  # 632
    '9aec782fa2aa': 'grocery.produce',  # 633
    '97699cacfa14': 'grocery.cheese',  # 634
    '67ee7aba7688': 'grocery.supermarket',  # 635
    '98aa7f07154f': 'weather.sunset',  # 637
    '5deb1c91416e': 'local.moving',  # 638
    '47acb92b9869': 'local.hazards',  # 639
    '69ba337d5fae': 'local.air',  # 640
    'a725f414da43': 'local.grid',  # 641
    '7103aafbf36e': 'local.energy',  # 642
    '82fff22e8fc9': 'local.water',  # 643
    '342465f88c8a': 'local.wifi',  # 644
    '3f1581e401cf': 'local.cell',  # 645
    '143d112d13ed': 'local.gps',  # 646
    '9411ab39a33a': 'local.food-scan',  # 647
    '445c300b73a0': 'local.then',  # 648
    'da37c023f705': 'local.peaks',  # 649
    '7e80b01a2607': 'nav.north',  # 651
    'ad26644575d5': 'ui.arrow-down',  # 652
    '39a647b0720a': 'ui.arrow-up',  # 653
    '39cfc1a2daac': 'place.landmark',  # 654
    '8d10cdb84178': 'transit.tram',  # 655
    '5d79aae8f4e3': 'market.produce',  # 658
    'cc15a4fc6294': 'market.butcher',  # 659
    '74d4bc63c79c': 'market.fish',  # 660
    'b8fec9c73e83': 'market.cheese',  # 661
    '3e261ad35125': 'market.deli',  # 662
    '41608b7bcdb2': 'market.wine',  # 663
    '567f77f18722': 'market.farm',  # 664
    '63f1dd9abe3c': 'ui.receipt',  # 665
    '7fec75bf1ff6': 'food.fries',  # 667
    '1ff5b5f2de31': 'food.taco',  # 668
    'beb3da48ccb4': 'food.dine-in',  # 670
    '24e2d1b2d710': 'food.bread',  # 671
    'b3dd005cfac6': 'place.cafe',  # 673
    '69a8ed3e95e8': 'food.bar',  # 675
    'a2602e1524ff': 'sweets.ice-cream',  # 677
    '5e8e29ccb768': 'ui.surprise',  # 679
    '5edfe170525d': 'ui.passport',  # 684
    'e8f1a9341f6b': 'ui.backtrack',  # 686
    '41c612e6c3e7': 'ui.settings',  # 687
    '71bb50c8bf74': 'ui.watch',  # 688
    '15d84f2ced8d': 'ui.widgets',  # 689
    '39802f5169b6': 'ui.bell',  # 690
    '717133cc8a0c': 'ui.swap',  # 692
    'e98e72ce7704': 'place.bank',  # 693
    '7e10cc7d2b24': 'place.home',  # 695
    '938d2ac901ef': 'place.supermarket',  # 696
    'af99bb5d78cb': 'nature.birds',  # 697
    'd9ed29288396': 'nature.garden',  # 698
    '6ed4a94ab9cc': 'nature.plane-leaf',  # 699
    'fb741b946b12': 'nature.ginkgo-leaf',  # 700
    '51f07eed3287': 'nature.heron',  # 701
    'f43875b6173d': 'nature.flower',  # 702
    '572aac5d7574': 'nature.elm-leaf',  # 703
    '420393d58f13': 'need.water',  # 704
    '953ba814f89e': 'need.toilet',  # 705
    'c2b9d054cba9': 'need.shelter',  # 706
    '61361c565e95': 'need.charge',  # 707
    '4efb69bb11ee': 'need.shower',  # 708
    'dbce2036bb9b': 'need.help',  # 709
    '875f655380e0': 'nav.turn-back',  # 711
    'b5ca45fd7a76': 'ui.map',  # 712
    '67db95a7c5be': 'ui.panel',  # 715
    '0bf74f971403': 'ui.draw-area',  # 717
    '66d32bf62ec0': 'ui.along-route',  # 718
    'e7c4732bca68': 'ui.check-double',  # 719
    'fc731c7918fe': 'ui.check',  # 720
    'c70470e1ec02': 'ui.boost',  # 721
    '33ac552969f3': 'food.serve',  # 722
    'd82bf5bc594b': 'park.along-kerb',  # 723
    '508044de6fde': 'park.angled',  # 724
    'df4e7cd147cc': 'park.head-in',  # 725
    'a0801964a70b': 'park.no-parking',  # 726
    'd02133daed08': 'ui.list',  # 727
    'd13f039a360e': 'ui.scan-barcode',  # 728
    '29af0c69d2c8': 'pantry.supermarket',  # 729
    'a1adbaeafaa6': 'pantry.corner-shop',  # 730
    '5839574f0ce3': 'pantry.produce',  # 731
    '4ec6871dcb0a': 'pantry.bakery',  # 732
    '6f2a952efc86': 'pantry.butcher',  # 733
    '1318b08fdaec': 'pantry.cheese',  # 734
    'f8644c211111': 'pantry.world-foods',  # 735
    '5c3265c00316': 'pantry.bulk',  # 736
    '8eb51b14be17': 'pantry.wine',  # 737
    '4494c4f476e9': 'pantry.eggs',  # 738
    'baca221d2692': 'pantry.late',  # 739
    '6dd150d899d0': 'pantry.community',  # 740
    '4363bad503e4': 'ui.star',  # 741
    '8bbe5c48b50d': 'park.my-car',  # 742
    '953cb41e9b0f': 'place.courts',  # 744
    '4424b9bed8b1': 'stamp.ramen',  # 745
    '9bd2c9ff90f6': 'stamp.bridge',  # 746
    'b4aca68a368c': 'stamp.parks',  # 747
    '85138124571b': 'stamp.osm',  # 748
    '8de5342f6e6e': 'fact.wifi',  # 749
    '2559a9315058': 'ui.map',  # 755
    '741e21f67bcd': 'place.bbq',  # 756
    '07cab05ddb58': 'place.lawn',  # 757
    'bd284fe2a04f': 'place.pitch',  # 758
    '1aecab279462': 'fact.fenced',  # 759
    'bcb1e6da9c66': 'place.entrance',  # 760
    '14efbcf50f09': 'fact.no-wifi',  # 761
    'f0208e228c56': 'plug.ccs2',  # 763
    '6ccbaa0107dd': 'plug.type2',  # 764
    'f231e0990253': 'plug.chademo',  # 765
    'af2faebe3098': 'plug.schuko',  # 766
    'fbf1e04c48a5': 'ui.route',  # 767
    '341812b5a6af': 'power.pillar',  # 768
    '8bbe98926353': 'power.station',  # 770
    '3f692549482a': 'power.hookup',  # 771
    'a7cff5e6c25f': 'ui.privacy',  # 772
    'a951e43bd2c7': 'ui.filter',  # 774
    'aff97710e2be': 'fact.fuel',  # 775
    'ac074593ef1e': 'need.toilet',  # 776
    '9c5cc64fb1c0': 'ui.layer-add',  # 777
    '53036183d323': 'fact.card',  # 781
    '4547401896e9': 'refill.bulk',  # 782
    '69194cfce0f2': 'refill.soap',  # 783
    'bbf8e7d5b08e': 'refill.water',  # 784
    '994d49595131': 'refill.return',  # 785
    'f52e1d086549': 'refill.vending',  # 786
    '0b9f6e18d5ea': 'ui.arrow-up',  # 787
    '16c04f8db1ca': 'refill.jar',  # 788
    'bab9887bc379': 'place.spa',  # 790
    '9d33e7402be4': 'place.massage',  # 791
    'ed266abd1feb': 'place.yoga',  # 792
    'bb74fcca2792': 'place.worship',  # 793
    'a6cb99c945ad': 'rent.bags',  # 794
    '2d35e9005ad9': 'rent.scooter',  # 795
    'c00e0e3365d9': 'rent.kayak',  # 796
    '6539fbb5fba2': 'rent.skis',  # 797
    'ab63c97893fe': 'fact.backrest',  # 799
    '15107241c04c': 'fact.armrests',  # 800
    '3f1464352c7a': 'ui.receipt',  # 802
    '89a9b0e4bc8f': 'return.cans',  # 803
    '016edc4c71fe': 'return.bottles',  # 804
    '8901ac9a61f3': 'reuse.book-box',  # 806
    '404536f93c33': 'reuse.give-box',  # 807
    '6687c8468b08': 'reuse.clothing-swap',  # 808
    'c3b7654d758a': 'ui.headphones',  # 809
    'f259a82a6e17': 'ui.vibrate',  # 810
    '426ffb350c88': 'ui.screen-off',  # 811
    'def8562af41b': 'ui.vibrate',  # 812
    '5c7082d4b4d4': 'road.stopped-car',  # 816
    'ed7c825afc07': 'road.works',  # 817
    '42935239bbbb': 'road.pothole',  # 818
    'c98f0f39ebe3': 'road.crash',  # 819
    '5e2a67dd11bf': 'road.hazard',  # 820
    '65e777bcfcb4': 'road.works',  # 821
    '5eefa3eef7a7': 'road.closed',  # 822
    '065626cf6e76': 'ui.swap',  # 823
    '7dcd7f9c7ae1': 'ui.chevron-down',  # 824
    'f8c06f64b87a': 'ui.sliders',  # 825
    '3a774dfe0b68': 'fact.no-toll',  # 826
    '8acbd7f8ec7f': 'mode.taxi',  # 827
    '73ffef29372d': 'place.defibrillator',  # 828
    '50f13fb6a5ed': 'place.police',  # 829
    '79ad68d376f2': 'sail.compass',  # 830
    'd7560c1ff343': 'sail.current',  # 831
    '0ebe06ffdf51': 'sail.vhf',  # 832
    'ac5671160352': 'sail.buoy-red',  # 834
    'bc358dc75b63': 'sail.buoy-red',  # 835
    '5481da64dd4c': 'sail.buoy-red',  # 836
    '44a0caadbbda': 'sail.buoy-green',  # 837
    'cee38e1b96a0': 'sail.you',  # 838
    '3417ce36d802': 'place.marina',  # 839
    '068e83fc3b15': 'gnss.gps',  # 841
    'd370247868f0': 'gnss.galileo',  # 842
    'cbb217e68dc8': 'gnss.glonass',  # 843
    '89f7990c60aa': 'gnss.beidou',  # 844
    '523663240969': 'gnss.orbits',  # 845
    '8fca4d82d899': 'ui.torch',  # 848
    '01b1ace38661': 'food.pumpkin',  # 850
    '0b44155d35d9': 'food.apple',  # 851
    '15dc73a4c7c4': 'food.mushroom',  # 852
    'caf781fdb53c': 'food.chestnut',  # 853
    '42e341fe11e1': 'food.plum',  # 854
    '540ca6aebf65': 'food.fig',  # 855
    '78af22322c71': 'food.citrus',  # 856
    'a5daef51783b': 'food.parsnip',  # 857
    '2b4adc3cd586': 'place.market',  # 858
    '9c86a535412f': 'ui.units',  # 860
    '18428b3b4458': 'ui.text-size',  # 861
    '794b5d928b7d': 'ui.battery',  # 862
    '791b18f69fc6': 'ui.privacy',  # 863
    'c9c13d388014': 'ui.account',  # 864
    'd8e69799c57a': 'place.lockers',  # 865
    '522a75534c95': 'fact.socket',  # 866
    '3d84dbed0d53': 'ui.unmapped',  # 867
    '0d48820d9f1f': 'ski.lift',  # 868
    '3f3da8556eb6': 'ski.rental',  # 869
    'd369faa3fc55': 'ski.school',  # 870
    'fb66b91210d5': 'ski.pass',  # 871
    '436f1937be77': 'ui.clipboard',  # 874
    '292b2f092507': 'bin.empty',  # 876
    '797321ffd85a': 'bin.organic',  # 877
    '84ee6cf9026b': 'nature.spring',  # 879
    '86740e2b96c6': 'nature.brook',  # 880
    'da71740f7c09': 'place.hand-pump',  # 881
    'b211cf510282': 'water.flowing',  # 882
    'b9d2f9cb268b': 'ui.phone-outline',  # 883
    '543d181a5192': 'stay.key',  # 884
    '12c26298d39c': 'fact.noise',  # 885
    'ef09283403c0': 'street.kebab',  # 886
    '758c93e9947f': 'street.falafel',  # 887
    '83026085cd9d': 'street.dumpling',  # 888
    '3a1484bfaa7a': 'street.hot-dog',  # 889
    'ab1f3f45c338': 'street.chicken',  # 890
    '82f82bfab0e3': 'street.food-truck',  # 891
    '71b301fe2e84': 'ui.sort',  # 892
    'ba71f08ade7e': 'place.sign',  # 893
    'bb9611aaadc5': 'place.shop-front',  # 895
    '0d39fd142b7a': 'sweets.gelato',  # 898
    '613d3d4d2223': 'sweets.bakery',  # 899
    '1ed91132cbe6': 'sweets.pastry',  # 900
    '38c0fd8bcf90': 'sweets.chocolate',  # 901
    '6682f57b17d6': 'sweets.donuts',  # 902
    'f327bd984de7': 'sweets.crepes',  # 903
    'e31ee4dba70c': 'sweets.frozen-yogurt',  # 904
    'ea93968ef2ca': 'sweets.candy',  # 905
    '4efe8d2e5f55': 'place.pool',  # 906
    'cc8d373a4318': 'place.lake',  # 907
    'f934f6816caa': 'place.beach',  # 908
    'a72094185e6b': 'place.pool',  # 910
    '6fd89388442e': 'place.kayak',  # 911
    '393953b1064d': 'water.reservoir',  # 912
    '5a4cb5cc4102': 'water.works',  # 913
    '296dc6727675': 'tech.compute',  # 916
    'e994f182095a': 'tech.audio',  # 917
    '34526a568f50': 'tech.games',  # 918
    '79e56d55f623': 'ui.you',  # 921
    'dabf8a06f012': 'fact.kids',  # 922
    'd5467fca65dc': 'fun.single-rider',  # 923
    '34e42dffb824': 'fun.carousel',  # 924
    '44de3f3873b5': 'fun.raft',  # 925
    '34504e3f7d3e': 'fun.coaster',  # 926
    'e7f1c38ce780': 'place.cinema',  # 928
    '3676065f7b7a': 'food.burger',  # 929
    'e2c5ff877a00': 'food.ice-cream',  # 930
    '979f69f00f0b': 'food.bar',  # 931
    '5ce9f9c3946e': 'place.vending',  # 932
    '0c929f9a868a': 'place.nursing',  # 933
    'f7fe90027979': 'place.first-aid',  # 934
    '33d30e4eed9a': 'fact.pram',  # 935
    '8e9b7ab99be9': 'fact.wheelchair',  # 936
    'a7bbeddddf3c': 'place.lockers',  # 937
    'de30b929ea62': 'place.shelter',  # 938
    '9a0f9d0d42b7': 'fact.smoking',  # 939
    'bb3661a8965d': 'fun.mushroom',  # 940
    '25b34aa77d70': 'place.photo-point',  # 941
    '3ba76b0fc102': 'place.atm',  # 942
    'b64e61a8fc6b': 'park.parking',  # 943
    '15c0fc825605': 'transit.bus-stop',  # 944
    'f4a0a2481e9e': 'ui.info',  # 945
    '12752be2a481': 'fun.height',  # 948
    '09ca2fef8c9f': 'ui.code',  # 949
    '10622817e7cd': 'need.toilet',  # 950
    'cd9f1107bfc6': 'ui.compass',  # 953
    '8e096f834d7e': 'nature.summit',  # 954
    '8347ef16b82b': 'place.signpost',  # 955
    '152fc8125a61': 'weather.sunset',  # 957
    'b15fed199aa5': 'fact.mud',  # 959
    'c710d251f06c': 'ui.play',  # 960
    '6bfb9c8c0740': 'transit.pass',  # 961
    '701dacd7a2f0': 'fact.air-conditioning',  # 963
    'e63c9f5f3649': 'park.bike-stands',  # 970
    '64f30e809a07': 'park.bike-lockers',  # 971
    '139f9508478c': 'fact.lock',  # 972
    '0513efe12997': 'nav.turn-right',  # 974
    'd1a899749682': 'ui.volume',  # 975
    '91255a0a740e': 'ui.list',  # 976
    '742414911c79': 'place.drinking-fountain',  # 978
    '3eae780fadbc': 'weather.swim',  # 980
    '29460cbe7137': 'weather.sunrise',  # 981
    '07ed5c29c3ba': 'weather.golden-hour',  # 982
    'e0ffd22145bc': 'weather.sunset',  # 983
    '41a3384507fc': 'fact.groups',  # 985
    '18d6b857aebf': 'wifi.operators',  # 988
    '99fbd96c0faf': 'wifi.city',  # 989
    '966fd1345d8d': 'wifi.mesh',  # 990
    '1a58aade90eb': 'wifi.eu',  # 991
    'd7ade2faaa21': 'wild.badge',  # 992
    '61cd06204cfa': 'wild.dogs-on-lead',  # 993
    '74d6c321106c': 'wild.no-drones',  # 994
    'c79d4028920b': 'wild.paths-only',  # 995
    '9c209a56b3b8': 'wild.visitor-centre',  # 996
    '72be13186435': 'wild.deer',  # 997
    '46b876a3cb97': 'food.polish',  # 998
    '3410da22b30b': 'food.african',  # 999
    '07c007c65a80': 'food.spices',  # 1000
    'f3d3918b9524': 'weather.cloud',  # 1001
    'c3b5bed244de': 'zoo.feeding',  # 1004
    '8296e691de80': 'place.library',  # 1006
    '48533f70a93f': 'ui.ticket',  # 1007
    '4f9ba2c9d68c': 'pin.arts-centre',  # 1008
    'f5762f202e22': 'pin.atm',  # 1009
    '018936c22bc0': 'pin.attraction',  # 1010
    '337385a0c5c1': 'pin.doctor',  # 1011
    'c895aaa18cf4': 'pin.kiosk',  # 1012
    '73597798b8a0': 'pin.post-box',  # 1013
    '2280f2906bcd': 'pin.gifts',  # 1014
    '469e569f2381': 'pin.cheese',  # 1015
    'c4f58fdab8de': 'pin.jewellery',  # 1016
    '5c5ced38cb8a': 'pin.bike-rental',  # 1017
    '81ebbb602d7d': 'pin.florist',  # 1018
    '8db2af2c5e09': 'pin.bakery',  # 1019
    'f4f1103c4206': 'ui.edit',  # 1020
    '2633affa35d8': 'place.tower',  # 1023
    '6fa732eabb44': 'place.church',  # 1024
    'f19cf4397ee1': 'place.house',  # 1025
    '22bd3fae9693': 'transit.ferry',  # 1026
    '057f3f96b33d': 'place.bridge',  # 1027
}

DRAWINGS = {
    'f54fa0a7e4a2',  # 17
    '71a31a6d8e98',  # 18
    '80384cbce74c',  # 19
    '86fc2f954cf5',  # 20
    '31efc28deb3a',  # 21
    'cab135077195',  # 27
    '22d7403aa7b8',  # 28
    '2d71ca1b5626',  # 54
    'ab4643e97714',  # 381
    'b15989971e93',  # 385
    '293dd7dc1043',  # 400
    '443cbf6b6291',  # 469
    'b3d04e6aea05',  # 514
    '8b24323d23e5',  # 560
    'e5aba8e58c84',  # 561
    'edbe62261cc4',  # 562
    '09fc20be0a19',  # 618
    'ca35383f2d90',  # 629
    'bb23536de4ae',  # 630
    'fcbbffb48caa',  # 631
    'fb5197b2b832',  # 680
    '81adfc3cfe6f',  # 681
    '630e467ca998',  # 682
    '313b4b2584aa',  # 691
    '225e6931932c',  # 762
    '58dac7a8bb34',  # 773
    '06c873a29b12',  # 813
    'b95f89ccf6fa',  # 859
    '9b41c669f00e',  # 873
    'fc214a976501',  # 920
    '5699f213092e',  # 987
    '6fada4f9961c',  # 1002
    'a8429f47e6c8',  # 1021
    'bce692005c41',  # 1022
}

SYMBOLS = {
    '84192ff27aed',  # 488
    '0b9bb104213e',  # 489
    '48dfc578552c',  # 490
    'a0f9c12fe312',  # 491
    '048b0b0c86b7',  # 492
    '53aa096b9592',  # 493
    '8eb51b14be17',  # 737
    '543d181a5192',  # 884
    'bb9611aaadc5',  # 895
    '742414911c79',  # 978
}

ALIASES = {
    'ui-chevron-left': 'ui.back',
    'ui-back': 'ui.back',
    'ui-moon': 'ui.moon',
    'ui-walk': 'mode.walk',
    'ui-check': 'ui.check',
    'ui-x': 'ui.close',
    'ui-close': 'ui.close',
    'ui-utensils': 'cat.eat',
    'maki-restaurant': 'cat.eat',
    'ui-chevron-right': 'ui.chevron-right',
    'ui-wheelchair': 'fact.wheelchair',
    'ui-accessibility': 'fact.wheelchair',
    'ui-clock': 'ui.clock',
    'ui-layers': 'ui.layers',
    'ui-umbrella': 'fact.terrace',
    'ui-map': 'tab.map',
    'ui-chevron-up': 'ui.chevron-up',
    'ui-cloud-rain': 'weather.rain',
    'ui-rain': 'weather.rain',
    'ui-crosshair': 'ui.crosshair',
    'ui-locate-fixed': 'ui.crosshair',
    'ui-cloud-sun': 'weather.partly-cloudy',
    'ui-vegan': 'fact.vegan',
    'ui-leaf': 'fact.vegan',
    'ui-droplet': 'need.water',
    'ui-star': 'ui.star',
    'ui-calendar': 'ui.calendar',
    'ui-mic': 'ui.mic',
    'ui-flame': 'fact.trending',
    'ui-corner-up-right': 'tab.go',
    'ui-sun': 'weather.sun',
    'ui-bolt': 'cat.charge',
    'ui-cup': 'place.cafe',
    'maki-cafe': 'place.cafe',
    'ui-parking': 'park.parking',
    'maki-parking': 'park.parking',
    'ui-cloud': 'weather.cloud',
    'ui-shopping-bag': 'cat.shops',
    'ui-phone': 'ui.phone',
    'ui-wrench': 'ui.wrench',
    'ui-camera': 'ui.camera',
    'ui-car': 'mode.car',
    'maki-car': 'mode.car',
    'ui-cloud-check': 'ui.offline',
    'ui-book-open': 'tab.local',
    'ui-tree': 'nature.tree',
    'ui-package': 'fact.delivery',
    'ui-fuel': 'place.fuel',
    'maki-fuel': 'place.fuel',
    'ui-truck': 'mode.truck',
    'ui-bus': 'mode.bus',
    'ui-route': 'ui.route',
    'ui-waypoints': 'ui.route',
    'ui-search': 'ui.search',
    'ui-bed': 'cat.stay',
    'maki-lodging': 'cat.stay',
    'maki-park': 'place.park',
    'ui-trees': 'place.park',
    'ui-lock': 'ui.lock',
    'ui-bell': 'ui.bell',
    'maki-mountain': 'nature.peak',
    'ui-cash': 'fact.cash',
    'ui-users': 'fact.groups',
    'maki-drinking-water': 'place.drinking-water',
    'ui-paw': 'fact.dog',
    'ui-dog': 'fact.dog',
    'ui-soup': 'food.hot',
    'pinhead-handled_bowl_with_steam': 'food.hot',
    'ui-calendar-days': 'tab.soon',
    'ui-user': 'tab.mine',
    'ui-music': 'cat.fun',
    'ui-wifi': 'fact.wifi',
    'ui-key': 'ui.key',
    'ui-wind': 'weather.wind',
    'ui-globe': 'ui.globe',
    'ui-plane': 'mode.fly',
    'maki-airport': 'mode.fly',
    'ui-mountain': 'cat.nature',
    'ui-bike': 'mode.bike',
    'maki-bicycle': 'mode.bike',
    'ui-bookmark': 'ui.bookmark',
    'maki-bicycle-share': 'place.bike-dock',
    'ui-map-pin': 'ui.pin',
    'maki-marker': 'ui.pin',
    'ui-map-pinned': 'ui.pin.2',
    'ui-library': 'place.library',
    'maki-library': 'place.library',
    'ui-plug': 'fact.socket',
    'ui-plug-zap': 'fact.socket',
    'ui-heart': 'ui.heart',
    'ui-cross': 'place.pharmacy',
    'maki-pharmacy': 'place.pharmacy',
    'ui-hammer': 'place.tools',
    'temaki-tools': 'place.tools',
    'ui-share': 'ui.share',
    'ui-eye': 'ui.eye',
    'maki-viewpoint': 'ui.eye',
    'ui-sparkles': 'ui.sparkles',
    'ui-plus': 'ui.plus',
    'ui-refresh': 'ui.refresh',
    'ui-toilet': 'need.toilet',
    'maki-toilet': 'need.toilet',
    'pinhead-taco': 'food.taco',
    'ui-landmark': 'place.landmark',
    'maki-landmark': 'place.landmark',
    'maki-museum': 'place.museum',
    'ui-arrow-right': 'ui.arrow-right',
    'maki-cinema': 'place.cinema',
    'ui-book': 'place.books',
    'ui-tent': 'place.camp',
    'maki-campsite': 'place.camp',
    'ui-wine': 'food.wine',
    'ui-navigation': 'nav.arrow',
    'ui-navigation-2': 'nav.arrow.2',
    'ui-pizza': 'food.pizza',
    'pinhead-pizza_slice': 'food.pizza',
    'ui-martini': 'food.bar',
    'maki-bar': 'food.bar',
    'ui-flag': 'ui.flag',
    'ui-train': 'mode.transit',
    'temaki-bench': 'place.bench',
    'ui-locate': 'ui.locate',
    'ui-baby': 'fact.baby',
    'ui-braces': 'ui.code',
    'ui-card': 'fact.card',
    'maki-playground': 'place.playground',
    'ui-snowflake': 'weather.snow',
    'ui-cloud-snow': 'weather.snow',
    'ui-gift': 'place.gift',
    'ui-eye-off': 'ui.eye-off',
    'ui-alert-triangle': 'ui.warning',
    'ui-smartphone': 'ui.smartphone',
    'ui-burger': 'food.burger',
    'pinhead-burger': 'food.burger',
    'maki-fast-food': 'food.burger',
    'ui-scissors': 'place.tailor',
    'ui-download': 'ui.download',
    'ui-tag': 'ui.tag',
    'ui-shield': 'ui.privacy.2',
    'ui-coins': 'ui.coins',
    'ui-cube': 'ui.3d',
    'ui-rotate-3d': 'ui.3d',
    'ui-basket': 'cat.groceries',
    'ui-sailboat': 'cat.water',
    'ui-near': 'ui.query',
    'ui-school': 'place.school',
    'maki-school': 'place.school',
    'ui-bottle': 'fact.bottle-refill',
    'ui-trash': 'ui.trash',
    'ui-door': 'place.entrance',
    'ui-web': 'ui.web',
    'ui-dumbbell': 'place.fitness',
    'maki-fitness-centre': 'place.fitness',
    'ui-trending-up': 'ui.trending-up',
    'maki-swimming': 'place.pool',
    'maki-theatre': 'place.theatre',
    'ui-theater': 'place.theatre',
    'ui-palette': 'place.art',
    'ui-candy': 'food.sweets',
    'ui-elevator': 'place.lift',
    'ui-timer': 'ui.timer',
    'ui-recycle': 'bin.recycling',
    'maki-recycling': 'bin.recycling',
    'ui-link': 'ui.link',
    'ui-ticket': 'ui.ticket',
    'ui-sliders': 'ui.sliders',
    'ui-shower': 'place.shower',
    'temaki-shower': 'place.shower',
    'ui-beer': 'food.beer',
    'ui-cart': 'shop.cart',
    'ui-house': 'place.home',
    'ui-turn-right': 'nav.turn-right',
    'ui-turn-left': 'nav.turn-left',
    'ui-lightbulb': 'ui.bulb',
    'ui-headphones': 'ui.headphones',
    'ui-mail': 'ui.mail',
    'maki-post': 'ui.mail',
    'ui-washing-machine': 'place.laundry',
    'maki-laundry': 'place.laundry',
    'maki-amusement-park': 'place.theme-park',
    'ui-smoking': 'fact.smoking',
    'ui-grape': 'food.grapes',
    'ui-film': 'ui.film',
    'ui-gamepad': 'place.gaming',
    'maki-gaming': 'place.gaming',
    'ui-store': 'place.shop',
    'maki-shop': 'place.shop',
    'maki-defibrillator': 'place.defibrillator',
    'ui-stethoscope': 'place.doctor',
    'maki-doctor': 'place.doctor',
    'maki-dentist': 'place.dentist',
    'ui-sunset': 'weather.sunset',
    'ui-sunrise': 'weather.sunrise',
    'ui-arrow-down': 'ui.arrow-down',
    'ui-arrow-up': 'ui.arrow-up',
    'ui-tram': 'transit.tram',
    'ui-fish': 'market.fish',
    'ui-beef': 'market.butcher',
    'ui-apple': 'market.produce',
    'ui-receipt': 'ui.receipt',
    'ui-ice-cream': 'food.ice-cream',
    'pinhead-ice_cream_on_cone': 'food.ice-cream',
    'ui-settings': 'ui.settings',
    'maki-bank': 'place.bank',
    'maki-grocery': 'place.supermarket',
    'ui-u-turn': 'nav.turn-back',
    'ui-vibrate': 'ui.vibrate',
    'ui-chevron-down': 'ui.chevron-down',
    'maki-police': 'place.police',
    'ui-anchor': 'place.marina',
    'maki-harbor': 'place.marina',
    'maki-beach': 'place.beach',
    'ui-battery': 'ui.battery',
    'ui-filter': 'ui.filter',
    'temaki-spa': 'place.spa',
    'ui-church': 'place.worship',
    'maki-place-of-worship': 'place.worship',
    'ui-shirt': 'reuse.clothing-swap',
    'ui-info': 'ui.info',
    'temaki-vending_machine': 'place.vending',
    'ui-air-vent': 'fact.air-conditioning',
    'ui-ship': 'transit.ferry',
    'maki-bridge': 'place.bridge',
    'maki-bus': 'transit.bus-stop',
    'ui-wifi-off': 'fact.no-wifi',
    'ui-cloud-off': 'ui.no-signal',
    'maki-charging-station': 'ui.charge',
    'pinhead-charging_station': 'ui.charge',
    'pinhead-sushi': 'food.sushi',
    'temaki-bread': 'food.bread',
    'maki-bakery': 'food.bread',
    'pinhead-coffee_mug_with_steam': 'food.coffee',
    'maki-restaurant-noodle': 'food.ramen',
    'ui-person': 'fact.person',
    'ui-gauge': 'ui.gauge',
    'maki-pitch': 'place.pitch',
    'temaki-climbing': 'place.climbing',
    'ui-compass': 'ui.compass.2',
    'pinhead-cheese_wedge': 'market.cheese',
    'pinhead-fish': 'market.fish',
    'pinhead-meat': 'market.butcher',
    'maki-restaurant-seafood': 'market.fish',
    'pinhead-leaf': 'fact.vegan',
}
