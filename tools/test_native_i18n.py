import collections
import hashlib
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET
from native_i18n import TECHNICAL_TERMS

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / 'app/src/main/i18n/catalog.json'
PLACEHOLDERS = re.compile(r'%([1-9]\d*)\$s')

class NativeLocalizationTests(unittest.TestCase):
    def test_catalog_has_complete_english_and_chinese_with_stable_placeholders(self):
        entries = json.loads(CATALOG.read_text(encoding='utf-8'))
        self.assertGreater(len(entries), 500)
        self.assertEqual(len(entries), len({e['key'] for e in entries}))
        for entry in entries:
            with self.subTest(key=entry['key']):
                self.assertTrue(entry['en'].strip())
                self.assertEqual(collections.Counter(PLACEHOLDERS.findall(entry['source'])), collections.Counter(PLACEHOLDERS.findall(entry['en'])))
                self.assertEqual(set(range(1,len(entry['parts']))), {int(i) for i in PLACEHOLDERS.findall(entry['en'])})
    def test_committed_language_packs_preserve_the_entire_message_set(self):
        entries = json.loads(CATALOG.read_text(encoding='utf-8'))
        expected = {e['key']:e for e in entries}
        source_hash = hashlib.sha256(json.dumps([(e['key'],e['en']) for e in entries],ensure_ascii=False).encode()).hexdigest()
        for path in (CATALOG.parent / 'locales').glob('*.json'):
            data = json.loads(path.read_text(encoding='utf-8'))
            self.assertEqual(source_hash,data['sourceSha256'],str(path))
            self.assertEqual(set(expected), set(data['translations']))
            for key, translated in data['translations'].items():
                with self.subTest(locale=data['language'],key=key):
                    self.assertTrue(translated.strip())
                    self.assertNotIn('ZXQ', translated)
                    self.assertNotRegex(translated,r'\[\[\d{4}\]\]')
                    self.assertEqual(collections.Counter(PLACEHOLDERS.findall(expected[key]['en'])), collections.Counter(PLACEHOLDERS.findall(translated)))
                    for term in TECHNICAL_TERMS:
                        self.assertEqual(expected[key]['en'].count(term),translated.count(term),(data['language'],key,term))
    def test_declared_locales_have_complete_resource_files(self):
        entries=json.loads(CATALOG.read_text(encoding='utf-8'))
        expected={e['key'] for e in entries}
        for path in (ROOT/'app/src/main/res').glob('values*/native_strings.xml'):
            self.assertEqual(expected,{node.attrib['name'] for node in ET.parse(path).getroot()},str(path))
        declared=[node.attrib['{http://schemas.android.com/apk/res/android}name'] for node in ET.parse(ROOT/'app/src/main/res/xml/native_locales.xml').getroot()]
        tags=[node.text for node in ET.parse(ROOT/'app/src/main/res/values/native_locale_tags.xml').getroot().find('string-array')]
        self.assertEqual(declared,tags)
        self.assertEqual(len(declared),len(set(declared)))
        self.assertIn('en',declared);self.assertIn('zh-Hans',declared)

if __name__=='__main__':unittest.main()
