"""Check the notice archives actually embedded in an APK."""
import io
import json
import zipfile
from maven_materials import safe_member,identity

def check_notices(apk,commit,maven_lock):
    documents={}
    for kind,name in [('npm','npm-license-texts.zip'),('maven','maven-license-texts.zip')]:
        path='assets/licenses/dependencies/'+name;entry=apk.getinfo(path)
        if not 0<entry.file_size<=32*1024**2:raise ValueError('Invalid embedded notice archive size')
        with zipfile.ZipFile(io.BytesIO(apk.read(entry))) as archive:
            members=archive.infolist()
            if len(members)>20000 or sum(m.file_size for m in members)>128*1024**2:raise ValueError('Embedded notice archive bounds exceeded')
            if len({m.filename for m in members})!=len(members):raise ValueError('Duplicate embedded notice entry')
            for member in members:safe_member(member.filename)
            if archive.testzip() is not None:raise ValueError('Corrupt embedded notice archive')
            report='npm-notice-inventory.json' if kind=='npm' else 'maven-source-delivery.json'
            if archive.getinfo(report).file_size>4*1024**2:raise ValueError('Embedded notice metadata exceeds bound')
            documents[kind]=json.loads(archive.read(report))
    if documents['npm']['sourceCommit']!=commit:raise ValueError('Embedded npm notices describe another official source revision')
    expected={identity(r):(r['binarySha256'],r['binarySize']) for r in maven_lock['components']}
    actual={identity(r):(r['binarySha256'],r['binarySize']) for r in documents['maven']['components']}
    if expected!=actual or documents['maven'].get('privatePathsIncluded') is not False:raise ValueError('Embedded Maven notices differ from the actual locked artifacts')
    return documents
