"""Mangrove reviews from the command line.

  python3 -m reviews read "Anne Frank Huis" 52.37520 4.88398
  python3 -m reviews write "Anne Frank Huis" 52.37520 4.88398 --stars 5 --opinion "…" [--osm way/601081859] [--how eaten_there]
  python3 -m reviews write … --send        (publishes it: public under CC BY 4.0 as soon as it is sent)
"""
import argparse
import json
import sys

from . import for_place, mangrove


def main(argv=None):
    a = argparse.ArgumentParser(prog='reviews', description='Read and write Mangrove reviews for a mapped place.')
    a.add_argument('action', choices=['read', 'write'])
    a.add_argument('name')
    a.add_argument('lat', type=float)
    a.add_argument('lon', type=float)
    a.add_argument('--stars', type=float, help='1 to 5 (write)')
    a.add_argument('--opinion', help='at most 500 characters (write)')
    a.add_argument('--osm', help='the OpenStreetMap id, e.g. way/601081859')
    a.add_argument('--how', choices=['eaten_there', 'takeaway', 'delivered'], help='for food and drink (write)')
    a.add_argument('--nickname', help='shown with the review (write)')
    a.add_argument('--send', action='store_true', help='publish the review; without it, only show what would be sent')
    o = a.parse_args(argv)
    try:
        if o.action == 'read':
            print(json.dumps(for_place(o.name, o.lat, o.lon, osm_id=o.osm), ensure_ascii=False, indent=1))
            return 0
        if o.stars is None:
            a.error('write needs --stars')
        jwt, payload = mangrove.review(o.name, o.lat, o.lon, o.stars, o.opinion, osm_id=o.osm, how=o.how, nickname=o.nickname)
        print(json.dumps(payload, ensure_ascii=False, indent=1))
        if not o.send:
            print('Signed, not sent. Add --send to publish it.', file=sys.stderr)
            return 0
        mangrove.submit(jwt)
        print('Published on Mangrove.', file=sys.stderr)
        return 0
    except mangrove.MangroveError as e:
        print(e, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
