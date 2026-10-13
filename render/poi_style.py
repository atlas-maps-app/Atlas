"""How a place looks in Atlas, from its tags: a category (colour), a glyph, a kind label, and for chains a brand style.
Brand colours are Atlas's own style table, keyed by the mapped brand:wikidata; names come from the brand tag. No logos."""

CATS = {
    'food': ('Food & drink', '#E07A1F'),
    'grocery': ('Groceries', '#2E9E5B'),
    'shop': ('Shops', '#8E5BD0'),
    'beauty': ('Beauty & hair', '#D2508F'),
    'culture': ('Culture', '#B8326A'),
    'stay': ('Stay', '#2E6FD8'),
    'health': ('Health', '#D23C3C'),
    'service': ('Services', '#5A6478'),
    'sport': ('Sport', '#1D8FA0'),
}
# 24x24 stroke glyphs
GLYPH = {
    'fork': '<path d="M7 3v8a2 2 0 0 0 4 0V3M9 13v8M16 3c-2 1-2.5 4-2.5 6.5H17V21"/>',
    'cup': '<path d="M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z"/><path d="M16 11h2a2 2 0 0 1 0 4h-2M9 3c0 2 2 2 2 4"/>',
    'beer': '<path d="M6 7h10v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2zM16 10h2a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-2M6 7a3 3 0 0 1 3-3 3 3 0 0 1 5 0 3 3 0 0 1 2 3"/>',
    'cocktail': '<path d="M4 4h16l-8 9zM12 13v7M8 20h8"/>',
    'burger': '<path d="M4 10a8 5 0 0 1 16 0zM4 14h16M5 17h14a1 1 0 0 1 0 0 3 3 0 0 1-3 3H8a3 3 0 0 1-3-3z"/>',
    'icecream': '<path d="M8 11a4 4 0 1 1 8 0zM8 11l4 10 4-10"/>',
    'bag': '<path d="M5 8h14l-1 13H6zM9 8V6a3 3 0 0 1 6 0v2"/>',
    'shirt': '<path d="M8 3l-5 3 2 4 3-1v12h8V9l3 1 2-4-5-3a4 4 0 0 1-8 0z"/>',
    'gift': '<path d="M4 9h16v4H4zM6 13h12v8H6zM12 9v12M12 9C10 5 7 5 7 7s3 2 5 2c2 0 5 0 5-2s-3-2-5 2"/>',
    'ring': '<circle cx="12" cy="14" r="6"/><path d="M9 4h6l-3 4z"/>',
    'scissors': '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/><path d="M8 16L19 4M16 16L5 4"/>',
    'shoe': '<path d="M3 16V8h5l3 4 7 2a3 3 0 0 1 3 3v1H3z"/>',
    'bread': '<path d="M5 11a7 5 0 0 1 14 0v8H5z"/><path d="M9 9l-1 3M13 9l-1 3M17 10l-1 3"/>',
    'cake': '<path d="M4 13h16v7H4zM6 13V9h12v4M12 9V6M12 4v.5"/>',
    'cart': '<path d="M3 4h2l2 11h11l2-8H6"/><circle cx="9" cy="19" r="1.5"/><circle cx="17" cy="19" r="1.5"/>',
    'cheese': '<path d="M3 17l9-11 9 6v5zM3 17h18"/><circle cx="10" cy="14" r="1"/><circle cx="15" cy="15" r="1"/>',
    'bottle': '<path d="M10 2h4v4l2 3v12H8V9l2-3z"/><path d="M8 13h8"/>',
    'leaf': '<path d="M12 21V9M12 13c-4-1-7-4-7-9 4 1 7 4 7 9 0-5 3-8 7-9 0 5-3 8-7 9"/>',
    'ticket': '<path d="M3 8a2 2 0 0 0 2-2h14a2 2 0 0 0 2 2v8a2 2 0 0 0-2 2H5a2 2 0 0 0-2-2z"/><path d="M14 6v12"/>',
    'sofa': '<path d="M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3M3 11h18v6H3zM5 17v2M19 17v2"/>',
    'glasses': '<circle cx="7" cy="14" r="3.5"/><circle cx="17" cy="14" r="3.5"/><path d="M10.5 14h3M3.5 13L5 7M20.5 13L19 7"/>',
    'bike': '<circle cx="6" cy="16" r="3.5"/><circle cx="18" cy="16" r="3.5"/><path d="M6 16l4-8h6l2 8M10 8L8.5 5H7M16 8l-3.5 8"/>',
    'book': '<path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zM4 19V5M19 19v2H6"/>',
    'note': '<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>',
    'palette': '<path d="M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.5-1.6 0-1.2-1-1.4-1-2.4 0-1 .8-1.5 2-1.5H17a4 4 0 0 0 4-4c0-4.5-4-8.5-9-8.5z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="15" cy="7" r="1"/>',
    'bed': '<path d="M3 18V7M3 13h18v5M21 13a3 3 0 0 0-3-3h-7v3M7 11a1.5 1.5 0 1 0 0-.01"/>',
    'columns': '<path d="M3 9l9-5 9 5M5 9v9M9.5 9v9M14.5 9v9M19 9v9M3 21h18"/>',
    'frame': '<path d="M4 4h16v16H4zM7 16l3-4 2 2 3-4 2 6z"/>',
    'mask': '<path d="M4 5h16v6a8 8 0 0 1-16 0z"/><path d="M8 9h2M14 9h2M9 14c1.5 1.5 4.5 1.5 6 0"/>',
    'cross': '<path d="M9 3h6v6h6v6h-6v6H9v-6H3V9h6z"/>',
    'tooth': '<path d="M7 3c-3 0-4 3-3 6 1 2 1 5 2 9 .5 2 2 2 2.5 0L10 14h4l1.5 4c.5 2 2 2 2.5 0 1-4 1-7 2-9 1-3 0-6-3-6-2 0-3 1-5 1S9 3 7 3z"/>',
    'cash': '<path d="M3 7h18v10H3z"/><circle cx="12" cy="12" r="2.5"/><path d="M6 10v4M18 10v4"/>',
    'mail': '<path d="M3 6h18v12H3zM3 7l9 6 9-6"/>',
    'dumbbell': '<path d="M6 7v10M3 9v6M18 7v10M21 9v6M6 12h12"/>',
    'briefcase': '<path d="M3 8h18v11H3zM9 8V5h6v3M3 13h18"/>',
    'star': '<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"/>',
    'flower': '<circle cx="12" cy="9" r="2.5"/><path d="M12 6.5a3 3 0 1 1 0-.01M14.5 9a3 3 0 1 1 0 .01M9.5 9a3 3 0 1 1 0 .01M12 11.5a3 3 0 1 1 0 .01M12 14v7M12 18c-2 0-4-1-4-3"/>',
    'spark': '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6"/>',
    'tea': '<path d="M5 10h12v4a6 6 0 0 1-12 0zM17 11h1.5a2 2 0 0 1 0 4H17M8 6l1-3M12 6l1-3"/>',
    'pin': '<circle cx="12" cy="10" r="3"/><path d="M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z"/>',
}
SHOP = {  # shop=* -> (category, glyph, kind label)
    'clothes': ('shop', 'shirt', 'Clothes'), 'fashion_accessories': ('shop', 'bag', 'Accessories'), 'bag': ('shop', 'bag', 'Bags'), 'shoes': ('shop', 'shoe', 'Shoes'),
    'gift': ('shop', 'gift', 'Gifts'), 'jewelry': ('shop', 'ring', 'Jewellery'), 'interior_decoration': ('shop', 'sofa', 'Interiors'), 'furniture': ('shop', 'sofa', 'Furniture'),
    'antiques': ('shop', 'sofa', 'Antiques'), 'art': ('culture', 'palette', 'Art'), 'music': ('shop', 'note', 'Records'), 'musical_instrument': ('shop', 'note', 'Instruments'),
    'books': ('shop', 'book', 'Books'), 'toys': ('shop', 'gift', 'Toys'), 'games': ('shop', 'gift', 'Games'), 'party': ('shop', 'gift', 'Party'), 'ticket': ('service', 'ticket', 'Tickets'),
    'optician': ('health', 'glasses', 'Optician'), 'bicycle': ('shop', 'bike', 'Bikes'), 'houseware': ('shop', 'sofa', 'Homeware'), 'doityourself': ('shop', 'bag', 'DIY'),
    'copyshop': ('service', 'book', 'Copy shop'), 'tailor': ('service', 'scissors', 'Tailor'), 'fabric': ('shop', 'scissors', 'Fabric'), 'craft': ('shop', 'palette', 'Craft'),
    'hairdresser': ('beauty', 'scissors', 'Hair'), 'beauty': ('beauty', 'spark', 'Beauty'), 'cosmetics': ('beauty', 'spark', 'Cosmetics'), 'massage': ('beauty', 'spark', 'Massage'), 'tattoo': ('beauty', 'spark', 'Tattoo'),
    'chemist': ('health', 'cross', 'Chemist'),
    'supermarket': ('grocery', 'cart', 'Supermarket'), 'convenience': ('grocery', 'cart', 'Corner shop'), 'bakery': ('grocery', 'bread', 'Bakery'), 'pastry': ('grocery', 'cake', 'Pâtisserie'),
    'confectionery': ('grocery', 'cake', 'Sweets'), 'cheese': ('grocery', 'cheese', 'Cheese'), 'alcohol': ('grocery', 'bottle', 'Wine & spirits'), 'wine': ('grocery', 'bottle', 'Wine'),
    'tea': ('grocery', 'tea', 'Tea'), 'florist': ('shop', 'flower', 'Florist'), 'newsagent': ('shop', 'book', 'Newsagent'), 'kiosk': ('shop', 'book', 'Kiosk'),
    'cannabis': ('shop', 'leaf', 'Coffeeshop'), 'rental': ('service', 'bike', 'Rental'), 'vacant': ('service', 'pin', 'Empty shop'),
}
AMENITY = {
    'restaurant': ('food', 'fork', 'Restaurant'), 'cafe': ('food', 'cup', 'Café'), 'pub': ('food', 'beer', 'Brown café'), 'bar': ('food', 'cocktail', 'Bar'),
    'fast_food': ('food', 'burger', 'Takeaway'), 'ice_cream': ('food', 'icecream', 'Ice cream'), 'nightclub': ('food', 'cocktail', 'Club'),
    'pharmacy': ('health', 'cross', 'Pharmacy'), 'doctors': ('health', 'cross', 'Doctor'), 'dentist': ('health', 'tooth', 'Dentist'),
    'theatre': ('culture', 'mask', 'Theatre'), 'arts_centre': ('culture', 'palette', 'Arts centre'), 'art_gallery': ('culture', 'frame', 'Gallery'), 'events_venue': ('culture', 'star', 'Venue'),
    'atm': ('service', 'cash', 'Cash machine'), 'post_box': ('service', 'mail', 'Post box'), 'bicycle_rental': ('service', 'bike', 'Bike rental'), 'public_bookcase': ('culture', 'book', 'Book swap'),
}
TOURISM = {'hotel': ('stay', 'bed', 'Hotel'), 'guest_house': ('stay', 'bed', 'Guest house'), 'apartment': ('stay', 'bed', 'Apartment'), 'museum': ('culture', 'columns', 'Museum'),
           'gallery': ('culture', 'frame', 'Gallery'), 'attraction': ('culture', 'star', 'Attraction')}
LEISURE = {'fitness_centre': ('sport', 'dumbbell', 'Gym'), 'sports_centre': ('sport', 'dumbbell', 'Sports')}

# Brand styles, keyed by brand:wikidata (sign colour, text colour). Only what is mapped here; the rest get a neutral sign.
BRAND = {
    'Q1653985': ('#00A0E2', '#FFFFFF'),   # Albert Heijn
    'Q13639185': ('#9E1B32', '#FFFFFF'),  # Gall & Gall
    'Q2609459': ('#E30613', '#FFFFFF'),   # Etos
    'Q33903645': ('#E3000F', '#FFFFFF'),  # Blokker
    'Q582245': ('#FFD200', '#C8102E'),    # FEBO
    'Q62562792': ('#7AB929', '#FFFFFF'),  # BENU
    'Q74051230': ('#FFB800', '#1E1E1E'),  # Geldmaat
    'Q127962': ('#C41230', '#FFFFFF'),    # Levi's
    'Q1126126': ('#1A1A1A', '#F6D21E'),   # Dr. Martens
    'Q6702957': ('#D31F40', '#FFFFFF'),   # Lululemon
    'Q60772401': ('#1A1A1A', '#FFFFFF'),  # COS
    'Q648458': ('#1E3765', '#FFFFFF'),    # Birkenstock
    'Q2220070': ('#23412F', '#E9D9A6'),   # Simon Lévelt
    'Q13433690': ('#5B3A29', '#F2E6CF'),  # Bagels & Beans
    'Q3572115': ('#FFE600', '#1E1E1E'),   # YellowKorner
    'Q934096': ('#9B1B1F', '#FFFFFF'),    # Red Wing
    'Q527877': ('#2B2A28', '#E89A2B'),    # Carhartt
    'Q24039229': ('#1E1E1E', '#FFFFFF'),  # The Hoxton
    'Q7958488': ('#1E1E1E', '#FFFFFF'),   # W Hotels
    'Q123655125': ('#F4C6CF', '#3A2A2E'), # My Jewellery
}

def style(t):
    """(category, glyph, kind label, brand name or None, sign colour, text colour) for a place, or None if it is not a place."""
    hit = None
    if t.get('amenity') in AMENITY: hit = AMENITY[t['amenity']]
    elif t.get('shop') in SHOP: hit = SHOP[t['shop']]
    elif 'shop' in t: hit = ('shop', 'bag', t['shop'].replace('_', ' ').capitalize())
    elif t.get('tourism') in TOURISM: hit = TOURISM[t['tourism']]
    elif t.get('leisure') in LEISURE: hit = LEISURE[t['leisure']]
    elif 'office' in t: hit = ('service', 'briefcase', 'Office')
    elif t.get('healthcare'): hit = ('health', 'cross', 'Health')
    if not hit: return None
    cat, glyph, kind = hit
    brand = t.get('brand')
    bg, fg = BRAND.get(t.get('brand:wikidata'), (None, None))
    if brand and not bg: bg, fg = '#26282C', '#FFFFFF'
    return dict(cat=cat, col=CATS[cat][1], glyph=glyph, kind=kind, brand=brand, bg=bg, fg=fg)
