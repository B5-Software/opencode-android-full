#!/usr/bin/env python3
"""Collect the exact Debian source files for an APK inventory. Never execute them.

Trust chain: pinned InRelease + explicit archive key fingerprints -> SHA256 Sources
index -> exact name/version -> SHA256 .dsc/orig/Debian patches. Downloaded files
use content-addressed names; repository paths are never extracted onto the host.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import lzma
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import tarfile
import gzip
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

from debian_sbom import stanzas, HashReader

ROOT = Path(__file__).resolve().parent.parent
HEX = re.compile(r'[a-f0-9]{64}')
NAME = re.compile(r'[a-z0-9][a-z0-9+.-]*')
VERSION = re.compile(r'[A-Za-z0-9.+:~_-]+')
MAX_INDEX = 128 * 1024**2
MAX_INDEX_TEXT = 256 * 1024**2
MAX_OBJECT = 2 * 1024**3
MAX_TOTAL = 16 * 1024**3
INDEX_PATH = 'main/source/Sources.xz'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unique_json(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def read_json(path):
    if path.stat().st_size > 16 * 1024**2: raise ValueError('JSON input too large')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_json)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, prefix=path.name+'.', suffix='.partial', delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')
    temporary.replace(path)


def relative(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9+._~%/-]+', value): raise ValueError('Invalid source path')
    path = PurePosixPath(value)
    if path.is_absolute() or any(p in ('.', '..') for p in value.split('/')) or str(path) != value:
        raise ValueError('Unsafe source path')
    return value


def validate_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != 'https' or parsed.hostname not in ('snapshot.debian.org', 'deb.debian.org') or parsed.port not in (None, 443) or parsed.username or parsed.password or parsed.fragment or parsed.query:
        raise ValueError('Unexpected source download origin')
    return value


class SourceRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


def checked_download(urls, destination, expected, size):
    if not HEX.fullmatch(expected) or type(size) is not int or not 0 < size <= MAX_OBJECT: raise ValueError('Invalid source object declaration')
    if destination.is_symlink(): raise ValueError('Source cache entry must not be a symlink')
    if destination.exists():
        if destination.stat().st_size != size or digest(destination) != expected: raise ValueError('Cached source checksum mismatch')
        return 'cache'
    destination.parent.mkdir(parents=True, exist_ok=True)
    opener = build_opener(SourceRedirect())
    for position, url in enumerate(urls):
        validate_url(url)
        try:
            response = opener.open(Request(url, headers={'Accept-Encoding':'identity','User-Agent':'OpenCode-Android-source-inventory/1'}), timeout=45)
        except HTTPError as error:
            if error.code in (404,410) and position+1 < len(urls): continue
            raise
        temporary = None
        try:
            with response, tempfile.NamedTemporaryFile(dir=destination.parent, prefix=expected+'.', suffix='.partial', delete=False) as output:
                temporary = Path(output.name); count = 0; checksum = hashlib.sha256()
                while chunk := response.read(1024**2):
                    count += len(chunk)
                    if count > size: raise ValueError('Source download exceeded declared size')
                    checksum.update(chunk); output.write(chunk)
                if count != size or checksum.hexdigest() != expected: raise ValueError('Source download checksum/size mismatch')
                output.flush(); os.fsync(output.fileno())
            temporary.replace(destination)
            return url
        finally:
            if temporary is not None: temporary.unlink(missing_ok=True)
    raise ValueError('No source download location')


def verify_release(gpgv, keyring, inrelease, expected, allowed_fingerprints, work):
    if digest(inrelease) != expected: raise ValueError('Pinned InRelease checksum mismatch')
    # A private verification cwd also avoids MSYS gpgv interpreting a Windows D:
    # keyring path as a resource URL. No personal keyring, agent or trustdb is used.
    work.mkdir(parents=True, exist_ok=True)
    ring = work/'archive-keyring.pgp'; ring.write_bytes(keyring.read_bytes())
    (work/'gpgv-home').mkdir(mode=0o700, exist_ok=True)
    executable = str(Path(gpgv).resolve()) if Path(gpgv).exists() else gpgv
    result = subprocess.run([executable, '--homedir','./gpgv-home','--keyring','./archive-keyring.pgp', '--status-fd','2','--output','-',os.path.relpath(inrelease.resolve(), work.resolve()).replace('\\','/')], cwd=work, capture_output=True, timeout=30)
    if result.returncode: raise ValueError('InRelease signature verification failed')
    valid = set()
    for line in result.stderr.decode('utf-8', errors='replace').splitlines():
        if not line.startswith('[GNUPG:] VALIDSIG '): continue
        fields = line.removeprefix('[GNUPG:] ').split()
        if len(fields) >= 10 and fields[8] in ('8','9','10','11'):
            primary = fields[10] if len(fields)>10 else fields[1]
            if primary in allowed_fingerprints: valid.add(primary)
    if not valid: raise ValueError('No signature by an explicitly allowed Debian archive key')
    if len(result.stdout) > 1024**2: raise ValueError('Release metadata too large')
    return result.stdout.decode('utf-8'), sorted(valid)


def release_index(plaintext, suite):
    records = list(stanzas(plaintext))
    if len(records)!=1 or records[0].get('Codename') != suite: raise ValueError('Unexpected Release suite')
    matches = []
    for line in records[0].get('SHA256','').splitlines():
        fields=line.split()
        if len(fields)==3 and fields[2]==INDEX_PATH:
            checksum,size,_=fields
            if not HEX.fullmatch(checksum) or not size.isdecimal() or not 0<int(size)<=MAX_INDEX: raise ValueError('Invalid Sources index declaration')
            matches.append((checksum,int(size)))
    if len(matches)!=1: raise ValueError('Missing or duplicate SHA256 Sources index')
    return matches[0]


def source_files(record):
    directory=relative(record.get('Directory',''))
    if not directory.startswith('pool/'): raise ValueError('Source is outside Debian pool')
    files=[];names=set()
    for line in record.get('Checksums-Sha256','').strip().splitlines():
        fields=line.split()
        if len(fields)!=3: raise ValueError('Malformed source checksum entry')
        checksum,size,filename=fields
        relative(filename)
        if '/' in filename or filename in names or not HEX.fullmatch(checksum) or not size.isdecimal() or not 0<int(size)<=MAX_OBJECT:
            raise ValueError('Invalid or duplicate source file')
        names.add(filename);files.append({'name':filename,'sha256':checksum,'size':int(size),'poolPath':directory+'/'+filename})
    if not 1<len(files)<=4096 or sum(item['name'].endswith('.dsc') for item in files)!=1: raise ValueError('Source package needs exactly one .dsc and its complete data files')
    return sorted(files,key=lambda item:item['name'])


def requested_sources(inventory):
    requests=read_json(inventory/'debian-source-requests.json');coverage=read_json(inventory/'debian-inventory-coverage.json')
    if len(requests)!=coverage['sourcePackageIdentities'] or not HEX.fullmatch(coverage['rootfsSha256']): raise ValueError('Inventory source coverage mismatch')
    wanted={};binary_ids=set()
    for item in requests:
        name,version=item['name'],item['version']
        if not NAME.fullmatch(name) or not VERSION.fullmatch(version) or (name,version) in wanted: raise ValueError('Invalid or duplicate requested source identity')
        for identity in item['binarySpdxIds']:
            if identity in binary_ids or not re.fullmatch(r'SPDXRef-Debian-[a-f0-9]{24}',identity): raise ValueError('Duplicate or invalid binary identity')
            binary_ids.add(identity)
        wanted[(name,version)]=item
    if len(binary_ids)!=coverage['installedPackages']: raise ValueError('Source requests do not cover all installed binaries')
    return wanted,coverage


def plan_sources(inventory, policy_path, keyring, cache, gpgv):
    wanted,coverage=requested_sources(inventory);policy=read_json(policy_path)
    if digest(keyring)!=policy['keyringSha256']: raise ValueError('Archive keyring does not match the pinned policy')
    release_lock=read_json(ROOT/'runtime/developer-source-lock.json');indexes=[];found={}
    for name,pin in release_lock.items():
        archive,suite=name.split('/');base=pin['url']
        if archive not in policy['primaryFingerprints'] or base!=f'https://snapshot.debian.org/archive/{archive}/{pin["snapshot"]}': raise ValueError('Unexpected snapshot pin')
        inrelease=cache/archive/'dists'/suite/'InRelease'
        if not inrelease.exists(): raise FileNotFoundError('Prepare the locked binary mirror indexes first')
        plaintext,signers=verify_release(gpgv,keyring,inrelease,pin['inReleaseSha256'],policy['primaryFingerprints'][archive],cache/'source-verification'/archive/suite)
        checksum,size=release_index(plaintext,suite);index=inrelease.parent/INDEX_PATH
        checked_download([base+'/dists/'+suite+'/'+INDEX_PATH],index,checksum,size)
        with lzma.open(index,'rb') as stream: text=stream.read(MAX_INDEX_TEXT+1)
        if len(text)>MAX_INDEX_TEXT: raise ValueError('Sources index expansion exceeds limit')
        for record in stanzas(text.decode('utf-8')):
            identity=(record.get('Package'),record.get('Version'))
            if identity not in wanted: continue
            files=source_files(record)
            previous=found.get(identity)
            if previous and [(f['name'],f['size'],f['sha256']) for f in previous['files']]!=[(f['name'],f['size'],f['sha256']) for f in files]: raise ValueError('Conflicting source content for the same exact version')
            found.setdefault(identity,{'name':identity[0],'version':identity[1],'index':name,'binarySpdxIds':wanted[identity]['binarySpdxIds'],'files':files})
        indexes.append({'name':name,'snapshot':pin['snapshot'],'url':base,'inReleaseSha256':pin['inReleaseSha256'],'sourcesSha256':checksum,'sourcesSize':size,'primarySigners':signers})
    missing=sorted(set(wanted)-set(found))
    if missing: raise ValueError('Unresolved exact source versions: '+repr(missing))
    objects={}
    for package in found.values():
        archive=package['index'].split('/')[0]
        for file in package['files']:
            item=objects.setdefault(file['sha256'],{'size':file['size'],'locations':[]})
            if item['size']!=file['size']: raise ValueError('Source checksum size collision')
            location={'archive':archive,'poolPath':file['poolPath']}
            if location not in item['locations']:item['locations'].append(location)
    total=sum(item['size'] for item in objects.values())
    if total>MAX_TOTAL: raise ValueError('Source collection exceeds total bound')
    return {'format':1,'scope':'Debian portion only','rootfsSha256':coverage['rootfsSha256'],'requestsSha256':digest(inventory/'debian-source-requests.json'),'archiveKeyringSha256':policy['keyringSha256'],'indexes':indexes,'sourcePackages':[found[k] for k in sorted(found)],'objects':{k:objects[k] for k in sorted(objects)},'totalBytes':total,'fullReleaseAuditComplete':False}


def object_urls(plan,item):
    urls=[]
    for location in item['locations']:
        archive=location['archive'];path=quote(relative(location['poolPath']),safe='/+~._-')
        index=next(i for i in plan['indexes'] if i['name'].startswith(archive+'/'))
        urls += [f'https://deb.debian.org/{archive}/{path}', index['url']+'/'+path]
    return list(dict.fromkeys(urls))


def compare_source_graph(actual, expected):
    """Lock source identities/content, retaining each build's actual rootfs digest.

    The rootfs archive can contain generated package-manager/cache metadata;
    byte reproducibility across builders is not assumed. Every other field,
    including the complete binary-to-source request hash, must match exactly.
    """
    validate_plan(actual);validate_plan(expected)
    if {k:v for k,v in actual.items() if k!='rootfsSha256'}!={k:v for k,v in expected.items() if k!='rootfsSha256'}:
        raise ValueError('The authenticated source graph differs from the committed lock')


def validate_plan(plan):
    if plan.get('format')!=1 or not HEX.fullmatch(plan.get('rootfsSha256','')) or not HEX.fullmatch(plan.get('requestsSha256','')):
        raise ValueError('Invalid source lock identity')
    objects=plan['objects'];packages=plan['sourcePackages']
    if not 0<len(objects)<=50000 or not 0<len(packages)<=10000:raise ValueError('Source lock count exceeds limit')
    total=0;identities=set();referenced=set();indexes={}
    for index in plan['indexes']:
        archive,suite=index['name'].split('/')
        if archive not in ('debian','debian-security') or not re.fullmatch(r'[a-z0-9-]+',suite) or not re.fullmatch(r'\d{8}T\d{6}Z',index['snapshot']):raise ValueError('Invalid source index identity')
        if index['url']!=f'https://snapshot.debian.org/archive/{archive}/{index["snapshot"]}':raise ValueError('Unexpected source index URL')
        if index['name'] in indexes:raise ValueError('Duplicate source index')
        indexes[index['name']]=index
    for checksum,item in objects.items():
        if not HEX.fullmatch(checksum) or type(item['size']) is not int or not 0<item['size']<=MAX_OBJECT:raise ValueError('Invalid source object')
        if not 0<len(item['locations'])<=8:raise ValueError('Source object location count exceeds limit')
        for location in item['locations']:
            if location['archive'] not in ('debian','debian-security') or not relative(location['poolPath']).startswith('pool/'):raise ValueError('Invalid source object location')
        total+=item['size']
    if total>MAX_TOTAL or total!=plan['totalBytes']:raise ValueError('Source lock byte bound mismatch')
    for package in packages:
        identity=(package['name'],package['version'])
        if not NAME.fullmatch(identity[0]) or not VERSION.fullmatch(identity[1]) or identity in identities or package['index'] not in indexes:raise ValueError('Invalid or duplicate source package identity')
        identities.add(identity);names=set()
        for file in package['files']:
            if '/' in relative(file['name']) or file['name'] in names:raise ValueError('Invalid source package filename')
            names.add(file['name']);checksum=file['sha256']
            if checksum not in objects or file['size']!=objects[checksum]['size']:raise ValueError('Source package/object mismatch')
            location={'archive':package['index'].split('/')[0],'poolPath':relative(file['poolPath'])}
            if not file['poolPath'].endswith('/'+file['name']) or location not in objects[checksum]['locations']:raise ValueError('Source package location mismatch')
            referenced.add(checksum)
        if not 1<len(names)<=4096 or sum(name.endswith('.dsc') for name in names)!=1:raise ValueError('Invalid source descriptor count')
    if referenced!=set(objects):raise ValueError('Source lock contains unreferenced objects')
    return plan


def apk_keyring(apk,output,policy):
    with zipfile.ZipFile(apk) as archive:
        metadata=archive.read('assets/runtime/pack.json')
        if len(metadata)>65536:raise ValueError('APK runtime metadata too large')
        expected=json.loads(metadata)['sha256'];key=None;count=0;unpacked=0
        with archive.open('assets/runtime/debian.pack') as raw:
            checked=HashReader(raw)
            with tarfile.open(fileobj=checked,mode='r|gz') as rootfs:
                for entry in rootfs:
                    count+=1;unpacked+=entry.size
                    if count>250000 or not 0<=entry.size<=512*1024**2 or unpacked>4*1024**3:raise ValueError('APK keyring scan limits exceeded')
                    if entry.name in ('./usr/share/keyrings/debian-archive-keyring.pgp','usr/share/keyrings/debian-archive-keyring.pgp'):
                        if key is not None or not entry.isfile() or not 0<entry.size<1024**2:raise ValueError('Invalid APK archive keyring member')
                        key=rootfs.extractfile(entry).read()
            while checked.read(1024**2):pass
            if checked.digest.hexdigest()!=expected:raise ValueError('APK rootfs archive checksum mismatch')
    if key is None or hashlib.sha256(key).hexdigest()!=policy['keyringSha256']:raise ValueError('APK archive keyring differs from pinned policy')
    if output.exists():
        if output.read_bytes()!=key:raise ValueError('Existing keyring differs')
    else:output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(key)


def verify_objects(plan,objects):
    validate_plan(plan)
    for checksum,item in plan['objects'].items():
        path=objects/checksum
        if path.is_symlink() or not path.is_file() or path.stat().st_size!=item['size'] or digest(path)!=checksum:raise ValueError('Missing or changed source object: '+checksum)
    for package in plan['sourcePackages']:validate_dsc(package,objects)
    return {'sourceArchivesCollected':len(plan['sourcePackages']),'objectsVerified':len(plan['objects']),'verifiedBytes':plan['totalBytes'],'rootfsSha256':plan['rootfsSha256'],'fullReleaseAuditComplete':False}


def validate_dsc(package,objects):
    files=package['files'];descriptor=next(f for f in files if f['name'].endswith('.dsc'));path=objects/descriptor['sha256']
    if path.stat().st_size>2*1024**2: raise ValueError('.dsc metadata too large')
    text=path.read_text(encoding='utf-8')
    if text.startswith('-----BEGIN PGP SIGNED MESSAGE-----'):
        text=text.split('\n\n',1)[1].split('-----BEGIN PGP SIGNATURE-----',1)[0]
        text='\n'.join(line[2:] if line.startswith('- ') else line for line in text.splitlines())
    records=list(stanzas(text))
    if len(records)!=1 or records[0].get('Source')!=package['name'] or records[0].get('Version')!=package['version']:raise ValueError('.dsc source identity mismatch')
    checks={}
    for line in records[0].get('Checksums-Sha256','').strip().splitlines():
        checksum,size,name=line.split()
        if name in checks:raise ValueError('Duplicate .dsc checksum')
        checks[name]=(checksum,int(size))
    expected={f['name']:(f['sha256'],f['size']) for f in files if not f['name'].endswith('.dsc')}
    if checks!=expected:raise ValueError('.dsc data files differ from the signed Sources index')


def collect_sources(plan,objects,report_path,workers):
    validate_plan(plan)
    if not 1<=workers<=8:raise ValueError('Use 1 to 8 download workers')
    objects.mkdir(parents=True,exist_ok=True)
    fetched=[];failures=[]
    def fetch(pair):
        checksum,item=pair
        return checksum,checked_download(object_urls(plan,item),objects/checksum,checksum,item['size'])
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        pending={executor.submit(fetch,pair):pair[0] for pair in plan['objects'].items()}
        for future in concurrent.futures.as_completed(pending):
            try:fetched.append(future.result()[0])
            except Exception as error:failures.append({'sha256':pending[future],'error':type(error).__name__+': '+str(error)[:240]})
            if (len(fetched)+len(failures))%50==0:print(f'Source objects verified: {len(fetched)}/{len(pending)}, failures: {len(failures)}',flush=True)
    complete=[];incomplete=[];verified=set(fetched)
    for package in plan['sourcePackages']:
        if all(file['sha256'] in verified for file in package['files']):
            try:validate_dsc(package,objects);complete.append({'name':package['name'],'version':package['version']})
            except ValueError as error:
                failures.append({'source':package['name'],'version':package['version'],'error':str(error)[:240]});incomplete.append({'name':package['name'],'version':package['version']})
        else:incomplete.append({'name':package['name'],'version':package['version']})
    report={'scope':'Debian portion only','rootfsSha256':plan['rootfsSha256'],'sourcePackagesRequested':len(plan['sourcePackages']),'sourceArchivesCollected':len(complete),'objectsVerified':len(fetched),'objectsRequested':len(plan['objects']),'verifiedBytes':sum(plan['objects'][sha]['size'] for sha in fetched),'incomplete':incomplete,'failures':failures,'fullReleaseAuditComplete':False}
    write_json(report_path,report);print(json.dumps(report,ensure_ascii=False),flush=True)
    if failures or incomplete:raise ValueError('Corresponding source collection is incomplete; verified cache can be resumed')


def materialize(plan,objects,output,name,version=None):
    validate_plan(plan)
    matches=[p for p in plan['sourcePackages'] if p['name']==name and (version is None or p['version']==version)]
    if len(matches)!=1:raise ValueError('Select one exact source package/version')
    package=matches[0]
    for file in package['files']:
        path=objects/file['sha256']
        if path.is_symlink() or not path.is_file() or path.stat().st_size!=file['size'] or digest(path)!=file['sha256']:raise ValueError('Source object changed')
        if file['name'].endswith('.') or file['name'].split('.')[0].upper() in {'CON','PRN','AUX','NUL',*(f'COM{i}' for i in range(1,10)),*(f'LPT{i}' for i in range(1,10))}:raise ValueError('Filename is not portable to Windows')
    validate_dsc(package,objects);output.mkdir(parents=True,exist_ok=False)
    for file in package['files']:shutil.copyfile(objects/file['sha256'],output/file['name'])


def package_sources(plan,objects,inventory,keyring,index_cache,output,part_bytes):
    summary=verify_objects(plan,objects)
    if digest(keyring)!=plan['archiveKeyringSha256'] or digest(inventory/'debian-source-requests.json')!=plan['requestsSha256']:
        raise ValueError('Source delivery metadata differs from the verified plan')
    if read_json(inventory/'debian-inventory-coverage.json')['rootfsSha256']!=plan['rootfsSha256']:raise ValueError('Source delivery rootfs identity differs')
    for index in plan['indexes']:
        archive,suite=index['name'].split('/');directory=index_cache/archive/'dists'/suite
        if digest(directory/'InRelease')!=index['inReleaseSha256'] or digest(directory/INDEX_PATH)!=index['sourcesSha256'] or (directory/INDEX_PATH).stat().st_size!=index['sourcesSize']:
            raise ValueError('Source delivery indexes differ from the verified plan')
    if not 32*1024**2<=part_bytes<=1024**3:raise ValueError('Source part limit must be between 32 MiB and 1 GiB')
    if any(item['size']>part_bytes for item in plan['objects'].values()):raise ValueError('A source object exceeds the part limit')
    groups=[];group=[];size=0
    for checksum,item in plan['objects'].items():
        if group and size+item['size']>part_bytes:groups.append(group);group=[];size=0
        group.append(checksum);size+=item['size']
    if group:groups.append(group)
    output.mkdir(parents=True,exist_ok=False)
    manifest=(json.dumps(plan,ensure_ascii=False,indent=2)+'\n').encode()
    readme=("Debian corresponding-source collection; this is not a complete APK release audit.\n"
            "Unpack ALL parts into one directory. Source archives remain compressed and are never executed.\n"
            "Verify: python tools/debian_sources.py verify --lock SOURCE_MANIFEST.json --objects objects\n"
            "Restore original filenames: python tools/debian_sources.py materialize --lock SOURCE_MANIFEST.json --objects objects --name dpkg --output dpkg-source\n"
            "Then use Debian's dpkg-source -x with the restored .dsc if you want to unpack it.\n"
            "The objects, descriptors and Debian patches retain their original SHA-256-verified bytes.\n").encode()
    def add_bytes(tar,name,data):
        entry=tarfile.TarInfo(name);entry.size=len(data);entry.mode=0o644;entry.mtime=0;tar.addfile(entry,io.BytesIO(data))
    parts=[]
    for number,group in enumerate(groups,1):
        path=output/f'debian-source-part-{number:02}.tar.gz'
        with path.open('xb') as raw,gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0,compresslevel=1) as compressed,tarfile.open(fileobj=compressed,mode='w|',format=tarfile.PAX_FORMAT) as tar:
            add_bytes(tar,'SOURCE_MANIFEST.json',manifest);add_bytes(tar,'README.txt',readme)
            if number==1:
                for tool in ('debian_sources.py','debian_sbom.py'):add_bytes(tar,'tools/'+tool,(ROOT/'tools'/tool).read_bytes())
                add_bytes(tar,'metadata/debian-source-policy.json',(ROOT/'runtime/debian-source-policy.json').read_bytes())
                add_bytes(tar,'metadata/debian-archive-keyring.pgp',keyring.read_bytes())
                for file in ('debian-source-requests.json','debian-inventory-coverage.json'):add_bytes(tar,'metadata/inventory/'+file,(inventory/file).read_bytes())
                for index in plan['indexes']:
                    archive,suite=index['name'].split('/')
                    for file in ('InRelease',INDEX_PATH):
                        target=index_cache/archive/'dists'/suite/file
                        add_bytes(tar,'metadata/indexes/'+archive+'/dists/'+suite+'/'+file,target.read_bytes())
            for checksum in group:
                entry=tarfile.TarInfo('objects/'+checksum);entry.size=plan['objects'][checksum]['size'];entry.mode=0o644;entry.mtime=0
                with (objects/checksum).open('rb') as stream:tar.addfile(entry,stream)
        parts.append({'file':path.name,'sha256':digest(path),'size':path.stat().st_size})
    write_json(output/'debian-source-delivery.json',{'scope':'Debian portion only',**summary,'parts':parts})
    (output/'SHA256SUMS-debian-sources.txt').write_text(''.join(p['sha256']+'  '+p['file']+'\n' for p in parts),encoding='utf-8')
    return parts


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='action',required=True)
    plan=sub.add_parser('plan');plan.add_argument('--inventory',type=Path,required=True);plan.add_argument('--keyring',type=Path,required=True);plan.add_argument('--gpgv',default=shutil.which('gpgv'));plan.add_argument('--cache',type=Path,default=ROOT/'.cache/developer-mirror');plan.add_argument('--policy',type=Path,default=ROOT/'runtime/debian-source-policy.json');plan.add_argument('--output',type=Path,required=True);plan.add_argument('--expected-lock',type=Path)
    collect=sub.add_parser('collect');collect.add_argument('--lock',type=Path,required=True);collect.add_argument('--objects',type=Path,required=True);collect.add_argument('--report',type=Path,required=True);collect.add_argument('--workers',type=int,default=4)
    keyring=sub.add_parser('keyring');keyring.add_argument('--apk',type=Path,required=True);keyring.add_argument('--output',type=Path,required=True);keyring.add_argument('--policy',type=Path,default=ROOT/'runtime/debian-source-policy.json')
    verify=sub.add_parser('verify');verify.add_argument('--lock',type=Path,required=True);verify.add_argument('--objects',type=Path,required=True)
    restore=sub.add_parser('materialize');restore.add_argument('--lock',type=Path,required=True);restore.add_argument('--objects',type=Path,required=True);restore.add_argument('--name',required=True);restore.add_argument('--version');restore.add_argument('--output',type=Path,required=True)
    package=sub.add_parser('package');package.add_argument('--lock',type=Path,required=True);package.add_argument('--objects',type=Path,required=True);package.add_argument('--inventory',type=Path,required=True);package.add_argument('--keyring',type=Path,required=True);package.add_argument('--index-cache',type=Path,default=ROOT/'.cache/developer-mirror');package.add_argument('--output',type=Path,required=True);package.add_argument('--part-bytes',type=int,default=512*1024**2)
    args=parser.parse_args()
    if args.action=='plan':
        if not args.gpgv:raise ValueError('gpgv is required; signature verification cannot be skipped')
        result=plan_sources(args.inventory,args.policy,args.keyring,args.cache,args.gpgv)
        if args.expected_lock:compare_source_graph(result,read_json(args.expected_lock))
        write_json(args.output,result);print(json.dumps({'sourcePackages':len(result['sourcePackages']),'objects':len(result['objects']),'bytes':result['totalBytes']}))
    elif args.action=='collect':collect_sources(read_json(args.lock),args.objects,args.report,args.workers)
    elif args.action=='keyring':apk_keyring(args.apk,args.output,read_json(args.policy));print('APK archive keyring and rootfs digest verified')
    elif args.action=='verify':print(json.dumps(verify_objects(read_json(args.lock),args.objects)))
    elif args.action=='materialize':materialize(read_json(args.lock),args.objects,args.output,args.name,args.version)
    else:print(json.dumps(package_sources(read_json(args.lock),args.objects,args.inventory,args.keyring,args.index_cache,args.output,args.part_bytes)))


if __name__=='__main__':main()
