"""Source acquisition integrity and usable, deterministic offline delivery."""
import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import debian_sources as sources

ROOT=Path(__file__).resolve().parents[1]

def sha(data):return hashlib.sha256(data).hexdigest()

class SourceTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True)
        self.work=Path(tempfile.mkdtemp(prefix='debian-sources-test-',dir=ROOT/'.cache')).resolve()
        self.work.relative_to((ROOT/'.cache').resolve())
    def tearDown(self):
        self.work.relative_to((ROOT/'.cache').resolve());shutil.rmtree(self.work)
    def fixture(self):
        objects=self.work/'objects';objects.mkdir()
        orig=b'upstream fixture\n';patch=b'Debian build rules and patch fixture\n'
        dsc=(f'Format: 3.0 (quilt)\nSource: fixture\nVersion: 1:2.3-1\nChecksums-Sha256:\n {sha(orig)} {len(orig)} fixture_2.3.orig.tar.xz\n {sha(patch)} {len(patch)} fixture_2.3-1.debian.tar.xz\n').encode()
        files=[];graph={}
        for name,data in [('fixture_2.3.orig.tar.xz',orig),('fixture_2.3-1.debian.tar.xz',patch),('fixture_2.3-1.dsc',dsc)]:
            checksum=sha(data);(objects/checksum).write_bytes(data);path='pool/main/f/fixture/'+name
            files.append({'name':name,'size':len(data),'sha256':checksum,'poolPath':path})
            graph[checksum]={'size':len(data),'locations':[{'archive':'debian','poolPath':path}]}
        package={'name':'fixture','version':'1:2.3-1','index':'debian/trixie','binarySpdxIds':['SPDXRef-Debian-'+'a'*24],'files':files}
        inventory=self.work/'inventory';inventory.mkdir()
        (inventory/'debian-source-requests.json').write_text('[{"fixture":true}]',encoding='utf-8')
        (inventory/'debian-inventory-coverage.json').write_text(json.dumps({'rootfsSha256':'b'*64}),encoding='utf-8')
        ring=self.work/'ring.pgp';ring.write_bytes(b'fixture public keys')
        cache=self.work/'indexes';directory=cache/'debian/dists/trixie';(directory/'main/source').mkdir(parents=True)
        (directory/'InRelease').write_bytes(b'fixture signature envelope');(directory/sources.INDEX_PATH).write_bytes(b'fixture Sources.xz')
        plan={'format':1,'rootfsSha256':'b'*64,'requestsSha256':sources.digest(inventory/'debian-source-requests.json'),'archiveKeyringSha256':sources.digest(ring),'indexes':[{'name':'debian/trixie','snapshot':'20261027T000000Z','url':'https://snapshot.debian.org/archive/debian/20261027T000000Z','inReleaseSha256':sources.digest(directory/'InRelease'),'sourcesSha256':sources.digest(directory/sources.INDEX_PATH),'sourcesSize':(directory/sources.INDEX_PATH).stat().st_size}],'sourcePackages':[package],'objects':dict(sorted(graph.items())),'totalBytes':sum(item['size'] for item in graph.values()),'fullReleaseAuditComplete':False}
        return plan,objects,inventory,ring,cache
    def test_release_uses_only_sha256_and_the_expected_codename(self):
        text='Codename: trixie\nMD5Sum:\n '+'a'*32+' 4 main/source/Sources.xz\nSHA256:\n '+'b'*64+' 12 main/source/Sources.xz\nSHA512:\n '+'c'*128+' 12 main/source/Sources.xz\n'
        self.assertEqual(('b'*64,12),sources.release_index(text,'trixie'))
        for bad in [text.replace('Codename: trixie','Codename: sid'),text.replace('SHA256:','SHA256x:'),text+'SHA256:\n '+'b'*64+' 12 main/source/Sources.xz\n']:
            with self.assertRaises(ValueError):sources.release_index(bad,'trixie')
    def test_unsafe_paths_redirects_and_duplicate_files_are_rejected(self):
        for path in ['../escape','/absolute','pool/../escape','pool//duplicate','pool/./dot','pool\\windows','pool/a?query','pool/a#fragment']:
            with self.subTest(path=path),self.assertRaises(ValueError):sources.relative(path)
        for url in ['http://snapshot.debian.org/a','https://127.0.0.1/a','https://user:secret@snapshot.debian.org/a','https://snapshot.debian.org:444/a']:
            with self.assertRaises(ValueError):sources.validate_url(url)
        record={'Directory':'pool/main/f/fixture','Checksums-Sha256':'a'*64+' 2 f.dsc\n'+'b'*64+' 3 f.orig.tar.xz'}
        self.assertEqual(2,len(sources.source_files(record)))
        for bad in [dict(record,Directory='../escape'),dict(record,**{'Checksums-Sha256':record['Checksums-Sha256']+'\n'+'a'*64+' 2 f.dsc'})]:
            with self.assertRaises(ValueError):sources.source_files(bad)
    def test_lock_associations_sizes_and_pool_origins_cannot_drift(self):
        plan,*_=self.fixture();sources.validate_plan(plan)
        variants=[]
        bad=copy.deepcopy(plan);bad['totalBytes']+=1;variants.append(bad)
        bad=copy.deepcopy(plan);next(iter(bad['objects'].values()))['size']=True;variants.append(bad)
        bad=copy.deepcopy(plan);bad['sourcePackages'][0]['files'][0]['sha256']='f'*64;variants.append(bad)
        bad=copy.deepcopy(plan);bad['indexes'][0]['url']='https://fixture.invalid/';variants.append(bad)
        bad=copy.deepcopy(plan);bad['sourcePackages'].append(bad['sourcePackages'][0]);variants.append(bad)
        for bad in variants:
            with self.assertRaises(ValueError):sources.validate_plan(bad)
    def test_rebuild_retains_actual_rootfs_binding_but_cannot_change_sources(self):
        expected,*_=self.fixture();actual=copy.deepcopy(expected);actual['rootfsSha256']='c'*64
        sources.compare_source_graph(actual,expected)
        self.assertEqual('c'*64,actual['rootfsSha256'])
        actual['sourcePackages'][0]['version']='1:2.3-2'
        with self.assertRaises(ValueError):sources.compare_source_graph(actual,expected)
    def test_complete_request_file_hash_remains_enforced(self):
        expected,*_=self.fixture();actual=copy.deepcopy(expected);actual['requestsSha256']='e'*64
        with self.assertRaisesRegex(ValueError,'requestsSha256'):sources.compare_source_graph(actual,expected)
    def test_source_plan_json_bytes_use_utf8_and_lf(self):
        path=self.work/'source-plan.json';value={'name':'fixture 作者','lines':['one','two']}
        sources.write_json(path,value)
        self.assertEqual((json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf-8'),path.read_bytes())
    def test_download_checks_before_publishing_and_rejects_a_changed_cache(self):
        data=b'fixture source';target=self.work/sha(data)
        with mock.patch.object(sources,'build_opener') as opener:
            opener.return_value.open.return_value=io.BytesIO(data)
            sources.checked_download(['https://deb.debian.org/debian/pool/source'],target,sha(data),len(data))
        self.assertEqual(data,target.read_bytes());self.assertEqual('cache',sources.checked_download([],target,sha(data),len(data)))
        target.write_bytes(b'changed')
        with self.assertRaises(ValueError):sources.checked_download([],target,sha(data),len(data))
        for payload in [data+b'extra',data[:-1],b'x'*len(data)]:
            destination=self.work/('other-'+sha(data))
            with mock.patch.object(sources,'build_opener') as opener:
                opener.return_value.open.return_value=io.BytesIO(payload)
                with self.assertRaises(ValueError):sources.checked_download(['https://deb.debian.org/debian/pool/source'],destination,sha(data),len(data))
            self.assertFalse(destination.exists());self.assertFalse(list(self.work.glob('*.partial')))
    def test_offline_verification_and_original_filenames_without_execution(self):
        plan,objects,*_=self.fixture();report=sources.verify_objects(plan,objects)
        self.assertEqual(1,report['sourceArchivesCollected']);self.assertFalse(report['fullReleaseAuditComplete'])
        output=self.work/'materialized';sources.materialize(plan,objects,output,'fixture')
        for file in plan['sourcePackages'][0]['files']:self.assertEqual((objects/file['sha256']).read_bytes(),(output/file['name']).read_bytes())
        with self.assertRaises(FileExistsError):sources.materialize(plan,objects,output,'fixture')
        descriptor=next(f for f in plan['sourcePackages'][0]['files'] if f['name'].endswith('.dsc'))
        path=objects/descriptor['sha256'];path.write_text(path.read_text().replace('Version: 1:2.3-1','Version: 9:9-9'))
        with self.assertRaises(ValueError):sources.validate_dsc(plan['sourcePackages'][0],objects)
    def test_delivery_is_deterministic_and_contains_verbatim_objects(self):
        plan,objects,inventory,ring,cache=self.fixture()
        outputs=[]
        for name in ['delivery-a','delivery-b']:
            directory=self.work/name;parts=sources.package_sources(plan,objects,inventory,ring,cache,directory,32*1024**2);outputs.append(parts)
            with tarfile.open(directory/parts[0]['file'],'r:gz') as tar:
                for checksum in plan['objects']:self.assertEqual((objects/checksum).read_bytes(),tar.extractfile('objects/'+checksum).read())
                self.assertTrue(tar.getmember('tools/debian_sources.py').isfile())
                self.assertTrue(all(m.mtime==0 and m.uid==0 for m in tar.getmembers()))
        self.assertEqual(outputs[0],outputs[1])
        (cache/'debian/dists/trixie/InRelease').write_bytes(b'changed provenance')
        with self.assertRaises(ValueError):sources.package_sources(plan,objects,inventory,ring,cache,self.work/'bad-delivery',32*1024**2)
    def test_gpgv_checks_real_signatures_and_explicit_signer_policy(self):
        gpg=shutil.which('gpg') or r'C:\Program Files\Git\usr\bin\gpg.exe';gpgv=shutil.which('gpgv') or r'C:\Program Files\Git\usr\bin\gpgv.exe'
        if not Path(gpg).exists() or not Path(gpgv).exists():self.skipTest('GnuPG is unavailable')
        home=self.work/'signing-home';home.mkdir(mode=0o700)
        prefix=[gpg,'--homedir','./signing-home','--batch','--pinentry-mode','loopback','--passphrase','']
        def run(args):return subprocess.run(args,cwd=self.work,capture_output=True,check=True,timeout=30)
        try:
            run(prefix+['--quick-generate-key','Debian source verification fixture','ed25519','sign','0'])
            public=run(prefix+['--export']).stdout;ring=self.work/'fixture.pgp';ring.write_bytes(public)
            listing=run(prefix+['--with-colons','--list-keys']).stdout.decode();fingerprint=next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
            message=self.work/'Release';message.write_text('Codename: trixie\nSHA256:\n '+'a'*64+' 5 main/source/Sources.xz\n',encoding='utf-8')
            run(prefix+['--output','InRelease','--clearsign','Release']);signed=self.work/'InRelease';expected=sources.digest(signed)
            text,signers=sources.verify_release(gpgv,ring,signed,expected,[fingerprint],self.work/'verify')
            self.assertIn('Codename: trixie',text);self.assertEqual([fingerprint],signers)
            with self.assertRaises(ValueError):sources.verify_release(gpgv,ring,signed,expected,['0'*40],self.work/'wrong-key')
            signed.write_bytes(signed.read_bytes().replace(b'Codename: trixie',b'Codename: forged'))
            with self.assertRaises(ValueError):sources.verify_release(gpgv,ring,signed,sources.digest(signed),[fingerprint],self.work/'tampered')
        finally:
            conf=Path(gpg).with_name('gpgconf.exe' if Path(gpg).suffix=='.exe' else 'gpgconf')
            if conf.exists():subprocess.run([str(conf),'--homedir','./signing-home','--kill','gpg-agent'],cwd=self.work,capture_output=True,timeout=10)

if __name__=='__main__':unittest.main()
