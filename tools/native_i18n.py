"""Extract/rebuild native Android messages. Never edits the bundled OpenCode source.

The committed catalog and Android XML are the build inputs. Extraction is a maintainer
operation; normal builds and CI do not need an upstream checkout or a translation API.
"""
from pathlib import Path
import argparse, hashlib, json, re
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'app/src/main/java/io/github/opencodeandroid'
CATALOG = ROOT / 'app/src/main/i18n/catalog.json'
TECHNICAL_TERMS = ['OpenCode','Debian','Chromium','Linux','Android','PRoot','WebView','XFCE','UTF-8','ARM64','APK','SSH','API','HTTPS','LSP','clangd','pylsp','rootfs','helloworld','/workspace','/root','.ocaupdate','.ocafeed','16 KiB','2 MB']

def literal(code, start):
    """Read a Kotlin quoted string, including nested interpolation expressions."""
    i, piece, parts, arguments = start + 1, '', [], []
    if code.startswith('"""', start):
        end = code.index('"""', start + 3) + 3
        return end, [code[start + 3:end - 3]], []
    while i < len(code):
        c = code[i]
        if c == '"': return i + 1, parts + [piece], arguments
        if c == '\\':
            value = code[i + 1]
            if value == 'u': piece += chr(int(code[i + 2:i + 6], 16)); i += 6
            else: piece += {'n':'\n','r':'\r','t':'\t','b':'\b'}.get(value, value); i += 2
            continue
        if c == '$' and code[i + 1:i + 2] == '{':
            j, depth = i + 2, 1
            while depth:
                if code[j] == '"': j = literal(code, j)[0]; continue
                if code[j] == '{': depth += 1
                if code[j] == '}': depth -= 1
                j += 1
            parts.append(piece); piece = ''; arguments.append(code[i + 2:j - 1]); i = j; continue
        if c == '$' and (match := re.match(r'[A-Za-z_]\w*', code[i + 1:])):
            parts.append(piece); piece = ''; arguments.append(match[0]); i += len(match[0]) + 1; continue
        piece += c; i += 1
    raise ValueError('Unterminated Kotlin string')

def tokens(code):
    i = 0
    while i < len(code):
        if code.startswith('//', i):
            end = code.find('\n', i); i = len(code) if end < 0 else end; continue
        if code.startswith('/*', i):
            end, depth = i + 2, 1
            while depth:
                if code.startswith('/*', end): depth += 1; end += 2
                elif code.startswith('*/', end): depth -= 1; end += 2
                else: end += 1
            i = end; continue
        if code[i] == '"':
            end, parts, args = literal(code, i); yield i, end, parts, args; i = end
        elif code[i] == "'":
            i += 1
            while i < len(code):
                if code[i] == '\\': i += 2
                elif code[i] == "'": i += 1; break
                else: i += 1
        else: i += 1

def key(parts):
    return 'native_' + hashlib.sha256(json.dumps(parts, ensure_ascii=False).encode()).hexdigest()[:12]

def extract():
    entries = {entry['key']: entry for entry in json.loads(CATALOG.read_text(encoding='utf-8'))} if CATALOG.exists() else {}
    for path in sorted(SOURCE.rglob('*.kt')):
        if 'i18n' in path.parts: continue
        code = path.read_text(encoding='utf-8')
        for start, end, parts, args in tokens(code):
            if not re.search('[\u4e00-\u9fff]', ''.join(parts)): continue
            identity = key(parts)
            source = ''.join(p + (f'%{i + 1}$s' if i < len(args) else '') for i, p in enumerate(parts))
            entries.setdefault(identity, {'key': identity, 'source': source, 'parts': parts, 'en': '', 'references': []})['references'].append(str(path.relative_to(SOURCE)) + ':' + str(code.count('\n', 0, start) + 1))
    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(json.dumps(list(entries.values()), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def xml_text(value):
    result = escape(value.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n').replace('\t', '\\t'))
    return '"' + result + '"' if value != value.strip() else result

def write_resources():
    entries = json.loads(CATALOG.read_text(encoding='utf-8'))
    locale_tags = ['en', 'zh-Hans']
    translations = [('values', {e['key']: e['en'] for e in entries}), ('values-zh', {e['key']: e['source'] for e in entries})]
    for path in sorted((CATALOG.parent / 'locales').glob('*.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        if not re.fullmatch(r'(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9]+(?:\+[A-Za-z0-9]+)*)', data['androidQualifier']): raise ValueError('Invalid Android locale qualifier')
        if len(data['translations']) != len(entries):
            # A language is promoted only when its entire catalog is available.
            (ROOT / 'app/src/main/res' / ('values-' + data['androidQualifier']) / 'native_strings.xml').unlink(missing_ok=True)
            continue
        locale_tags.append({'no':'nb', 'tl':'fil', 'zh-TW':'zh-Hant'}.get(data['language'], data['language']))
        translations.append(('values-' + data['androidQualifier'], data['translations']))
    for locale, messages in translations:
        lines = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Native application messages. Edit app/src/main/i18n/catalog.json, then run tools/native_i18n.py resources. -->', '<resources>']
        for entry in entries:
            value = messages.get(entry['key'])
            if not value:
                if locale in ('values', 'values-zh'): raise ValueError('Missing translation: ' + entry['source'])
                continue
            # Escape literal percent signs only for formatted messages.
            formatted = len(entry['parts']) > 1
            if formatted: value = re.sub(r'%(?![1-9]\d*\$s)', '%%', value)
            lines.append(f'    <string name="{entry["key"]}"' + ('' if formatted else ' formatted="false"') + '>' + xml_text(value) + '</string>')
        directory = ROOT / 'app/src/main/res' / locale; directory.mkdir(exist_ok=True)
        (directory / 'native_strings.xml').write_text('\n'.join(lines + ['</resources>', '']), encoding='utf-8')
    config = ROOT / 'app/src/main/res/xml/native_locales.xml'
    config.write_text('<?xml version="1.0" encoding="utf-8"?>\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n' + ''.join(f'    <locale android:name="{tag}" />\n' for tag in locale_tags) + '</locale-config>\n', encoding='utf-8')
    tags = ROOT / 'app/src/main/res/values/native_locale_tags.xml'
    tags.write_text('<resources>\n    <string-array name="native_locale_tags" translatable="false">\n' + ''.join(f'        <item>{tag}</item>\n' for tag in locale_tags) + '    </string-array>\n</resources>\n', encoding='utf-8')
    directory = SOURCE / 'i18n'; directory.mkdir(exist_ok=True)
    lines = ['package io.github.opencodeandroid.i18n', '', 'import io.github.opencodeandroid.R', '', '// Generated from the native message catalog; internal service identifiers stay unchanged.', 'internal val NativeMessages = listOf(']
    def kotlin(value): return json.dumps(value, ensure_ascii=False).replace('$', '\\$')
    for entry in entries:
        lines.append(f'    NativeMessage(R.string.{entry["key"]}, listOf(' + ', '.join(kotlin(p) for p in entry['parts']) + ')),')
    (directory / 'NativeMessageCatalog.kt').write_text('\n'.join(lines + [')', '']), encoding='utf-8')

def migrate_ui():
    # Only presentation functions; never convert enum identifiers, comparisons,
    # raw terminal/file contents, service phases, or operation state machines.
    paths = [p for p in SOURCE.rglob('*.kt') if '@Composable' in p.read_text(encoding='utf-8') and 'i18n' not in p.parts]
    for path in paths:
        code = path.read_text(encoding='utf-8'); strings = list(tokens(code)); mask = list(code)
        for start, end, *_ in strings: mask[start:end] = ' ' * (end-start)
        masked = ''.join(mask); edits=[]; insertions=[]
        for match in re.finditer(r'@Composable\s+(?:(?:internal|private|public)\s+)?fun\s+(\w+)\s*\(', masked):
            pos, depth = match.end(), 1
            while depth:
                if masked[pos]=='(': depth+=1
                if masked[pos]==')': depth-=1
                pos+=1
            body = masked.find('{', pos)
            if body < 0 or '=' in masked[pos:body]: continue
            end, depth = body + 1, 1
            while depth:
                if masked[end]=='{': depth+=1
                if masked[end]=='}': depth-=1
                end+=1
            changed = False
            for start, stop, parts, args in strings:
                if not body < start < stop < end or not re.search('[\u4e00-\u9fff]', ''.join(parts)): continue
                if re.search(r'(?:==|!=)\s*$', code[max(body,start-8):start]): continue
                replacement = f'strings.text(R.string.{key(parts)}' + ''.join(', ' + arg for arg in args) + ')'
                edits.append((start,stop,replacement)); changed=True
            if changed: insertions.append((body+1,body+1,'\n    val strings = rememberNativeStrings()'))
        if not edits: continue
        for start, end, replacement in sorted(edits+insertions, reverse=True): code=code[:start]+replacement+code[end:]
        imports = 'import io.github.opencodeandroid.i18n.rememberNativeStrings\n'
        if path.parent != SOURCE: imports += 'import io.github.opencodeandroid.R\n'
        pos = code.index('\n')+1; code=code[:pos]+'\n'+imports+code[pos:]
        path.write_text(code,encoding='utf-8',newline='\n')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('action',choices=['extract','resources','migrate-ui']); args=parser.parse_args()
    {'extract':extract,'resources':write_resources,'migrate-ui':migrate_ui}[args.action]()
