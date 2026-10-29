#!/usr/bin/env python3
"""Build public Pages download metadata from complete, published GitHub releases."""
import argparse
import json
from pathlib import Path
import re
import subprocess

REPOSITORY='B5-Software/opencode-android-full'
FILES={'apk':'opencode-android-full-arm64.apk','core':'opencode-android-full-arm64.ocaupdate','feed':'opencode-android-full-arm64.ocafeed'}
TAG=re.compile(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?')

def prepare(releases):
    if not isinstance(releases,list) or len(releases)>10000:raise ValueError('Invalid public release list')
    ready=[];total=0
    for release in releases:
        if release.get('draft'):continue
        assets=release.get('assets',[])
        for asset in assets:
            count=asset.get('download_count',0)
            if type(count) is not int or count<0:raise ValueError('Invalid download count')
            total+=count
        tag=release.get('tag_name','')
        if not isinstance(tag,str) or len(tag)>100 or not TAG.fullmatch(tag):continue
        rows={row.get('name'):row for row in assets};selected={}
        for kind,name in FILES.items():
            row=rows.get(name,{})
            url=f'https://github.com/{REPOSITORY}/releases/download/{tag}/{name}'
            digest=row.get('digest','')
            if row.get('state')!='uploaded' or row.get('browser_download_url')!=url or type(row.get('size')) is not int or not 0<row['size']<=2*1024**3 or not isinstance(digest,str) or not re.fullmatch(r'sha256:[a-f0-9]{64}',digest):break
            selected[kind]={'url':url,'size':row['size'],'sha256':digest.split(':')[1]}
        else:
            published=release.get('published_at','')
            if not isinstance(published,str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z',published):continue
            ready.append({'repository':REPOSITORY,'tag':tag,'prerelease':bool(release.get('prerelease')),'publishedAt':published,'releaseUrl':f'https://github.com/{REPOSITORY}/releases/tag/{tag}','assets':selected})
    if not ready:raise ValueError('No complete published APK/update/feed release')
    selected=max(ready,key=lambda row:row['publishedAt']);selected['downloadCount']=total
    return selected

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    if args.input:data=json.loads(args.input.read_text(encoding='utf-8-sig'))
    else:
        raw=subprocess.check_output(['gh','api',f'repos/{REPOSITORY}/releases?per_page=100','--paginate','--slurp'])
        if len(raw)>32*1024**2:raise ValueError('Release metadata exceeds bound')
        data=[release for page in json.loads(raw) for release in page]
    result=prepare(data);args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
    print(f'Public download metadata: {result["tag"]}, APK/update/feed ready')

if __name__=='__main__':main()
