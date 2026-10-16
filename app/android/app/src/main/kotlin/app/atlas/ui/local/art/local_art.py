#!/usr/bin/env python3
"""local_art.py: the local package's drawings and the foods' data.

    python3 local_art.py

Writes
  app/src/main/res/raw/local_art.json            drawings in the symbol catalogue's resource format
  app/src/main/kotlin/app/atlas/ui/local/FoodData.kt   the ~60 foods and their seasons, as a table
  app/src/main/res/values{,-nl,-de,-fr}/strings_local_foods.xml   their names

The drawings are of two kinds. The three food icons the Season board draws only inside its wheel (strawberries, asparagus,
kale) are lifted out of that SVG exactly as drawn. The generic food glyphs (a berry, a pod, a root...) cover foods the
boards draw no icon for: they are small silhouettes in the boards' 24 x 24 style, tinted by the food's colour, with a
green leaf. Python 3 standard library only.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isdir(os.path.join(ROOT, 'canvas')):
    ROOT = os.path.dirname(ROOT)
sys.path.insert(0, os.path.join(ROOT, 'app', 'android', 'places', 'tools'))
import board_symbols as B  # noqa: E402

RES = os.path.join(ROOT, 'app', 'android', 'app', 'src', 'main', 'res')
KT = os.path.join(ROOT, 'app', 'android', 'app', 'src', 'main', 'kotlin', 'app', 'atlas', 'ui', 'local', 'FoodData.kt')

LEAF = '#4A9A3A'

# Generic glyphs: currentColor is the food's own colour.
GLYPHS = {
    'berry': '<circle cx="8.5" cy="14" r="3.6" fill="currentColor"/><circle cx="15.5" cy="14" r="3.6" fill="currentColor"/><circle cx="12" cy="9.5" r="3.6" fill="currentColor"/><circle cx="12" cy="18.2" r="3.4" fill="currentColor"/><path d="M12 6.4c0-1.6 1-2.8 3-3.2-.2 1.8-1.4 3-3 3.2z" fill="%s"/>' % LEAF,
    'stone': '<circle cx="12" cy="14" r="7" fill="currentColor"/><path d="M12 7c0-2 1-3.4 3-4" stroke="#6B3A1E" stroke-width="1.6" fill="none" stroke-linecap="round"/><path d="M12 6c1-1.6 3-2 4.5-1.4-1 1.4-3 2-4.5 1.4z" fill="%s"/>' % LEAF,
    'round': '<circle cx="12" cy="13.5" r="7.8" fill="currentColor"/><path d="M12 5.8c-1.4-1.4-3-1.6-4.4-.8 1.2 1.6 3 2 4.4.8zM12 5.8c1.4-1.4 3-1.6 4.4-.8-1.2 1.6-3 2-4.4.8z" fill="%s"/>' % LEAF,
    'leaf': '<path d="M12 21V9" stroke="%s" stroke-width="2" stroke-linecap="round"/><path d="M12 19C6 18 3.6 12.6 4.4 5c6 .2 9.6 3.4 9.6 8.6 0 2.4-.8 4-2 5.4z" fill="currentColor"/><path d="M12 19c1.4-3 2.6-5 5.4-7 .2 4-1.4 6.6-5.4 7z" fill="currentColor" opacity=".75"/>' % LEAF,
    'root': '<path d="M9 8.5h6L12.8 21.5h-1.6z" fill="currentColor"/><path d="M12 8.5c-2-1-3-2.6-2.8-4.6 1.6.4 2.6 1.6 2.8 4.6zM12 8.5c0-3 1-4.4 2.4-5.2.6 2-.2 3.8-2.4 5.2z" fill="%s"/>' % LEAF,
    'pod': '<path d="M4 17.5C4.5 10 10 5 20 4.5c0 8-5 14-12.5 14.2-1.6 0-3-.4-3.5-1.2z" fill="currentColor"/><circle cx="9" cy="14" r="1.7" fill="#fff" opacity=".55"/><circle cx="13" cy="10.6" r="1.7" fill="#fff" opacity=".55"/><circle cx="16.4" cy="7.6" r="1.2" fill="#fff" opacity=".55"/>',
    'cabbage': '<circle cx="12" cy="12.5" r="8.6" fill="currentColor"/><path d="M12 4c-2.6 3.2-2.6 13.2 0 17M12 4c2.6 3.2 2.6 13.2 0 17M3.7 12.5c4.6-2 12-2 16.6 0" stroke="#fff" stroke-width="1.1" fill="none" opacity=".5"/>',
    'onion': '<path d="M12 21c-4.4 0-7.5-3-7.5-7.2C4.5 10.2 8 7.6 10.6 4.4c.4-.6 1-.6 1.4-.6s1 0 1.4.6C16 7.6 19.5 10.2 19.5 13.8 19.5 18 16.4 21 12 21z" fill="currentColor"/><path d="M12 20.4c-2.4-3-2.6-9.4 0-14M9 19.6c-1.8-3-1.6-7-.2-10M15 19.6c1.8-3 1.6-7 .2-10" stroke="#fff" stroke-width="1" fill="none" opacity=".4"/><path d="M12 4c0-1 .4-1.8 1.4-2.4" stroke="%s" stroke-width="1.6" fill="none" stroke-linecap="round"/>' % LEAF,
    'stalk': '<path d="M8 21L9.6 6.4M12 21V4M16 21L14.4 6.4" stroke="currentColor" stroke-width="2.8" stroke-linecap="round"/><path d="M9.6 6.4c-1.6-.6-2.6-1.8-2.8-3.4M14.4 6.4c1.6-.6 2.6-1.8 2.8-3.4" stroke="%s" stroke-width="1.6" fill="none" stroke-linecap="round"/>' % LEAF,
    'nut': '<path d="M12 4.4c4.4 0 7.4 3.8 7.4 8.4 0 4-3.4 7-7.4 7s-7.4-3-7.4-7c0-4.6 3-8.4 7.4-8.4z" fill="currentColor"/><path d="M5 11c3.4 1.6 10.6 1.6 14 0" stroke="#fff" stroke-width="1.4" fill="none" opacity=".45"/><path d="M12 4.4c-.4-1.2 0-2.2 1-2.8" stroke="#6B3A1E" stroke-width="1.6" fill="none" stroke-linecap="round"/>',
    'shell': '<path d="M12 4C6 4 2.5 9 2.5 14.4c0 3.4 2 5.6 5 5.6h9c3 0 5-2.2 5-5.6C21.5 9 18 4 12 4z" fill="currentColor"/><path d="M12 5v14.4M7.4 7.4C5.6 11 5.8 16 7.6 19.4M16.6 7.4c1.8 3.6 1.6 8.6-.2 12" stroke="#fff" stroke-width="1" fill="none" opacity=".45"/>',
    'fish': '<path d="M3 12c3-4.6 8.4-6.6 13.4-4.2L21 5.4v13.2l-4.6-2.4C11.4 18.6 6 16.6 3 12z" fill="currentColor"/><circle cx="8" cy="11" r="1.1" fill="#fff"/><path d="M12 8.6c1.2 2 1.2 4.8 0 6.8" stroke="#fff" stroke-width="1" fill="none" opacity=".45"/>',
    'potato': '<path d="M5 13c-.4-4 3.2-7.4 8-7.2 4.6.2 6.8 3.4 6 7-.8 3.8-4.2 6.2-8.4 6C6.6 18.6 5.2 16 5 13z" fill="currentColor"/><circle cx="9" cy="11" r=".9" fill="#000" opacity=".25"/><circle cx="14" cy="14" r=".9" fill="#000" opacity=".25"/><circle cx="12.4" cy="9.4" r=".8" fill="#000" opacity=".25"/>',
    'corn': '<path d="M12 3.6c3 2.6 3.6 8.6 2.4 14.2L12 21l-2.4-3.2C8.4 12.2 9 6.2 12 3.6z" fill="currentColor"/><path d="M9.2 12h5.6M9.6 8.6h4.8M9.8 15.4h4.4M12 4v16" stroke="#fff" stroke-width=".9" fill="none" opacity=".45"/><path d="M12 21c-3-.6-5-3.4-5.4-7.4 2.4 1 4.4 3.4 5.4 7.4zM12 21c3-.6 5-3.4 5.4-7.4-2.4 1-4.4 3.4-5.4 7.4z" fill="%s"/>' % LEAF,
    'cuke': '<path d="M5.4 18.6C3.4 16.6 4 14 6.4 11.6L15 3.4c1.6-1.4 3.6-1.2 4.8.4s1 3.4-.6 4.8L10.6 17.8C8.6 20 6.8 20 5.4 18.6z" fill="currentColor"/><path d="M8 14l1.4 1.4M11 11l1.4 1.4M14 8l1.4 1.4" stroke="#fff" stroke-width="1.1" stroke-linecap="round" opacity=".5"/>',
    'beet': '<path d="M12 21c-4.4 0-7-3-7-6.6C5 10.6 8.6 8 12 8s7 2.6 7 6.4c0 3.6-2.6 6.6-7 6.6z" fill="currentColor"/><path d="M12 8c-1.6-1-2.4-2.6-2.4-4.6M12 8c0-2.6.8-4.2 2.4-5" stroke="%s" stroke-width="1.8" fill="none" stroke-linecap="round"/><path d="M12 21v1.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>' % LEAF,
}


def svg_of(inner, view='0 0 24 24'):
    return '<svg width="24" height="24" viewBox="%s">%s</svg>' % (view, inner)


def wheel_icons():
    """Icons the Season board draws only in its wheel: <g aria-label="Kale"><circle/><g transform="translate scale(.5)">...</g></g>."""
    with open(os.path.join(ROOT, 'canvas', 'project', 'Season.dc.html'), encoding='utf-8') as f:
        s = f.read()
    out = {}
    for label, key in (('Kale', 'kale'), ('Asparagus', 'asparagus'), ('Strawberries', 'strawberries')):
        m = re.search(r'<g aria-label="%s"><circle[^>]*></circle><g transform="[^"]*">(.*?)</g></g>' % label, s, re.S)
        out[key] = svg_of(m.group(1))
    return out


# id, english, dutch, german, french, glyph, colour, NW season, FR season, US season
# Season: "start-end/peakStart-peakEnd" in months 1-12 (a range may wrap the year); empty = as NW; "-" = not here.
FOODS = [
    ('asparagus', 'Asparagus', 'Asperges', 'Spargel', 'Asperges', 'art:asparagus', '6A9A2A', '4-6/5-6', '3-6/4-5', '4-6/5-5'),
    ('spinach', 'Spinach', 'Spinazie', 'Spinat', 'Épinards', 'leaf', '3F8A3A', '4-10/5-6', '3-11/4-6', '3-6/4-5'),
    ('lettuce', 'Lettuce', 'Sla', 'Kopfsalat', 'Laitue', 'leaf', '7DB04A', '4-10/6-8', '3-11/5-9', '4-10/5-9'),
    ('radishes', 'Radishes', 'Radijs', 'Radieschen', 'Radis', 'root', 'D93A5A', '4-10/5-6', '3-11/4-6', '4-6/5-5'),
    ('peas', 'Peas', 'Doperwten', 'Erbsen', 'Petits pois', 'pod', '6AAA3A', '6-8/6-7', '5-7/5-6', '5-7/5-6'),
    ('broadbeans', 'Broad beans', 'Tuinbonen', 'Dicke Bohnen', 'Fèves', 'pod', '5E9A4A', '6-8/6-7', '5-7/5-6', '5-6/5-6'),
    ('greenbeans', 'Green beans', 'Sperziebonen', 'Grüne Bohnen', 'Haricots verts', 'pod', '4E9A3A', '6-10/7-9', '6-10/7-9', '6-10/7-9'),
    ('courgette', 'Courgette', 'Courgette', 'Zucchini', 'Courgette', 'cuke', '4A8A3A', '6-10/7-9', '5-10/6-9', '6-10/7-9'),
    ('cucumber', 'Cucumber', 'Komkommer', 'Gurke', 'Concombre', 'cuke', '3E9A5A', '5-10/6-8', '5-10/6-9', '6-9/7-8'),
    ('tomatoes', 'Tomatoes', 'Tomaten', 'Tomaten', 'Tomates', 'round', 'D93A2A', '5-10/7-9', '5-10/7-9', '6-10/7-9'),
    ('peppers', 'Peppers', 'Paprika', 'Paprika', 'Poivrons', 'round', 'E0402A', '6-10/8-9', '6-10/7-9', '6-10/8-9'),
    ('aubergine', 'Aubergine', 'Aubergine', 'Aubergine', 'Aubergine', 'round', '5A2A6A', '7-10/8-9', '6-10/7-9', '7-10/8-9'),
    ('sweetcorn', 'Sweetcorn', 'Mais', 'Zuckermais', 'Maïs doux', 'corn', 'F2B91B', '8-10/8-9', '7-10/8-9', '7-9/7-8'),
    ('carrots', 'Carrots', 'Wortels', 'Karotten', 'Carottes', 'root', 'E8762C', '6-2/8-11', '5-2/7-11', '5-11/6-10'),
    ('beetroot', 'Beetroot', 'Rode bieten', 'Rote Bete', 'Betteraves', 'beet', '8A1F4A', '6-2/9-11', '6-2/9-12', '6-11/8-10'),
    ('newpotatoes', 'New potatoes', 'Nieuwe aardappelen', 'Frühkartoffeln', 'Pommes de terre nouvelles', 'potato', 'C9A25A', '5-8/6-7', '4-7/5-6', '5-8/6-7'),
    ('onions', 'Onions', 'Uien', 'Zwiebeln', 'Oignons', 'onion', 'C98A3A', '8-3/8-10', '7-3/8-10', '8-12/8-10'),
    ('leeks', 'Leeks', 'Prei', 'Lauch', 'Poireaux', 'stalk', '6AAA5A', '8-4/10-2', '8-4/10-2', '9-4/10-2'),
    ('garlic', 'Garlic', 'Knoflook', 'Knoblauch', 'Ail', 'onion', 'D9CDB0', '6-9/7-8', '6-9/7-8', '6-9/7-8'),
    ('cauliflower', 'Cauliflower', 'Bloemkool', 'Blumenkohl', 'Chou-fleur', 'cabbage', 'E6DCB4', '6-11/8-10', '5-12/8-11', '9-11/9-10'),
    ('broccoli', 'Broccoli', 'Broccoli', 'Brokkoli', 'Brocoli', 'cabbage', '3F8A4A', '6-11/8-10', '5-12/8-11', '9-11/9-10'),
    ('whitecabbage', 'White cabbage', 'Witte kool', 'Weißkohl', 'Chou blanc', 'cabbage', 'B8D09A', '8-3/10-12', '8-3/10-12', '8-12/9-11'),
    ('redcabbage', 'Red cabbage', 'Rode kool', 'Rotkohl', 'Chou rouge', 'cabbage', '8A2A6A', '8-3/10-1', '8-3/10-1', '8-12/9-11'),
    ('savoy', 'Savoy cabbage', 'Savooiekool', 'Wirsing', 'Chou de Milan', 'cabbage', '5A9A4A', '9-3/10-1', '9-3/10-1', '9-3/10-12'),
    ('kale', 'Kale', 'Boerenkool', 'Grünkohl', 'Chou kale', 'art:kale', '2F7A3A', '10-3/11-2', '10-3/11-2', '9-3/10-1'),
    ('sprouts', 'Brussels sprouts', 'Spruitjes', 'Rosenkohl', 'Choux de Bruxelles', 'cabbage', '5E9A3A', '10-3/11-1', '10-3/11-1', '9-3/10-12'),
    ('celeriac', 'Celeriac', 'Knolselderij', 'Sellerie', 'Céleri-rave', 'beet', 'C9B48E', '9-3/10-1', '9-3/10-1', '9-3/10-1'),
    ('parsnips', 'Parsnips', 'Pastinaak', 'Pastinaken', 'Panais', 'food.parsnip', 'EFE2C8', '10-3/11-2', '10-3/11-2', '10-4/11-2'),
    ('pumpkin', 'Pumpkin', 'Pompoen', 'Kürbis', 'Citrouille', 'food.pumpkin', 'E8762C', '9-11/10-10', '9-11/10-10', '9-11/10-10'),
    ('squash', 'Squash', 'Butternut', 'Butternut', 'Butternut', 'food.squash', 'D9A23A', '9-12/10-11', '9-12/10-11', '9-12/10-11'),
    ('mushrooms', 'Mushrooms', 'Paddenstoelen', 'Pilze', 'Champignons', 'food.mushroom', '8C6A4A', '8-11/9-10', '8-11/9-10', '8-11/9-10'),
    ('fennel', 'Fennel', 'Venkel', 'Fenchel', 'Fenouil', 'beet', 'C9D9A0', '6-10/8-9', '5-11/8-10', '9-4/10-2'),
    ('chicory', 'Chicory', 'Witlof', 'Chicorée', 'Endives', 'leaf', 'E6E0B0', '10-4/11-2', '10-4/11-2', '10-4/11-2'),
    ('rhubarb', 'Rhubarb', 'Rabarber', 'Rhabarber', 'Rhubarbe', 'stalk', 'C8325A', '3-6/4-5', '3-6/4-5', '3-6/4-5'),
    ('apples', 'Apples', 'Appels', 'Äpfel', 'Pommes', 'food.apple.2', 'D93A3A', '8-3/9-11', '8-3/9-11', '8-12/9-10'),
    ('pears', 'Pears', 'Peren', 'Birnen', 'Poires', 'food.pear', 'B8C84A', '8-2/9-11', '8-2/9-11', '8-12/9-10'),
    ('plums', 'Plums', 'Pruimen', 'Pflaumen', 'Prunes', 'food.plum', '7B3F8C', '7-9/8-8', '7-9/8-8', '7-9/8-8'),
    ('cherries', 'Cherries', 'Kersen', 'Kirschen', 'Cerises', 'stone', 'B8203A', '6-8/6-7', '5-8/6-7', '5-8/6-7'),
    ('strawberries', 'Strawberries', 'Aardbeien', 'Erdbeeren', 'Fraises', 'art:strawberries', 'E0405A', '5-8/6-7', '4-9/5-7', '5-7/5-6'),
    ('raspberries', 'Raspberries', 'Frambozen', 'Himbeeren', 'Framboises', 'berry', 'D8305A', '6-9/7-8', '6-9/7-8', '6-9/6-8'),
    ('blueberries', 'Blueberries', 'Bosbessen', 'Heidelbeeren', 'Myrtilles', 'berry', '3A4A9A', '7-9/7-8', '7-9/7-8', '6-9/7-8'),
    ('blackberries', 'Blackberries', 'Bramen', 'Brombeeren', 'Mûres', 'berry', '3A2A4A', '8-10/8-9', '8-10/8-9', '7-9/8-8'),
    ('redcurrants', 'Redcurrants', 'Rode bessen', 'Johannisbeeren', 'Groseilles', 'berry', 'D81F2A', '6-8/7-7', '6-8/7-7', '6-8/7-7'),
    ('gooseberries', 'Gooseberries', 'Kruisbessen', 'Stachelbeeren', 'Groseilles à maquereau', 'berry', '9AC85A', '6-8/6-7', '6-8/6-7', '6-7/6-7'),
    ('grapes', 'Grapes', 'Druiven', 'Trauben', 'Raisins', 'food.grapes', '7A4A9A', '8-11/9-10', '8-10/9-9', '8-10/9-9'),
    ('figs', 'Figs', 'Vijgen', 'Feigen', 'Figues', 'food.fig', '6A3A5A', '8-10/9-9', '8-10/9-9', '6-10/8-9'),
    ('peaches', 'Peaches', 'Perziken', 'Pfirsiche', 'Pêches', 'stone', 'F29A5A', '7-9/8-8', '6-9/7-8', '6-9/7-8'),
    ('apricots', 'Apricots', 'Abrikozen', 'Aprikosen', 'Abricots', 'stone', 'F2A73B', '7-8/7-8', '6-8/6-7', '5-8/6-7'),
    ('melon', 'Melon', 'Meloen', 'Melone', 'Melon', 'round', 'E8A23A', '7-9/8-8', '6-9/7-8', '6-9/7-8'),
    ('citrus', 'Citrus', 'Citrusvruchten', 'Zitrusfrüchte', 'Agrumes', 'food.citrus', 'F2A71B', '11-3/12-2', '11-3/12-2', '11-4/12-2'),
    ('quince', 'Quince', 'Kweepeer', 'Quitte', 'Coing', 'round', 'D8C23A', '10-11/10-10', '10-11/10-10', '10-11/10-10'),
    ('cranberries', 'Cranberries', 'Cranberry’s', 'Cranberrys', 'Canneberges', 'berry', 'B8203A', '10-12/11-11', '10-12/11-11', '10-12/10-11'),
    ('chestnuts', 'Chestnuts', 'Kastanjes', 'Esskastanien', 'Châtaignes', 'food.chestnut', '6B3A1E', '10-12/10-11', '10-12/10-11', '10-12/10-11'),
    ('hazelnuts', 'Hazelnuts', 'Hazelnoten', 'Haselnüsse', 'Noisettes', 'nut', '9A6A3A', '9-11/10-10', '9-11/10-10', '9-11/10-10'),
    ('walnuts', 'Walnuts', 'Walnoten', 'Walnüsse', 'Noix', 'nut', '8A6A4A', '9-11/10-10', '9-11/10-10', '9-11/10-10'),
    ('mussels', 'Mussels', 'Mosselen', 'Miesmuscheln', 'Moules', 'shell', '2A3A5A', '7-3/9-1', '7-3/9-1', '-'),
    ('oysters', 'Oysters', 'Oesters', 'Austern', 'Huîtres', 'shell', '8A8A7A', '9-4/10-3', '9-4/10-3', '9-4/10-3'),
    ('herring', 'New herring', 'Hollandse nieuwe', 'Matjes', 'Hareng nouveau', 'fish', '5A7ACA', '5-7/6-7', '-', '-'),
    ('samphire', 'Samphire', 'Zeekraal', 'Queller', 'Salicorne', 'stalk', '3F8A5A', '6-8/7-8', '6-8/7-8', '-'),
    ('wildgarlic', 'Wild garlic', 'Daslook', 'Bärlauch', 'Ail des ours', 'leaf', '4A9A4A', '3-5/4-4', '3-5/4-4', '4-5/4-5'),
]


def esc(s):
    return s.replace('&', '&amp;').replace("'", "\\'").replace('"', '\\"')


def main():
    entries = []
    icons = wheel_icons()
    for key, svg in icons.items():
        conv = B.Converter(svg).run()
        entries.append({'id': 'food.' + key, 'vb': ' '.join(B.fmt(v) for v in conv.vb), 'p': [p if p[2] else p[:2] for p in conv.paths]})
    for key, inner in GLYPHS.items():
        conv = B.Converter(svg_of(inner)).run()
        entries.append({'id': 'glyph.' + key, 'vb': ' '.join(B.fmt(v) for v in conv.vb), 'p': [p if p[2] else p[:2] for p in conv.paths]})
    os.makedirs(os.path.join(RES, 'raw'), exist_ok=True)
    with open(os.path.join(RES, 'raw', 'local_art.json'), 'w', encoding='utf-8') as f:
        json.dump({'source': 'Atlas boards (canvas/project) via places/tools/board_symbols.py; MIT', 'art': entries}, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    print('art', len(entries), 'drawings')

    rows = []
    for f in FOODS:
        key, en, nl, de, fr, glyph, colour, nw, fr_s, us = f
        glyph_id = glyph[4:] if glyph.startswith('art:') else glyph
        if glyph.startswith('art:'):
            glyph_id = 'food.' + glyph[4:]
        elif '.' not in glyph:
            glyph_id = 'glyph.' + glyph
        rows.append('%s|%s|%s|%s|%s|%s' % (key, glyph_id, colour, nw, fr_s, us))
    kt = '''package app.atlas.ui.local

/**
 * The foods of the seasonal calendar, generated by `art/local_art.py` (edit the table there). One line per food:
 * `id|glyph|colour|NW|FR|US`, each season `start-end/peakStart-peakEnd` in months (a range may wrap the year), empty
 * for "as NW Europe" and `-` for "not here". NW stands for the Netherlands, Germany, Great Britain and anywhere else
 * in the northern temperate belt; the names are in `strings_local_foods.xml` as `local_food_<id>`.
 */
internal object FoodData {
    const val TABLE = """
%s
"""
}
''' % '\n'.join(rows)
    with open(KT, 'w', encoding='utf-8') as f:
        f.write(kt)
    for lang, idx in (('values', 1), ('values-nl', 2), ('values-de', 3), ('values-fr', 4)):
        d = os.path.join(RES, lang)
        os.makedirs(d, exist_ok=True)
        lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
        for f in FOODS:
            lines.append('    <string name="local_food_%s">%s</string>' % (f[0], esc(f[idx])))
        lines.append('</resources>')
        with open(os.path.join(d, 'strings_local_foods.xml'), 'w', encoding='utf-8') as out:
            out.write('\n'.join(lines) + '\n')
    print('foods', len(FOODS))


if __name__ == '__main__':
    main()
