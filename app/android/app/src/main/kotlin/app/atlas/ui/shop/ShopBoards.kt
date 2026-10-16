package app.atlas.ui.shop

/**
 * Inline SVG taken whole from the shop boards (`canvas/project/<Board>.dc.html`), for the objects and icons the shared
 * symbol catalogue does not hold. Each constant says its board and the number of the `<svg>` in it. Generated; drawn by [BoardArt].
 */
internal object ShopBoards {
    /** Pantry #0. */
    const val PANTRY_MAP = """<svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round"><path d="M3 6.5l6-2.5 6 2.5 6-2.5v13.5l-6 2.5-6-2.5-6 2.5z"></path><path d="M9 4v13.5M15 6.5V20"></path></svg>"""
    /** Pantry #2. */
    const val SPARKLE = """<svg width="18" height="18" viewBox="0 0 24 24" fill="#7B4DFF"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"></path></svg>"""
    /** Pantry #16. */
    const val CART = """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 5h2l2.5 11h11l2-8H7"></path><circle cx="9" cy="20" r="1.4"></circle><circle cx="17" cy="20" r="1.4"></circle></svg>"""
    /** Pantry #17. */
    const val TAG = """<svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round"><path d="M3 12V4h8l10 10-8 8z"></path><circle cx="7.5" cy="8.5" r="1.5"></circle></svg>"""
    /** Errands #1. */
    const val KEY_RING = """<svg style="position: absolute; left: 136px; top: 104px" width="140" height="60" viewBox="0 0 140 60" aria-hidden="true"><defs><linearGradient id="ring" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F3F4F6"></stop><stop offset=".5" stop-color="#8E949C"></stop><stop offset="1" stop-color="#D9DDE2"></stop></linearGradient></defs><ellipse cx="70" cy="34" rx="62" ry="22" fill="none" stroke="url(#ring)" stroke-width="6"></ellipse></svg>"""
    /** Help #2. */
    const val FRIDGE = """<svg style="position: absolute; left: 116px; top: 96px" width="180" height="230" viewBox="0 0 180 230" role="img" aria-label="A community fridge painted with flowers">
<ellipse cx="90" cy="224" rx="80" ry="6" fill="#000" opacity=".08"></ellipse>
<rect x="20" y="6" width="140" height="214" rx="14" fill="#7FB8A4"></rect>
<rect x="20" y="6" width="140" height="70" rx="14" fill="#8CC4B0"></rect>
<path d="M20 76h140" stroke="#5E9985" stroke-width="3"></path>
<rect x="140" y="30" width="7" height="30" rx="3.5" fill="#E9EEF0"></rect>
<rect x="140" y="96" width="7" height="50" rx="3.5" fill="#E9EEF0"></rect>
<g><circle cx="52" cy="40" r="12" fill="#F2A71B"></circle><circle cx="52" cy="40" r="5" fill="#E8622C"></circle><circle cx="86" cy="30" r="9" fill="#F59CB5"></circle><circle cx="86" cy="30" r="3.5" fill="#FFF3E6"></circle><path d="M40 60c10-10 30-10 40 0" stroke="#3E7A3A" stroke-width="3" fill="none" stroke-linecap="round"></path></g>
<g><path d="M44 200c0-40 8-70 30-90" stroke="#3E7A3A" stroke-width="3" fill="none" stroke-linecap="round"></path><ellipse cx="60" cy="150" rx="12" ry="5" fill="#3E7A3A" transform="rotate(-40 60 150)"></ellipse><ellipse cx="52" cy="176" rx="12" ry="5" fill="#3E7A3A" transform="rotate(30 52 176)"></ellipse><circle cx="76" cy="108" r="14" fill="#E8622C"></circle><circle cx="76" cy="108" r="6" fill="#F2C94C"></circle><circle cx="110" cy="150" r="11" fill="#F2C94C"></circle><circle cx="110" cy="150" r="4.5" fill="#E8622C"></circle><path d="M110 162c0 16-4 30-12 40" stroke="#3E7A3A" stroke-width="3" fill="none" stroke-linecap="round"></path></g>
<path d="M90 188c-8-6-12-10-12-15a6 6 0 0 1 12-2 6 6 0 0 1 12 2c0 5-4 9-12 15z" fill="#FBF6EE"></path>
<rect x="30" y="206" width="16" height="10" rx="2" fill="#5E9985"></rect><rect x="134" y="206" width="16" height="10" rx="2" fill="#5E9985"></rect>
</svg>"""
    /** Toilet #3. */
    const val COMPASS_TOILET = """<svg width="340" height="340" viewBox="0 0 340 340">
<defs>
<linearGradient id="steel" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF"></stop><stop offset=".45" stop-color="#B9BEC9"></stop><stop offset="1" stop-color="#E9EBF0"></stop></linearGradient>
<radialGradient id="enamel" cx=".42" cy=".38" r=".7"><stop offset="0" stop-color="#FFFFFF"></stop><stop offset="1" stop-color="#E7E3F6"></stop></radialGradient>
</defs>
<circle cx="170" cy="174" r="164" fill="#1B1440" opacity=".12"></circle>
<circle cx="170" cy="170" r="164" fill="url(#steel)"></circle>
<circle cx="170" cy="170" r="148" fill="url(#enamel)" stroke="#A9A2CC" stroke-width="1.5"></circle>
<g transform="rotate(-20 170 170)">
<circle cx="170" cy="170" r="134" fill="none" stroke="#8E86B8" stroke-width="7" stroke-dasharray="1.2 13.83"></circle>
<text x="170" y="56" text-anchor="middle" font-size="20" font-weight="800" fill="#C8322A" font-family="Geist, sans-serif">N</text>
<text x="290" y="177" text-anchor="middle" font-size="16" font-weight="700" fill="#6E6694" font-family="Geist, sans-serif">E</text>
<text x="170" y="298" text-anchor="middle" font-size="16" font-weight="700" fill="#6E6694" font-family="Geist, sans-serif">S</text>
<text x="50" y="177" text-anchor="middle" font-size="16" font-weight="700" fill="#6E6694" font-family="Geist, sans-serif">W</text>
</g>
<g transform="rotate(-28 170 170)">
<path d="M170 44L214 186 178 170V262h-16V170L126 186z" fill="#1B1440" opacity=".18" transform="translate(4 6)"></path>
<path d="M170 44L214 186 178 170V262h-16V170L126 186z" fill="#4B34B8"></path>
<path d="M170 44L214 186 178 170V96z" fill="#6E58E0"></path>
</g>
<circle cx="170" cy="170" r="16" fill="url(#steel)" stroke="#8E86B8" stroke-width="1.5"></circle>
</svg>"""
    /** Water #3. */
    const val COMPASS_WATER = """<svg width="340" height="340" viewBox="0 0 340 340">
<defs>
<linearGradient id="steelw" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF"></stop><stop offset=".45" stop-color="#B7C3CF"></stop><stop offset="1" stop-color="#E8EEF3"></stop></linearGradient>
<radialGradient id="enamelw" cx=".42" cy=".38" r=".7"><stop offset="0" stop-color="#FFFFFF"></stop><stop offset="1" stop-color="#DDEBF7"></stop></radialGradient>
</defs>
<circle cx="170" cy="174" r="164" fill="#0E2A44" opacity=".12"></circle>
<circle cx="170" cy="170" r="164" fill="url(#steelw)"></circle>
<circle cx="170" cy="170" r="148" fill="url(#enamelw)" stroke="#9DB6CC" stroke-width="1.5"></circle>
<g transform="rotate(-20 170 170)">
<circle cx="170" cy="170" r="134" fill="none" stroke="#7F9DB8" stroke-width="7" stroke-dasharray="1.2 13.83"></circle>
<text x="170" y="56" text-anchor="middle" font-size="20" font-weight="800" fill="#C8322A" font-family="Geist, sans-serif">N</text>
<text x="290" y="177" text-anchor="middle" font-size="16" font-weight="700" fill="#5F7A93" font-family="Geist, sans-serif">E</text>
<text x="170" y="298" text-anchor="middle" font-size="16" font-weight="700" fill="#5F7A93" font-family="Geist, sans-serif">S</text>
<text x="50" y="177" text-anchor="middle" font-size="16" font-weight="700" fill="#5F7A93" font-family="Geist, sans-serif">W</text>
</g>
<g transform="rotate(32 170 170)">
<path d="M170 44L214 186 178 170V262h-16V170L126 186z" fill="#0E2A44" opacity=".18" transform="translate(4 6)"></path>
<path d="M170 44L214 186 178 170V262h-16V170L126 186z" fill="#1560A8"></path>
<path d="M170 44L214 186 178 170V96z" fill="#3E86D0"></path>
</g>
<circle cx="170" cy="170" r="16" fill="url(#steelw)" stroke="#7F9DB8" stroke-width="1.5"></circle>
</svg>"""
    /** Food #2. */
    const val COMPASS_FOOD = """<svg width="300" height="300" viewBox="0 0 340 340">
<defs>
<linearGradient id="steelf" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF"></stop><stop offset=".45" stop-color="#CDBEB3"></stop><stop offset="1" stop-color="#F1E9E2"></stop></linearGradient>
<radialGradient id="enamelf" cx=".42" cy=".38" r=".7"><stop offset="0" stop-color="#FFFFFF"></stop><stop offset="1" stop-color="#F6E3D6"></stop></radialGradient>
</defs>
<circle cx="170" cy="174" r="164" fill="#3A1A08" opacity=".12"></circle>
<circle cx="170" cy="170" r="164" fill="url(#steelf)"></circle>
<circle cx="170" cy="170" r="148" fill="url(#enamelf)" stroke="#CFB09C" stroke-width="1.5"></circle>
<g transform="rotate(-20 170 170)">
<circle cx="170" cy="170" r="134" fill="none" stroke="#B58E75" stroke-width="7" stroke-dasharray="1.2 13.83"></circle>
<text x="170" y="56" text-anchor="middle" font-size="20" font-weight="800" fill="#C8322A" font-family="Geist, sans-serif">N</text>
</g>
<g transform="rotate(-120 170 170)">
<path d="M170 44L214 186 178 170V262h-16V170L126 186z" fill="#3A1A08" opacity=".18" transform="translate(4 6)"></path>
<path d="M170 44L214 186 178 170V262h-16V170L126 186z" fill="#A8420E"></path>
<path d="M170 44L214 186 178 170V96z" fill="#D9682E"></path>
</g>
<circle cx="170" cy="170" r="16" fill="url(#steelf)" stroke="#B58E75" stroke-width="1.5"></circle>
</svg>"""
    /** Water #4. */
    const val FOUNTAIN = """<svg width="56" height="76" viewBox="0 0 56 76" role="img" aria-label="Drinking fountain with bottle filler"><rect x="16" y="30" width="24" height="44" rx="3" fill="#5F6F7E"></rect><rect x="6" y="24" width="44" height="12" rx="6" fill="#7F8F9E"></rect><path d="M28 24c0-8 6-10 10-8" stroke="#8FB9E0" stroke-width="2" fill="none" stroke-linecap="round"></path><rect x="36" y="2" width="16" height="22" rx="3" fill="#3E4A56"></rect><path d="M44 24v6" stroke="#8FB9E0" stroke-width="3" stroke-linecap="round"></path></svg>"""
    /** Stroll #1. */
    const val SIGN = """<svg style="position: absolute; left: 180px; top: 186px" width="70" height="80" viewBox="0 0 70 80" aria-hidden="true"><path d="M0 8h56" stroke="#1A1A1A" stroke-width="3"></path><path d="M2 8c0 8 8 10 14 6" stroke="#1A1A1A" stroke-width="2" fill="none"></path><path d="M20 8v8M48 8v8" stroke="#1A1A1A" stroke-width="1.2"></path><rect x="12" y="16" width="44" height="46" rx="4" fill="#2F5D3A" stroke="#C9A45C" stroke-width="2"></rect><path d="M34 30c-5-3-10-3-14-1v18c4-2 9-2 14 1 5-3 10-3 14-1V29c-4-2-9-2-14 1z" fill="#F4EBDD"></path></svg>"""
}
