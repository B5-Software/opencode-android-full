"""Checks source identity and byte integrity without running acquired code."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import subprocess
import tarfile
import unittest
import upstream_sources as sources

class UpstreamSourceTest(unittest.TestCase):
    def setUp(self):
        cache=sources.ROOT/'.cache';cache.mkdir(exist_ok=True)
        self.temporary=tempfile.TemporaryDirectory(prefix='upstream-source-test-',dir=cache,ignore_cleanup_errors=True)
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

    def test_git_export_ignores_host_eol_attributes_and_permissions(self):
        repo=self.work/'repo';repo.mkdir()
        environment=sources.source_git_environment()
        def git(*args,**kwargs):return subprocess.run(sources.source_git_command(repo,*args),env=environment,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs).stdout
        git('init')
        data=b'original source\nsecond line\n';(repo/'LICENSE.txt').write_bytes(data)
        git('add','LICENSE.txt');git('-c','user.name=Source fixture','-c','user.email=fixture@localhost','-c','commit.gpgsign=false','commit','-m','Fixture')
        baseline=git('archive','--format=tar','HEAD')
        hostile_attributes=self.work/'host.attributes';hostile_attributes.write_text('*.txt export-ignore\n',encoding='utf-8')
        for name,value in [('core.autocrlf','true'),('core.eol','crlf'),('core.attributesFile',str(hostile_attributes)),('tar.umask','0077')]:git('config',name,value)
        candidate=git('archive','--format=tar','HEAD')
        self.assertEqual(baseline,candidate)
        with tarfile.open(fileobj=io.BytesIO(candidate)) as tar:
            self.assertEqual(data,tar.extractfile('LICENSE.txt').read())
            self.assertEqual(0o664,tar.getmember('LICENSE.txt').mode)

    def test_inherited_git_configuration_is_removed(self):
        environment=sources.source_git_environment({'PATH':'fixture','GIT_CONFIG_COUNT':'1','GIT_CONFIG_KEY_0':'core.autocrlf','GIT_CONFIG_VALUE_0':'true','GIT_CONFIG_PARAMETERS':'fixture','GIT_DIR':'private-repo'})
        self.assertEqual('fixture',environment['PATH'])
        self.assertEqual(os.devnull,environment['GIT_CONFIG_GLOBAL'])
        self.assertFalse(any(name in environment for name in ['GIT_CONFIG_COUNT','GIT_CONFIG_KEY_0','GIT_CONFIG_VALUE_0','GIT_CONFIG_PARAMETERS','GIT_DIR']))

if __name__=='__main__':unittest.main()
