#!/usr/bin/env python3
"""Collect notices for a manifest-based OpenCode runtime dependency superset.

Includes resolved optional/peer dependencies; this is not a compiler reachability
claim. Standard-license-only evidence and unresolved declarations stay explicit.
"""
import argparse
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote
import zipfile

from maven_materials import add_bytes,digest
from upstream_sources import source_git_command,source_git_environment

ROOT=Path(__file__).resolve().parents[1]
NOTICE=re.compile(r'^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE|OFL)(?:[._-].*)?$',re.I)
NAME=re.compile(r'(?:@[A-Za-z0-9_.-]+/)?[A-Za-z0-9_.-]+')

def read_json(path):
    if path.stat().st_size>1024**2:raise ValueError('Package metadata exceeds bound')
    return json.loads(path.read_text(encoding='utf-8'))

def resolve_dependency(directory,name,root):
    if not NAME.fullmatch(name):raise ValueError('Invalid package dependency name')
    for parent in [directory,*directory.parents]:
        try:parent.relative_to(root)
        except ValueError:break
        candidate=parent/'node_modules'/name/'package.json'
        if not candidate.is_file():continue
        resolved=candidate.resolve();resolved.relative_to(root)
        return resolved.parent
    return None

def discover(root):
    queue=[root/'packages/cli',root/'packages/app'];seen=set();records=[];missing=[]
    while queue:
        directory=queue.pop().resolve();directory.relative_to(root)
        if directory in seen:continue
        if len(seen)>=6000:raise ValueError('Dependency closure exceeds bound')
        seen.add(directory);metadata=read_json(directory/'package.json')
        if not NAME.fullmatch(metadata['name']) or not re.fullmatch('[A-Za-z0-9_.+~-]+',metadata['version']):raise ValueError('Invalid package identity')
        records.append((directory,metadata))
        required=metadata.get('dependencies',{});optional=metadata.get('optionalDependencies',{});peers=metadata.get('peerDependencies',{})
        for name in sorted(set(required)|set(optional)|set(peers)):
            dependency=resolve_dependency(directory,name,root)
            if dependency:queue.append(dependency)
            elif name not in optional and name not in peers:missing.append({'package':metadata['name'],'dependency':name})
    if missing:raise ValueError('Unresolved required manifest dependencies: '+json.dumps(missing[:20]))
    return records

def license_files(directory,root):
    result=[]
    for depth in range(3):
        for path in directory.glob('/'.join(['*']*depth+['*'])):
            if 'node_modules' in path.relative_to(directory).parts[:-1] or not path.is_file() or not NOTICE.fullmatch(path.name):continue
            path.resolve().relative_to(root)
            if path.stat().st_size>2*1024**2:raise ValueError('Package notice exceeds bound')
            result.append((str(path.relative_to(directory)).replace('\\','/'),path.read_bytes()))
    # Some published packages put their complete grant in the README.
    if not result:
        for path in sorted(directory.iterdir()):
            if path.name.lower() not in ('readme.md','readme') or not path.is_file() or path.stat().st_size>2*1024**2:continue
            path.resolve().relative_to(root)
            data=path.read_bytes()
            if b'permission is hereby granted' in data.lower() and b'copyright' in data.lower():result.append((path.name,data))
    return sorted(result)

def collect(root,lock,output):
    root=root.resolve();upstream=read_json(ROOT/'runtime/opencode-dev.lock.json')
    head=subprocess.check_output(source_git_command(root,'rev-parse','HEAD'),env=source_git_environment(),text=True).strip()
    if head!=upstream['commit']:raise ValueError('Notice source differs from the packaged official commit')
    root_license=subprocess.check_output(source_git_command(root,'show',head+':LICENSE'),env=source_git_environment())
    packages=discover(root);supplements={};standards={}
    for item in lock['supplements']:
        path=ROOT/'third_party/npm-notices'/item['sha256'];data=path.read_bytes()
        if len(data)!=item['size'] or digest(path)!=item['sha256']:raise ValueError('Repository notice differs from its pin')
        for package in item['packages']:
            group=supplements.setdefault((package['name'],package['version']),[])
            if not any(previous['sha256']==item['sha256'] for previous,_ in group):group.append((item,data))
    for item in lock['standardTexts']:
        path=ROOT/'third_party/common'/item['file'];data=path.read_bytes()
        if len(data)!=item['size'] or digest(path)!=item['sha256']:raise ValueError('Standard license text differs from its pin')
        standards[item['id']]=data
    output.mkdir(parents=True,exist_ok=False);records={};review=[]
    with zipfile.ZipFile(output/'npm-license-texts.zip','x') as archive:
        font_lock=read_json(ROOT/'third_party/font-notices.lock.json')
        for font in font_lock['fonts']:
            path=(root/font['path']).resolve();path.relative_to(root)
            if path.stat().st_size!=font['size'] or digest(path)!=font['sha256']:raise ValueError('Official font differs from its notice pairing')
            add_bytes(archive,'fonts/'+Path(font['path']).name+'.json',(json.dumps(font,ensure_ascii=False,indent=2)+'\n').encode())
        add_bytes(archive,'fonts/OFL-1.1.txt',standards['OFL-1.1'])
        for directory,metadata in sorted(packages,key=lambda item:str(item[0])):
            key=hashlib.sha256(json.dumps(metadata,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
            identity=metadata['name']+'@'+metadata['version']+'/'+key[:24]
            if identity in records:continue
            prefix='npm/'+quote(metadata['name'],safe='')+'/'+metadata['version']+'/'+key[:24]
            provided=license_files(directory,root);evidence=[]
            if provided:evidence.append('packaged-notice-text')
            if not provided and directory.is_relative_to(root/'packages'):
                provided=[('upstream-root-LICENSE',root_license)];evidence.append('official-workspace-root-license')
            if not provided:
                for item,data in supplements.get((metadata['name'],metadata['version']),[]):
                    provided.append(('repository-'+item['sha256']+'.txt',data));evidence.append('pinned-repository-notice')
            # ffi-rs publishes its native files as separate optional packages.
            # Retain the exact matching parent suite's supplied license too.
            if not provided and metadata['name'].startswith('@yuuang/ffi-rs-'):
                parent=next(((d,m) for d,m in packages if m['name']=='ffi-rs' and m['version']==metadata['version']),None)
                if parent and parent[1].get('optionalDependencies',{}).get(metadata['name'])==metadata['version']:
                    provided=license_files(parent[0],root);evidence.append('matching-optional-package-suite-notice')
            declared=metadata.get('license');standard=standards.get({'apache-2.0':'Apache-2.0'}.get(str(declared),str(declared)))
            declaration_only=not provided
            if declaration_only:
                if not standard:raise ValueError('Package lacks a collected notice or known declared standard: '+identity)
                evidence.append('declared-standard-license-only');review.append({'name':metadata['name'],'version':metadata['version'],'reason':'Publisher declaration/attribution metadata retained; no package/repository-specific license file collected'})
            for name,data in provided:add_bytes(archive,prefix+'/'+name,data)
            if standard:add_bytes(archive,prefix+'/declared-standard-license.txt',standard)
            published={k:metadata[k] for k in ('name','version','description','license','author','contributors','repository','homepage') if k in metadata}
            add_bytes(archive,prefix+'/published-attribution.json',(json.dumps(published,ensure_ascii=False,indent=2)+'\n').encode())
            record={'SPDXID':'SPDXRef-NPM-'+hashlib.sha256(identity.encode()).hexdigest()[:24],'name':metadata['name'],'version':metadata['version'],'metadataSha256':key,'licenseDeclaredByPublisher':declared,'licenseConcluded':'NOASSERTION','noticeEvidence':sorted(set(evidence)),'declarationOnly':declaration_only,'noticeFiles':len(provided),'archivePrefix':prefix}
            records[identity]=record
        inventory={'format':1,'scope':'Manifest-based superset of official CLI/WebUI dependencies, including resolved optional/peer/build modules; not a compiler-proven bundled footprint','sourceCommit':head,'packages':list(records.values()),'packagesCollected':len(records),'declarationOnlyReview':review,'fullReleaseAuditComplete':False}
        data=(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n').encode();add_bytes(archive,'npm-notice-inventory.json',data)
    (output/'npm-notice-inventory.json').write_bytes(data)
    packages_spdx=[{'SPDXID':r['SPDXID'],'name':r['name'],'versionInfo':r['version'],'downloadLocation':'NOASSERTION','filesAnalyzed':False,'licenseDeclared':'NOASSERTION','licenseConcluded':'NOASSERTION','copyrightText':'NOASSERTION','licenseComments':'Publisher declaration and notice evidence: '+r['archivePrefix']+'/published-attribution.json','externalRefs':[{'referenceCategory':'PACKAGE-MANAGER','referenceType':'purl','referenceLocator':'pkg:npm/'+quote(r['name'],safe='/')+'@'+quote(r['version'],safe='') }]} for r in records.values()]
    namespace=hashlib.sha256(data).hexdigest();document={'spdxVersion':'SPDX-2.3','dataLicense':'CC0-1.0','SPDXID':'SPDXRef-DOCUMENT','name':'OpenCode manifest dependency notice superset','documentNamespace':'https://spdx.org/spdxdocs/opencode-npm-'+namespace,'creationInfo':{'creators':['Tool: opencode-android-npm-notices-1'],'created':datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')},'comment':inventory['scope'],'packages':packages_spdx,'relationships':[{'spdxElementId':'SPDXRef-DOCUMENT','relationshipType':'DESCRIBES','relatedSpdxElement':r['SPDXID']} for r in records.values()]}
    (output/'npm-components.spdx.json').write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'packagesCollected':len(records),'declarationOnlyReview':len(review),'fullReleaseAuditComplete':False}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,default=ROOT/'.cache/opencode-upstream');parser.add_argument('--lock',type=Path,default=ROOT/'third_party/npm-notices.lock.json');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();collect(args.source,read_json(args.lock),args.output)
