"""Release selection must retain a working pair while a new upload is incomplete."""
import copy
import unittest
import site_release as site

class SiteReleaseTest(unittest.TestCase):
    def release(self,tag,date):
        return {'tag_name':tag,'published_at':date,'draft':False,'prerelease':True,'assets':[
            {'name':name,'state':'uploaded','size':100,'download_count':3,'digest':'sha256:'+'a'*64,'browser_download_url':f'https://github.com/{site.REPOSITORY}/releases/download/{tag}/{name}'} for name in site.FILES.values()]}
    def test_prerelease_is_downloadable_and_partial_publication_keeps_previous_pair(self):
        previous=self.release('v0.1.0-beta.1','2026-10-29T20:40:12Z')
        current=self.release('v0.1.0-beta.2','2026-10-30T01:00:00Z');current['assets'][1]['state']='new'
        self.assertEqual(previous['tag_name'],site.prepare([current,previous])['tag'])
        current['assets'][1]['state']='uploaded'
        result=site.prepare([previous,current]);self.assertEqual(current['tag_name'],result['tag']);self.assertEqual(18,result['downloadCount'])
    def test_unpublished_external_or_unsigned_metadata_cannot_supply_download_links(self):
        release=self.release('v0.1.0-beta.1','2026-10-29T20:40:12Z')
        for mutate in [lambda r:r.update(draft=True),lambda r:r.update(tag_name='../../escape'),lambda r:r['assets'][0].update(browser_download_url='https://example.invalid/file.apk'),lambda r:r['assets'][0].update(digest=None),lambda r:r['assets'][0].update(size=True)]:
            candidate=copy.deepcopy(release);mutate(candidate)
            with self.assertRaises(ValueError):site.prepare([candidate])
    def test_source_files_are_not_presented_as_apk_and_no_complete_release_fails(self):
        release=self.release('v0.1.0-beta.1','2026-10-29T20:40:12Z');release['assets'][0]['name']='source.tar.gz'
        with self.assertRaises(ValueError):site.prepare([release])
        with self.assertRaises(ValueError):site.prepare([])

if __name__=='__main__':unittest.main()
