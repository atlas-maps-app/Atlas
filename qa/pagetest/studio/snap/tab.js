/* a screenshot of one tab (shots.sh fills in __TAB__, __OUT__) at the runner's width; the page as tall as it is, up to 3200 px */
await ready(); await tabTo('__TAB__'); await sleep(400);
window.scrollTo(0, 0);
return 'SNAP __OUT__ ' + Math.min(3200, document.documentElement.scrollHeight);
