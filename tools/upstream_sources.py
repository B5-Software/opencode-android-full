#!/usr/bin/env python3
"""Deliver pinned upstream archives and their verbatim license/notice texts.

Only the sources listed as collected in the lock are delivered. External source
references are retained explicitly and never counted as collected archives.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import tarfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
MAX=1536*1024**2
WEBKIT_BUILD_PATHS=('Source','Tools','WebKitLibraries','.github','Configurations','icu','macos-cross','metadata','resources','scripts','WebKit.xcworkspace')

def source_git_environment(base=None):
    environment=dict(os.environ if base is None else base)
    # Git archive applies checkout conversions. Inherited Git configuration
    # must not change the exported source bytes, permissions or attributes.
    for key in list(environment):
        if key in ('GIT_CONFIG_PARAMETERS','GIT_CONFIG_COUNT','GIT_DIR','GIT_WORK_TREE','GIT_INDEX_FILE','GIT_OBJECT_DIRECTORY','GIT_ALTERNATE_OBJECT_DIRECTORIES') or key.startswith(('GIT_CONFIG_KEY_','GIT_CONFIG_VALUE_')):
            environment.pop(key)
    environment.update(GIT_CONFIG_GLOBAL=os.devnull,GIT_CONFIG_NOSYSTEM='1',GIT_ATTR_NOSYSTEM='1',GIT_TERMINAL_PROMPT='0')
    return environment

def source_git_command(repo,*arguments):
    return ['git','-c','core.longpaths=true','-c','core.autocrlf=false','-c','core.eol=lf','-c','core.attributesFile='+os.devnull,'-c','tar.umask=0002','-c','credential.helper=','-C',str(repo),*arguments]

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def validate(record):
    if not re.fullmatch(r'[a-z0-9-]+',record['name']) or record['file']!=record['name']+'-source.tar.gz':raise ValueError('Invalid source filename')
    if 'releaseTag' in record:
        version=record.get('version','')
        if record['name']!='bun-icu' or record['repository']!='unicode-org/icu' or not re.fullmatch(r'[0-9]+\.[0-9]+',version) or record['releaseTag']!='release-'+version or 'commit' in record:raise ValueError('Invalid ICU release source pin')
        expected=f'https://github.com/unicode-org/icu/releases/download/release-{version}/icu4c-{version}-sources.tgz'
    else:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',record['repository']) or not re.fullmatch(r'[a-f0-9]{40}',record['commit']):raise ValueError('Invalid upstream source pin')
        expected=f'https://codeload.github.com/{record["repository"]}/tar.gz/{record["commit"]}'
    if record['url']!=expected:raise ValueError('Unexpected archive URL')
    if not re.fullmatch(r'[a-f0-9]{64}',record['sha256']) or type(record['size']) is not int or not 0<record['size']<=MAX:raise ValueError('Invalid source checksum/size')

class SourceRedirect(HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,newurl):
        parsed=urlsplit(newurl)
        if parsed.scheme!='https' or parsed.hostname!='codeload.github.com' or parsed.username or parsed.password or parsed.port not in (None,443):raise ValueError('Unexpected source redirect')
        return super().redirect_request(request,fp,code,message,headers,newurl)

class ReleaseSourceRedirect(HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,newurl):
        parsed=urlsplit(newurl)
        if parsed.scheme!='https' or parsed.hostname not in ('github.com','release-assets.githubusercontent.com') or parsed.username or parsed.password or parsed.port not in (None,443):raise ValueError('Unexpected release source redirect')
        return super().redirect_request(request,fp,code,message,headers,newurl)

def acquire(record,cache):
    validate(record);cache.mkdir(parents=True,exist_ok=True);target=cache/record['file']
    if target.is_symlink():raise ValueError('Source cache entry is a symlink')
    if target.exists():
        if target.stat().st_size!=record['size'] or digest(target)!=record['sha256']:raise ValueError('Cached upstream source changed')
        return target
    temporary=None
    try:
        redirect=ReleaseSourceRedirect() if 'releaseTag' in record else SourceRedirect()
        with build_opener(redirect).open(Request(record['url'],headers={'Accept-Encoding':'identity'}),timeout=60) as response,tempfile.NamedTemporaryFile(dir=cache,suffix='.partial',delete=False) as output:
            temporary=Path(output.name);count=0;checksum=hashlib.sha256()
            while block:=response.read(1024**2):
                count+=len(block)
                if count>record['size']:raise ValueError('Source exceeds pinned size')
                checksum.update(block);output.write(block)
        if count!=record['size'] or checksum.hexdigest()!=record['sha256']:raise ValueError('Source checksum/size mismatch')
        temporary.replace(target);return target
    finally:
        if temporary:temporary.unlink(missing_ok=True)

def verify_git_archive(path,record):
    if path.is_symlink():raise ValueError('Git source archive is a symlink')
    if path.stat().st_size>MAX:raise ValueError('Compressed Git source exceeds bound')
    checksum=hashlib.sha256();count=0
    with gzip.open(path,'rb') as source:
        while block:=source.read(1024**2):
            count+=len(block)
            if count>record['tarSize']:raise ValueError('Git source tar exceeds its pin')
            checksum.update(block)
    if count!=record['tarSize'] or checksum.hexdigest()!=record['tarSha256']:raise ValueError(f'Git source tar differs from its pin: bytes={count}, sha256={checksum.hexdigest()}')

def acquire_git(record,cache):
    if record['name']!='bun-webkit' or record['repository']!='oven-sh/WebKit' or record['file']!='bun-webkit-source.tar.gz':raise ValueError('Unexpected Git source')
    if not re.fullmatch(r'[a-f0-9]{40}',record['commit']) or not re.fullmatch(r'[a-f0-9]{64}',record['tarSha256']) or type(record['tarSize']) is not int or not 0<record['tarSize']<=8*1024**3:raise ValueError('Invalid Git source pin')
    if record['paths'][:3]!=['Source','Tools','WebKitLibraries'] or len(record['paths'])>100:raise ValueError('Invalid Git source selection')
    for path in record['paths']:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+',path) or path in ('.','..'):raise ValueError('Unsafe Git source selector')
    cache.mkdir(parents=True,exist_ok=True);target=cache/'git'/record['tarSha256']/record['file']
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():verify_git_archive(target,record);return target
    # Follow Bun's documented public Git source path; no source code is run.
    # Ignore personal Git filters/hooks and credential helpers in this isolated
    # acquisition checkout. The original blob bytes come from git archive.
    environment=source_git_environment()
    with tempfile.TemporaryDirectory(prefix='webkit-source-',dir=cache,ignore_cleanup_errors=True) as directory:
        repo=Path(directory)
        repo.resolve().relative_to(cache.resolve())
        def git(*arguments,**kwargs):return subprocess.run(source_git_command(repo,*arguments),env=environment,check=True,**kwargs)
        git('init',stdout=subprocess.DEVNULL);git('remote','add','origin','https://github.com/oven-sh/WebKit.git')
        git('fetch','--no-tags','--depth=1','--filter=blob:none','origin',record['commit'])
        git('sparse-checkout','init','--cone');git('sparse-checkout','set',*[p for p in record['paths'] if p in WEBKIT_BUILD_PATHS]);git('checkout','--detach',record['commit'])
        temporary=target.with_suffix(target.suffix+'.partial')
        try:
            with temporary.open('xb') as raw,gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0,compresslevel=1) as output:
                process=subprocess.Popen(source_git_command(repo,'archive','--format=tar','--prefix=bun-webkit/',record['commit'],'--',*record['paths']),stdout=subprocess.PIPE,env=environment)
                try:
                    count=0
                    while block:=process.stdout.read(1024**2):
                        count+=len(block)
                        if count>record['tarSize']:raise ValueError('Generated Git tar exceeds pin')
                        output.write(block)
                    if process.wait():raise ValueError('git archive failed')
                finally:
                    process.stdout.close()
                    if process.poll() is None:process.terminate();process.wait(timeout=10)
            verify_git_archive(temporary,record);temporary.replace(target)
        finally:temporary.unlink(missing_ok=True)
    return target

def verify_bun_source(archive,lock):
    wanted={'package.json','scripts/build/deps/webkit.ts','scripts/build/deps/tinycc.ts'};found={}
    with tarfile.open(archive,'r|gz') as source:
        for entry in source:
            name='/'.join(PurePosixPath(entry.name).parts[1:])
            if name not in wanted:continue
            if not entry.isfile() or entry.size>256*1024 or name in found:raise ValueError('Invalid Bun version metadata')
            found[name]=source.extractfile(entry).read().decode('utf-8')
    if set(found)!=wanted or json.loads(found['package.json'])['version']!=lock['bunVersion']:raise ValueError('Bun source version does not match the locked runtime')
    records={r['name']:r for r in lock['sources']+lock.get('gitSources',[])}
    for file,constant,name in [('scripts/build/deps/webkit.ts','WEBKIT_VERSION','bun-webkit'),('scripts/build/deps/tinycc.ts','TINYCC_COMMIT','bun-tinycc')]:
        match=re.search(r'const '+constant+r'\s*=\s*"([a-f0-9]{40})"',found[file])
        if not match or match.group(1)!=records[name]['commit']:raise ValueError('Bun dependency source pin differs from its actual build script')

def verify_webkit_source(archive,lock):
    record=next(r for r in lock['gitSources'] if r['name']=='bun-webkit')
    if not set(WEBKIT_BUILD_PATHS).issubset(record['paths']):raise ValueError('WebKit source selection omits build inputs')
    wanted={'icu/source.json','.github/scripts/lanes.mjs','scripts/mirror-gcc13-debs.sh','CMakeLists.txt','Dockerfile'};found={}
    with tarfile.open(archive,'r|gz') as source:
        for entry in source:
            name='/'.join(PurePosixPath(entry.name).parts[1:])
            if name not in wanted:continue
            if name in found or not entry.isfile() or entry.size>256*1024:raise ValueError('Invalid WebKit build metadata')
            found[name]=source.extractfile(entry).read()
    if set(found)!=wanted:raise ValueError('WebKit archive omits required build recipes')
    icu=json.loads(found['icu/source.json']);paired=next((r for r in lock['sources'] if r['name']=='bun-icu'),None)
    if paired is None or paired.get('version')!=icu['version'] or paired.get('sha256')!=icu['sha256']:raise ValueError('ICU sources differ from the WebKit build pin')

def notices(archive,component,output):
    count=total=0
    with tarfile.open(archive,'r|gz') as source:
        for index,entry in enumerate(source,1):
            total+=entry.size
            if index>300000 or total>16*1024**3 or entry.size>512*1024**2:raise ValueError('Upstream tar bounds exceeded')
            name=PurePosixPath(entry.name)
            if name.is_absolute() or '..' in name.parts or any(ord(c)<32 for c in entry.name):raise ValueError('Unsafe upstream archive path')
            if not entry.isfile() or not re.match(r'^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE)(?:[._-].*)?$',name.name,re.I):continue
            if entry.size>2*1024**2:raise ValueError('License text exceeds bound')
            output.writestr(component+'/'+str(name),source.extractfile(entry).read());count+=1
    return count

def collect(lock,cache,output):
    if lock['format']!=1 or not 0<len(lock['sources'])<100:raise ValueError('Invalid source lock')
    names=set()
    for record in lock['sources']:
        validate(record)
        if record['name'] in names:raise ValueError('Duplicate source component')
        names.add(record['name'])
    upstream=json.loads((ROOT/'runtime/opencode-dev.lock.json').read_text(encoding='utf-8'))
    if lock['bunVersion']!=upstream['bunVersion'] or next(r['commit'] for r in lock['sources'] if r['name']=='opencode')!=upstream['commit']:raise ValueError('Source delivery does not match the packaged official pair/runtime')
    output.mkdir(parents=True,exist_ok=False);records=[];git_records=[]
    with zipfile.ZipFile(output/'upstream-license-texts.zip','x',compression=zipfile.ZIP_DEFLATED) as license_zip:
        for record in lock['sources']:
            archive=acquire(record,cache);count=notices(archive,record['name'],license_zip)
            if record['name']=='bun':verify_bun_source(archive,lock)
            shutil.copyfile(archive,output/record['file']);records.append(dict(record,licenseTextFiles=count))
        for record in lock.get('gitSources',[]):
            if record['name'] in names:raise ValueError('Duplicate Git source component')
            names.add(record['name']);archive=acquire_git(record,cache);verify_webkit_source(archive,lock);count=notices(archive,record['name'],license_zip)
            shutil.copyfile(archive,output/record['file']);git_records.append(dict(record,licenseTextFiles=count,sha256=digest(archive),size=archive.stat().st_size))
    result=dict(lock,sources=records,gitSources=git_records,sourceArchivesCollected=len(records)+len(git_records),fullReleaseAuditComplete=False)
    (output/'upstream-source-delivery.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'sourceArchivesCollected':len(records)+len(git_records),'licenseTextFiles':sum(r['licenseTextFiles'] for r in records+git_records),'externalSourceReferences':len(lock.get('externalSourceReferences',[]))}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--lock',type=Path,default=ROOT/'runtime/upstream-source-lock.json');parser.add_argument('--cache',type=Path,default=ROOT/'.cache/upstream-source-objects');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();collect(json.loads(args.lock.read_text(encoding='utf-8')),args.cache,args.output)
