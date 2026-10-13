/* the Screens tab with the first category's ⋯ and its first card's ⋯ open */
await ready(); await tabTo('screens'); await sleep(200);
Q('#pages .page[data-page] .page-h .more-btn').click(); await sleep(200);
Q('#pages .shot .more-btn').click(); await sleep(300);
window.scrollTo(0, 0);
return 'SNAP __OUT__ 1700';
