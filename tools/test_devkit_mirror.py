"""Preserve the authenticated envelope when a snapshot URL serves a newer one."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import devkit_mirror as mirror

class PinnedMetadataTest(unittest.TestCase):
    def setUp(self):
        cache=mirror.ROOT/'.cache';cache.mkdir(exist_ok=True)
        self.temporary=tempfile.TemporaryDirectory(prefix='mirror-metadata-test-',dir=cache)
        self.root=Path(self.temporary.name).resolve();self.root.relative_to(cache.resolve())
        self.index=b'fixture package index';self.index_hash=hashlib.sha256(self.index).hexdigest()
        self.release=('Fixture envelope\r\nSHA256:\r\n '+self.index_hash+' '+str(len(self.index))+' main/binary-arm64/Packages.xz\r\n').encode()
        self.pin=self.root/'runtime/apt-metadata/debian/trixie/InRelease';self.pin.parent.mkdir(parents=True);self.pin.write_bytes(self.release)
        snapshot='20261027T000000Z'
        self.profile={'snapshot':snapshot}
        lock={'debian/trixie':{'snapshot':snapshot,'url':'https://snapshot.debian.org/archive/debian/'+snapshot,'inReleaseSha256':hashlib.sha256(self.release).hexdigest(),'packagesSha256':self.index_hash,'size':len(self.index)}}
        (self.root/'runtime/developer-source-lock.json').write_text(json.dumps(lock),encoding='utf-8')
        self.cache=self.root/'mirror';path=self.cache/'debian/dists/trixie/main/binary-arm64/Packages.xz';path.parent.mkdir(parents=True);path.write_bytes(self.index)
    def tearDown(self):
        self.root.relative_to((mirror.ROOT/'.cache').resolve());self.temporary.cleanup()
    def run_indexes(self):
        with mock.patch.multiple(mirror,ROOT=self.root,MIRROR=self.cache,PROFILE=self.profile,SOURCES=[('debian','trixie')]),mock.patch.object(mirror.urllib.request,'urlopen',side_effect=AssertionError('Immutable envelope must not be re-fetched')):
            mirror.indexes()
    def test_retains_exact_signed_bytes_without_refetching(self):
        self.run_indexes();self.assertEqual(self.release,(self.cache/'debian/dists/trixie/InRelease').read_bytes())
    def test_changed_vendored_envelope_is_rejected(self):
        self.pin.write_bytes(self.release.replace(b'Fixture',b'Changed'))
        with self.assertRaises(ValueError):self.run_indexes()

if __name__=='__main__':unittest.main()
