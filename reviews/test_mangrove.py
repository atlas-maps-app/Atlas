"""Offline tests for the Mangrove connection: a temporary reviewer key and a fake Mangrove. Nothing is sent.

python3 -m unittest -v reviews.test_mangrove   (from the project root)
"""
import base64
import io
import json
import os
import stat
import tempfile
import unittest
import urllib.parse

from reviews import mangrove


class FakeMangrove:
    def __init__(self, reply):
        self.reply, self.calls = reply, []

    def __call__(self, req, timeout=None):
        self.calls.append(req)
        r = io.BytesIO(json.dumps(self.reply).encode())
        r.status = 200
        return r


class Mangrove(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ['ATLAS_MANGROVE_HOME'] = self.tmp.name

    def tearDown(self):
        os.environ.pop('ATLAS_MANGROVE_HOME', None)
        self.tmp.cleanup()

    def test_places_are_named_like_mapcomplete_names_them(self):
        self.assertEqual(mangrove.subject('Shenanigans', 50.9335697, 6.9556738), 'geo:50.9335697,6.9556738?q=Shenanigans&u=30')
        self.assertEqual(mangrove.subject('Anne  Frank Huis', 52.3752, 4.88398), 'geo:52.3752,4.88398?q=Anne%20Frank%20Huis&u=30')
        self.assertEqual(mangrove.subject("Ali's Gemüsekebap", 48.217067, 16.359179), "geo:48.217067,16.359179?q=Ali's%20Gem%C3%BCsekebap&u=30")

    def test_stars(self):
        self.assertEqual([mangrove.rating(s) for s in (1, 3, 5)], [0, 50, 100])
        self.assertEqual(mangrove.stars(75), 4.0)
        with self.assertRaises(mangrove.MangroveError):
            mangrove.rating(6)

    def test_a_signed_review_verifies_and_a_changed_one_does_not(self):
        jwt, payload = mangrove.review('Anne Frank Huis', 52.3752, 4.88398, 5, 'Made-up review for the test.',
                                       osm_id='way/601081859', how='eaten_there')
        self.assertTrue(mangrove.verify(jwt))
        head, body, sig = jwt.split('.')
        changed = dict(payload, rating=0)
        forged = '.'.join([head, base64.urlsafe_b64encode(json.dumps(changed).encode()).rstrip(b'=').decode(), sig])
        self.assertFalse(mangrove.verify(forged))
        self.assertEqual(payload['metadata'], {'license': 'CC-BY-4.0', 'is_personal_experience': True,
                                               'osm_id': 'way/601081859', 'atlas_how': 'eaten_there'})
        h = json.loads(base64.urlsafe_b64decode(head + '=' * (-len(head) % 4)))
        self.assertEqual((h['alg'], h['typ']), ('ES256', 'JWT'))
        self.assertTrue(h['kid'].startswith('-----BEGIN PUBLIC KEY-----') and '\n' not in h['kid'])

    def test_the_key_is_kept_and_readable_only_by_you(self):
        path = mangrove.key_path()
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        first = mangrove.public_key()[0]
        self.assertEqual(mangrove.public_key()[0], first)  # same key, same reviewer

    def test_signature_conversion_round_trip(self):
        raw = bytes(range(1, 33)) + bytes([0x80] + [0] * 31)
        self.assertEqual(mangrove.der_to_raw(mangrove.raw_to_der(raw)), raw)

    def test_limits(self):
        with self.assertRaisesRegex(mangrove.MangroveError, '500'):
            mangrove.review('x', 0, 0, 3, 'a' * 501)
        with self.assertRaises(mangrove.MangroveError):
            mangrove.review('x', 0, 0, 3, how='flown_in')

    def test_reading(self):
        reply = {'reviews': [{'signature': 's1', 'payload': {'iat': 1790000000, 'sub': 'geo:52.3752,4.88398?q=Anne%20Frank%20Huis&u=30',
                                                             'rating': 75, 'opinion': 'Made up.', 'metadata': {'nickname': 'tester'}}}]}
        g = FakeMangrove(reply)
        got = mangrove.reviews_for('Anne Frank Huis', 52.3752, 4.88398, opener=g)
        self.assertEqual((got[0]['stars'], got[0]['author'], got[0]['credit'], got[0]['license']), (4.0, 'tester', 'Mangrove', 'CC-BY-4.0'))
        q = urllib.parse.parse_qs(urllib.parse.urlparse(g.calls[0].full_url).query)
        self.assertEqual(q['sub'], ['geo:52.3752,4.88398?q=Anne%20Frank%20Huis&u=30'])

    def test_sending_puts_the_jwt(self):
        g = FakeMangrove(None)
        jwt, _ = mangrove.review('Test place', 0, 0, 4)
        self.assertTrue(mangrove.submit(jwt, opener=g))
        self.assertEqual(g.calls[0].get_method(), 'PUT')
        self.assertTrue(g.calls[0].full_url.endswith('/submit/' + jwt))


if __name__ == '__main__':
    unittest.main()
