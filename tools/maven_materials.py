#!/usr/bin/env python3
"""Collect original published Maven sources/notices for the actual release graph.

The private Gradle input file contains cache paths. Published records never do.
License declarations and original text are evidence, not compatibility verdicts.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path,PurePosixPath
import re
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler,Request,build_opener
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
MAX_OBJECT=64*1024**2

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def source_url(url):
    parsed=urlsplit(url)
    if parsed.scheme!='https' or parsed.hostname not in ('repo.maven.apache.org','dl.google.com') or parsed.port not in (None,443) or parsed.username or parsed.password or parsed.query or parsed.fragment:raise ValueError('Unexpected Maven source origin')
    return url

class Redirect(HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,newurl):
        source_url(newurl);return super().redirect_request(request,fp,code,message,headers,newurl)

def acquire(record,cache):
    source_url(record['url'])
    if not re.fullmatch('[a-f0-9]{64}',record['sha256']) or type(record['size']) is not int or not 0<record['size']<=MAX_OBJECT:raise ValueError('Invalid Maven material pin')
    path=cache/record['sha256'];cache.mkdir(parents=True,exist_ok=True)
    if path.is_symlink():raise ValueError('Maven object must not be a symlink')
    if path.exists():
        if path.stat().st_size!=record['size'] or digest(path)!=record['sha256']:raise ValueError('Cached Maven material differs')
        return path
    temporary=None
    try:
        with build_opener(Redirect()).open(Request(record['url'],headers={'Accept-Encoding':'identity'}),timeout=45) as source,tempfile.NamedTemporaryFile(dir=cache,suffix='.partial',delete=False) as output:
            temporary=Path(output.name);count=0;checksum=hashlib.sha256()
            while block:=source.read(1024**2):
                count+=len(block)
                if count>record['size']:raise ValueError('Maven download exceeds pinned size')
                checksum.update(block);output.write(block)
        if count!=record['size'] or checksum.hexdigest()!=record['sha256']:raise ValueError('Maven material checksum mismatch')
        temporary.replace(path);return path
    finally:
        if temporary:temporary.unlink(missing_ok=True)

def safe_member(name):
    path=PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or any(ord(c)<32 for c in name):raise ValueError('Unsafe Maven archive entry')
    return str(path)

def add_bytes(target,name,data):
    info=zipfile.ZipInfo(safe_member(name),(1980,1,1,0,0,0));info.external_attr=0o100644<<16;info.compress_type=zipfile.ZIP_DEFLATED;target.writestr(info,data)

def archive_notices(source,target,prefix,headers=False):
    count=0;total=0
    with zipfile.ZipFile(source) as archive:
        if len(archive.infolist())>100000:raise ValueError('Too many Maven archive entries')
        for item in archive.infolist():
            name=safe_member(item.filename);total+=item.file_size
            if item.file_size>MAX_OBJECT or total>512*1024**2:raise ValueError('Maven archive bounds exceeded')
            if item.is_dir():continue
            if re.match(r'^(LICEN[CS]E|NOTICE|COPYING|COPYRIGHT|OFL)(?:[._-].*)?$',PurePosixPath(name).name,re.I):
                if item.file_size>2*1024**2:raise ValueError('Maven notice too large')
                add_bytes(target,prefix+'/'+name,archive.read(item));count+=1
            elif headers and name.endswith(('.java','.kt')):
                # Preserve attribution/license headers, including BSD grants in
                # jsr305 sources. Full original source JARs are delivered too.
                with archive.open(item) as stream:data=stream.read(32768)
                stripped=data.lstrip()
                if stripped.startswith(b'/*') and b'*/' in stripped:
                    header=stripped[:stripped.index(b'*/')+2]
                    if b'copyright' in header.lower() or b'licens' in header.lower():
                        add_bytes(target,prefix+'/source-headers/'+name+'.txt',header+b'\n');count+=1
            elif not headers and PurePosixPath(name).name=='classes.jar':
                count+=archive_notices(io.BytesIO(archive.read(item)),target,prefix+'/classes.jar',True)
    return count

def identity(record):
    values=[record[k] for k in ('group','name','version')]
    if not all(isinstance(v,str) and re.fullmatch('[A-Za-z0-9_.+-]+',v) for v in values):raise ValueError('Invalid Maven coordinate')
    return ':'.join(values)

def collect(lock,inputs,cache,output):
    if lock['format']!=1 or not 0<len(lock['components'])<=1000:raise ValueError('Invalid Maven source lock')
    actual={identity(r):r for r in inputs};expected={identity(r):r for r in lock['components']}
    if len(expected)!=len(lock['components']) or set(actual)!=set(expected):raise ValueError('Actual release dependency graph differs from the source lock')
    for record in inputs:
        if any(record[k]!=actual[identity(record)][k] for k in ('sha256','size','extension')):raise ValueError('Ambiguous Maven binary identity')
    for name,record in expected.items():
        artifact=actual[name];path=Path(artifact['file'])
        if artifact['sha256']!=record['binarySha256'] or path.stat().st_size!=record['binarySize'] or digest(path)!=record['binarySha256']:raise ValueError('Actual Maven binary differs from its source pairing')
    output.mkdir(parents=True,exist_ok=False);records=[]
    with zipfile.ZipFile(output/'maven-license-texts.zip','x') as notices,zipfile.ZipFile(output/'maven-sources.zip','x') as sources:
        for coordinate,record in sorted(expected.items()):
            prefix=coordinate.replace(':','/');pom=acquire(record['pom'],cache);source=acquire(record['sources'],cache)
            text=pom.read_bytes()
            if len(text)>2*1024**2 or b'<!DOCTYPE' in text or b'<!ENTITY' in text:raise ValueError('Unsupported Maven POM')
            document=ET.fromstring(text)
            declarations=[{'name':item.findtext('{*}name'),'url':item.findtext('{*}url')} for item in document.findall('./{*}licenses/{*}license')]
            add_bytes(notices,prefix+'/pom.xml',text);add_bytes(sources,prefix+'/pom.xml',text);add_bytes(sources,prefix+'/sources.jar',source.read_bytes())
            count=archive_notices(source,notices,prefix,True)+archive_notices(Path(actual[coordinate]['file']),notices,prefix+'/binary')
            records.append(dict(record,licenseDeclarations=declarations,licenseTextFiles=count,licenseConcluded='NOASSERTION'))
        report={'format':1,'scope':lock['scope'],'components':records,'actualArtifactsVerified':len(records),'sourceArchivesCollected':len(records),'privatePathsIncluded':False,'fullReleaseAuditComplete':False}
        add_bytes(notices,'maven-source-delivery.json',(json.dumps(report,indent=2)+'\n').encode())
    (output/'maven-source-delivery.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'actualArtifactsVerified':len(records),'sourceArchivesCollected':len(records),'licenseTextFiles':sum(r['licenseTextFiles'] for r in records)}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--lock',type=Path,default=ROOT/'third_party/maven-sources.lock.json');parser.add_argument('--inputs',type=Path,default=ROOT/'.cache/maven-release-artifacts.json');parser.add_argument('--cache',type=Path,default=ROOT/'.cache/maven-source-objects');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();collect(json.loads(args.lock.read_text(encoding='utf-8')),json.loads(args.inputs.read_text(encoding='utf-8')),args.cache,args.output)
