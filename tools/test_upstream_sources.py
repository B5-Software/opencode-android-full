"""Checks source identity and byte integrity without running acquired code."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import tempfile
import tarfile
import unittest
import upstream_sources as sources

class UpstreamSourceTest(unittest.TestCase):
    def setUp(self):
        cache=sources.ROOT/'.cache';cache.mkdir(exist_ok=True)
        self.temporary=tempfile.TemporaryDirectory(prefix='upstream-source-test-',dir=cache)
        self.work=Path(self.temporary.name).resolve();self.work.relative_to(cache.resolve())
    def tearDown(self):
        self.work.relative_to((sources.ROOT/'.cache').resolve());self.temporary.cleanup()
    def test_archive_origins_names_and_sizes_are_explicit(self):
        good={'name':'fixture','repository':'owner/repo','commit':'a'*40,'file':'fixture-source.tar.gz','url':'https://codeload.github.com/owner/repo/tar.gz/'+'a'*40,'sha256':'b'*64,'size':12}
        sources.validate(good)
        for change in [{'file':'../escape'},{'url':'https://fixture.invalid/source'},{'size':True},{'commit':'main'}]:
            with self.assertRaises(ValueError):sources.validate(dict(good,**change))
    def test_git_tar_pin_is_independent_of_gzip_compression(self):
        data=b'original source bytes\n'*100
        record={'tarSize':len(data),'tarSha256':hashlib.sha256(data).hexdigest()}
        path=self.work/'source.gz'
        for level in [0,1,9]:
            path.write_bytes(gzip.compress(data,compresslevel=level,mtime=0));sources.verify_git_archive(path,record)
        path.write_bytes(gzip.compress(data+b'changed',mtime=0))
        with self.assertRaises(ValueError):sources.verify_git_archive(path,record)
    def test_bun_dependency_commits_are_read_from_the_acquired_source(self):
        lock={'bunVersion':'1.4.4','sources':[{'name':'bun-tinycc','commit':'c'*40}],'gitSources':[{'name':'bun-webkit','commit':'d'*40}]}
        path=self.work/'bun.tar.gz'
        def archive(version):
            with tarfile.open(path,'w:gz') as tar:
                files={'package.json':json.dumps({'version':version}),'scripts/build/deps/webkit.ts':'export const WEBKIT_VERSION = "'+'d'*40+'";','scripts/build/deps/tinycc.ts':'const TINYCC_COMMIT = "'+'c'*40+'";'}
                for name,text in files.items():
                    data=text.encode();entry=tarfile.TarInfo('bun-source/'+name);entry.size=len(data);tar.addfile(entry,io.BytesIO(data))
        archive('1.4.4');sources.verify_bun_source(path,lock)
        lock['gitSources'][0]['commit']='e'*40
        with self.assertRaises(ValueError):sources.verify_bun_source(path,lock)
        lock['gitSources'][0]['commit']='d'*40;archive('9.9.9')
        with self.assertRaises(ValueError):sources.verify_bun_source(path,lock)

if __name__=='__main__':unittest.main()
