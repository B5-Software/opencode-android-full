"""Verify actual-artifact pairing, offline notice delivery and safe manifest scope."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import maven_materials as maven
import npm_materials as npm
from notice_contract import check_notices

class NoticeTest(unittest.TestCase):
    def setUp(self):
        cache=maven.ROOT/'.cache';cache.mkdir(exist_ok=True)
        self.temporary=tempfile.TemporaryDirectory(prefix='dependency-notice-test-',dir=cache)
        self.work=Path(self.temporary.name).resolve();self.work.relative_to(cache.resolve())
    def tearDown(self):
        self.work.relative_to((maven.ROOT/'.cache').resolve());self.temporary.cleanup()
    def fixture(self):
        binary=self.work/'library.jar'
        with zipfile.ZipFile(binary,'w') as archive:archive.writestr('META-INF/NOTICE.txt',b'Original binary notice\n')
        source=io.BytesIO()
        with zipfile.ZipFile(source,'w') as archive:
            archive.writestr('LICENSE',b'Original source license\n')
            archive.writestr('Fixture.java',b'/* Copyright Fixture Authors. Licensed under a fixture license. */\npackage fixture;')
        pom=b'<project><licenses><license><name>Fixture license</name></license></licenses></project>'
        cache=self.work/'objects';cache.mkdir()
        def pin(data,name):
            checksum=hashlib.sha256(data).hexdigest();(cache/checksum).write_bytes(data)
            return {'url':'https://repo.maven.apache.org/maven2/fixture/'+name,'size':len(data),'sha256':checksum}
        record={'group':'fixture.group','name':'library','version':'1.0','extension':'jar','binarySha256':maven.digest(binary),'binarySize':binary.stat().st_size,'pom':pin(pom,'library.pom'),'sources':pin(source.getvalue(),'sources.jar')}
        inputs=[dict(group='fixture.group',name='library',version='1.0',extension='jar',sha256=record['binarySha256'],size=record['binarySize'],file=str(binary))]
        return {'format':1,'scope':'Fixture release artifacts','components':[record]},inputs,cache
    def test_sources_and_verbatim_attributions_pair_with_actual_binary(self):
        lock,inputs,cache=self.fixture();output=self.work/'delivery';maven.collect(lock,inputs,cache,output)
        report=json.loads((output/'maven-source-delivery.json').read_text())
        self.assertEqual(1,report['actualArtifactsVerified']);self.assertFalse(report['fullReleaseAuditComplete'])
        self.assertNotIn(str(self.work),json.dumps(report))
        with zipfile.ZipFile(output/'maven-license-texts.zip') as archive:
            self.assertEqual(b'Original source license\n',archive.read('fixture.group/library/1.0/LICENSE'))
            self.assertEqual(b'Original binary notice\n',archive.read('fixture.group/library/1.0/binary/META-INF/NOTICE.txt'))
            self.assertIn(b'Copyright Fixture Authors',archive.read('fixture.group/library/1.0/source-headers/Fixture.java.txt'))
        with zipfile.ZipFile(output/'maven-sources.zip') as archive:
            self.assertEqual((cache/lock['components'][0]['sources']['sha256']).read_bytes(),archive.read('fixture.group/library/1.0/sources.jar'))
    def test_changed_binary_or_ambiguous_graph_cannot_get_matching_notices(self):
        lock,inputs,cache=self.fixture();changed=copy.deepcopy(inputs);changed.append(dict(inputs[0],sha256='f'*64))
        with self.assertRaises(ValueError):maven.collect(lock,changed,cache,self.work/'ambiguous')
        Path(inputs[0]['file']).write_bytes(b'changed executable artifact')
        with self.assertRaises(ValueError):maven.collect(lock,inputs,cache,self.work/'changed')
        self.assertFalse((self.work/'changed').exists())
    def test_notice_archive_never_accepts_parent_paths_or_alternate_origins(self):
        for name in ['../escape','/absolute','bad\\windows']:
            with self.assertRaises(ValueError):maven.safe_member(name)
        with self.assertRaises(ValueError):maven.source_url('https://fixture.invalid/private')
    def test_embedded_metadata_requires_the_same_official_source_and_maven_graph(self):
        lock,inputs,cache=self.fixture();output=self.work/'delivery';maven.collect(lock,inputs,cache,output)
        npm_zip=io.BytesIO()
        with zipfile.ZipFile(npm_zip,'w') as archive:archive.writestr('npm-notice-inventory.json',json.dumps({'sourceCommit':'a'*40}))
        apk_data=io.BytesIO()
        with zipfile.ZipFile(apk_data,'w') as archive:
            archive.writestr('assets/licenses/dependencies/npm-license-texts.zip',npm_zip.getvalue())
            archive.writestr('assets/licenses/dependencies/maven-license-texts.zip',(output/'maven-license-texts.zip').read_bytes())
        with zipfile.ZipFile(apk_data) as apk:
            self.assertIn('npm',check_notices(apk,'a'*40,lock))
            with self.assertRaises(ValueError):check_notices(apk,'b'*40,lock)
            wrong=copy.deepcopy(lock);wrong['components'][0]['binarySha256']='e'*64
            with self.assertRaises(ValueError):check_notices(apk,'a'*40,wrong)
    def manifests(self,missing=False):
        root=self.work/'source'
        for folder,metadata in [('packages/cli',{'name':'@fixture/cli','version':'1','dependencies':{'required':'1'},'optionalDependencies':{'unavailable-optional':'1'},'peerDependencies':{'unavailable-peer':'1'}}),('packages/app',{'name':'@fixture/app','version':'1'}),('node_modules/required',{'name':'required','version':'1','license':'MIT'})]:
            if missing and folder=='node_modules/required':continue
            directory=root/folder;directory.mkdir(parents=True);(directory/'package.json').write_text(json.dumps(metadata),encoding='utf-8')
        return root.resolve()
    def test_manifest_scope_keeps_required_dependencies_and_resolved_optional_policy(self):
        root=self.manifests();self.assertEqual({'@fixture/cli','@fixture/app','required'},{m['name'] for _,m in npm.discover(root)})
    def test_missing_required_dependency_and_unsafe_name_are_rejected(self):
        root=self.manifests(missing=True)
        with self.assertRaises(ValueError):npm.discover(root)
        with self.assertRaises(ValueError):npm.resolve_dependency(root,'../private',root)
    def test_notice_text_and_readme_grant_are_preserved_verbatim(self):
        root=self.manifests();directory=root/'node_modules/required'
        text=b'Copyright Fixture Authors\nPermission is hereby granted to fixture users.\n'
        (directory/'README.md').write_bytes(text)
        self.assertEqual([('README.md',text)],npm.license_files(directory,root))
        (directory/'LICENSE').write_bytes(text)
        self.assertEqual([('LICENSE',text)],npm.license_files(directory,root))

if __name__=='__main__':unittest.main()
