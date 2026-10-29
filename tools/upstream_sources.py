#!/usr/bin/env python3
"""Deliver pinned upstream archives and their verbatim license/notice texts.

Only the sources listed as collected in the lock are delivered. External source
references are retained explicitly and never counted as collected archives.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import tarfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
MAX=1536*1024**2

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def validate(record):
    if not re.fullmatch(r'[a-z0-9-]+',record['name']) or record['file']!=record['name']+'-source.tar.gz':raise ValueError('Invalid source filename')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',record['repository']) or not re.fullmatch(r'[a-f0-9]{40}',record['commit']):raise ValueError('Invalid upstream source pin')
    if record['url']!=f'https://codeload.github.com/{record["repository"]}/tar.gz/{record["commit"]}':raise ValueError('Unexpected archive URL')
    if not re.fullmatch(r'[a-f0-9]{64}',record['sha256']) or type(record['size']) is not int or not 0<record['size']<=MAX:raise ValueError('Invalid source checksum/size')

class SourceRedirect(HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,newurl):
        parsed=urlsplit(newurl)
        if parsed.scheme!='https' or parsed.hostname!='codeload.github.com' or parsed.username or parsed.password or parsed.port not in (None,443):raise ValueError('Unexpected source redirect')
        return super().redirect_request(request,fp,code,message,headers,newurl)

def acquire(record,cache):
    validate(record);cache.mkdir(parents=True,exist_ok=True);target=cache/record['file']
    if target.is_symlink():raise ValueError('Source cache entry is a symlink')
    if target.exists():
        if target.stat().st_size!=record['size'] or digest(target)!=record['sha256']:raise ValueError('Cached upstream source changed')
        return target
    temporary=None
    try:
        with build_opener(SourceRedirect()).open(Request(record['url'],headers={'Accept-Encoding':'identity'}),timeout=60) as response,tempfile.NamedTemporaryFile(dir=cache,suffix='.partial',delete=False) as output:
            temporary=Path(output.name);count=0;checksum=hashlib.sha256()
            while block:=response.read(1024**2):
                count+=len(block)
                if count>record['size']:raise ValueError('Source exceeds pinned size')
                checksum.update(block);output.write(block)
        if count!=record['size'] or checksum.hexdigest()!=record['sha256']:raise ValueError('Source checksum/size mismatch')
        temporary.replace(target);return target
    finally:
        if temporary:temporary.unlink(missing_ok=True)

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
    output.mkdir(parents=True,exist_ok=False);records=[]
    with zipfile.ZipFile(output/'upstream-license-texts.zip','x',compression=zipfile.ZIP_DEFLATED) as license_zip:
        for record in lock['sources']:
            archive=acquire(record,cache);count=notices(archive,record['name'],license_zip)
            shutil.copyfile(archive,output/record['file']);records.append(dict(record,licenseTextFiles=count))
    result=dict(lock,sources=records,sourceArchivesCollected=len(records),fullReleaseAuditComplete=False)
    (output/'upstream-source-delivery.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'sourceArchivesCollected':len(records),'licenseTextFiles':sum(r['licenseTextFiles'] for r in records),'externalSourceReferences':len(lock.get('externalSourceReferences',[]))}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--lock',type=Path,default=ROOT/'runtime/upstream-source-lock.json');parser.add_argument('--cache',type=Path,default=ROOT/'.cache/upstream-source-objects');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();collect(json.loads(args.lock.read_text(encoding='utf-8')),args.cache,args.output)
