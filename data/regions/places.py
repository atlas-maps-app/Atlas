"""Every place beyond Amsterdam, in one table: where it is (fetch/regions.py reads that part) and how its views are framed
(render/regions.py reads the views). A new place is one entry here, then two commands:
    python3 fetch/regions.py <name>                        -> data/regions/<name>/raw.json, wx.json, region.json
    python3 render/regions.py <name> sunny,grey,wet        -> cache/render/<name>_<moment>_{g,o,o2}.svg

bbox is (south, west, north, east) and origin (lat, lon), in degrees; title names the place on screen; tz is its time zone
(Europe/Amsterdam when absent); wx_dates are the days of weather fetched; moments name the day and clock time a screen shows;
tradition, park and zoo pick a renderer's rules. views are the cameras: '' is the overview, any other key renders as <name>_<key>;
their fields are what render/city.py's Camera and render/regions.py read (target, yaw, pitch, dist, fov, cy, radius, ground_r, ...)."""
PLACES = {
    # the terminal, the nearest runways and the motorways that tunnel under them
    'schiphol': dict(bbox=(52.290, 4.725, 52.330, 4.800), origin=(52.3105, 4.7620), title='Schiphol', views={
        # looking north-east over the terminal, with 18C/36C, 09/27 and the A4 in view
        '': dict(target=(150, 0, 150), yaw=35, pitch=34, dist=3300, fov=36, cy=440, radius=2900, ground_r=4200),
        'close': dict(target=(80, 0, 330), yaw=290, pitch=37, dist=560, fov=36, cy=440, radius=560, ground_r=1600, win_d=600)}),
    # the Louvre, the Forum des Halles mall, the Seine and the Île de la Cité
    'paris': dict(bbox=(48.8500, 2.3300, 48.8660, 2.3560), origin=(48.8580, 2.3440), title='Paris', views={
        # looking east up the Seine: the Louvre on the left, the Île de la Cité and Notre-Dame ahead, Les Halles beyond
        '': dict(target=(400, 0, 560), yaw=58, pitch=36, dist=1000, fov=36, cy=430, radius=660, ground_r=2200, sett_d=250, furniture_px=2.5, no_ao=True),
        'close': dict(target=(380, 0, 560), yaw=58, pitch=38, dist=470, fov=36, cy=440, radius=440, ground_r=1200, win_d=560)}),
    # Maasvlakte 2: container terminals, the sea wall, the beach
    'port': dict(bbox=(51.940, 3.980, 51.985, 4.060), origin=(51.9620, 4.0180), title='Port of Rotterdam', views={
        # looking south-west over the container terminals of Maasvlakte 2 to the beach and the North Sea
        '': dict(target=(-300, 0, 0), yaw=235, pitch=33, dist=3600, fov=36, cy=430, radius=3200, ground_r=4800),
        'close': dict(target=(2250, 0, 1430), yaw=20, pitch=32, dist=880, fov=36, cy=440, radius=860, ground_r=2000, win_d=700)}),
    # Zuid-Kennemerland: dunes, beach, forest and lakes inside a national park
    'dunes': dict(bbox=(52.375, 4.545, 52.425, 4.615), origin=(52.4000, 4.5800), title='Zuid-Kennemerland', views={
        # looking south along the coast: beach, dunes, forest and lakes inside the national park
        '': dict(target=(-1150, 0, 250), yaw=262, pitch=30, dist=3000, fov=36, cy=420, radius=2900, ground_r=4500, no_ao=True, terrain_cell=30.0)}),
    # three building traditions beyond the Netherlands and Paris, to prove the kit: a Tuscan hill town, a Norwegian wooden harbour, a London terrace street;
    # each seen up close at the living map's distance (their street-level stops are in render/street_stops.py)
    'sangimignano': dict(bbox=(43.4590, 11.0310, 43.4760, 11.0560), origin=(43.4677, 11.0432), title='San Gimignano', tz='Europe/Rome', tradition='it_tuscan', views={
        'close': dict(target=(-24, 334.5, 22), yaw=45, pitch=34, dist=430, fov=36, cy=440, radius=420, ground_r=1300, win_d=480)}),
    'bergen': dict(bbox=(60.3880, 5.3060, 60.4050, 5.3440), origin=(60.3972, 5.3240), title='Bergen', tz='Europe/Oslo', tradition='no_timber', views={
        'close': dict(target=(27, 2.8, -33), yaw=55, pitch=32, dist=430, fov=36, cy=440, radius=420, ground_r=1300, win_d=480)}),
    'nottinghill': dict(bbox=(51.5110, -0.2110, 51.5190, -0.1970), origin=(51.5150, -0.2040), title='Notting Hill', tz='Europe/London', tradition='gb_terrace', views={
        'close': dict(target=(135, 0, 245), yaw=45, pitch=36, dist=330, fov=36, cy=440, radius=340, ground_r=1100, win_d=460)}),
    # the canal ring zoomed out: the Westerkerk, the Dam, the Jordaan, the three canals and the Amstel, Noord's waterfront across the IJ, for the map seen from further away
    # (the whole box times out at Overpass: fetch it as the ring 52.360-52.384 x 4.872-4.910 plus three boxes north-west, north-east and east, merged by id)
    'grachten': dict(bbox=(52.3600, 4.8720, 52.3930, 4.9220), origin=(52.3720, 4.8910), title='Amsterdam, the canal ring',
                     moments={'eve': ((2026, 10, 1), (19, 2))},          # the Eat screens' moment: dinner, Thursday 19:02
                     views={
        # looking the living map's way (north-east): the Westerkerk, the Jordaan, the three canals, the Dam and the IJ beyond
        '': dict(target=(-150, 0, -100), yaw=52, pitch=42, dist=2700, fov=36, cy=440, radius=1500, ground_r=2600),
        # the Map home on the same extract: the Westermarkt low in the middle where the blue dot stands, the canal ring and Centraal ahead
        'home': dict(target=(-177, 0, -521), yaw=52, pitch=44, dist=2300, fov=36, cy=440, radius=1500, ground_r=2600),
        # the Eat screen on the same extract: the Westermarkt where the dinner search starts, the places to eat above the sheet
        'eat': dict(target=(-468, 0, -270), yaw=52, pitch=46, dist=1600, fov=36, cy=300, radius=1400, ground_r=2400)}),
    # a theme park: Efteling, its rides, coaster tracks, woods and ponds, at a busy Saturday's moment (the recorded waits of 26 September)
    'efteling': dict(bbox=(51.6400, 5.0340, 51.6590, 5.0640), origin=(51.6500, 5.0500), title='Efteling', wx_dates=('2026-09-26', '2026-10-01'),
                     moments={'sat': ((2026, 9, 26), (14, 0))}, park='efteling', views={
        # from above its south end: Ruigrijk's coasters near, Symbolica in the middle, Reizenrijk and Marerijk beyond
        '': dict(target=(20, 0, 250), yaw=90, pitch=46, dist=950, fov=36, cy=330, radius=650, ground_r=1500),
        # Joris en de Draak up close: the two timber tracks racing through the wood, looking east as the park map does
        'joris': dict(target=(138, 6, 345), yaw=90, pitch=40, dist=520, fov=36, cy=250, radius=420, ground_r=900)}),
    # a zoo: ARTIS in the Plantage, its enclosures, houses, ponds and trees, at a real Wednesday afternoon (30 September, 14:00)
    'artis': dict(bbox=(52.3600, 4.9070, 52.3725, 4.9265), origin=(52.3662, 4.9165), title='ARTIS', wx_dates=('2026-09-30', '2026-10-01'),
                  moments={'wed': ((2026, 9, 30), (14, 0))}, zoo='artis', views={
        # from above its main gate on Plantage Kerklaan, looking east down its length to the gorillas, the sea lions and the penguins
        '': dict(target=(0, 0, 0), yaw=95, pitch=42, dist=1600, fov=36, cy=290, radius=640, ground_r=1500),
        # the gorillas up close: their island, their house beside it, the chimpanzees and the Aquarium beyond
        'gorilla': dict(target=(180, 3, 100), yaw=95, pitch=40, dist=430, fov=36, cy=250, radius=380, ground_r=900)}),
    # a first look across the Atlantic: Washington Square and the Village, the arch at the origin, the mapped building heights of Manhattan
    'newyork': dict(bbox=(40.7270, -74.0050, 40.7345, -73.9930), origin=(40.7308, -73.9973), title='New York, Washington Square', tz='America/New_York', views={
        # from the south-west, the arch and the fountain in the middle, the Village's blocks and NYU's towers around it
        '': dict(target=(40, 0, 60), yaw=25, pitch=34, dist=520, fov=36, cy=440, radius=500, ground_r=1500, win_d=520)}),
}
