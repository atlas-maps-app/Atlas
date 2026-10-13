"""Reviews for a place: Mangrove's open reviews by default, Google's beside them when you switch Google on.

Atlas's own reviews are Mangrove reviews (mangrove.review() and mangrove.submit()), so they are part of
the open pool too. Every review keeps its source, author and licence for the credit line.
"""
import os
import sys

from . import mangrove
from .mangrove import MangroveError

HERE = os.path.dirname(os.path.abspath(__file__))


def for_place(name, lat, lon, osm_id=None, address=None, google=True):
    """{'mangrove': {'summary', 'reviews'} or None, 'google': Google's listing or None}.

    Mangrove first, always. Google only when ATLAS_GOOGLE is set (see gen/google_optional.py);
    either can be None when it has nothing or can't be reached, and the screen shows the other.
    """
    out = {'mangrove': None, 'google': None}
    try:
        out['mangrove'] = {'summary': mangrove.summary(name, lat, lon), 'reviews': mangrove.reviews_for(name, lat, lon)}
    except MangroveError as e:
        print('mangrove: %s' % e, file=sys.stderr)
    if google:
        sys.path.insert(0, os.path.join(HERE, '..', 'gen'))
        from google_optional import google_for
        out['google'] = google_for(name, lat, lon, osm_id=osm_id, address=address, reviews=True, quiet=True)
    return out
