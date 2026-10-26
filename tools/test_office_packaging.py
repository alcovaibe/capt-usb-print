"""Metadata-only resource equivalence must not hide a payload/configuration change."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('office_assembly', Path(__file__).with_name('assemble-office-engine.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ResourceEquivalenceTest(unittest.TestCase):
    def test_zip_metadata_is_allowed_but_payload_change_is_not(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / str(i) for i in range(3)]
            for i,path in enumerate(paths):
                with zipfile.ZipFile(path,'w') as archive:
                    info = zipfile.ZipInfo('icon.png',(2020+i,1,1,0,0,0))
                    archive.writestr(info,b'same icon' if i < 2 else b'changed icon')
            location = Path('assets/share/config/images_colibre.zip')
            values = [module.shared_digest(p,location) for p in paths]
            self.assertEqual(values[0],values[1])
            self.assertNotEqual(values[0],values[2])
            # Other ZIP paths do not gain a broad equivalence exemption.
            self.assertNotEqual(module.shared_digest(paths[0],Path('other.zip')),
                                module.shared_digest(paths[1],Path('other.zip')))

    def test_only_empty_common_overlay_is_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / str(i) for i in range(3)]
            empty = b'<ns0:component-data ns0:name="Common" ns0:package="org.openoffice.Office" />'
            paths[0].write_bytes(b'<data><value>original</value></data>')
            paths[1].write_bytes(b'<data><value>original</value>'+empty+b'</data>')
            paths[2].write_bytes(b'<data><value>changed</value>'+empty+b'</data>')
            location = Path('assets_strippedUI/share/registry/main.xcd')
            values = [module.shared_digest(p,location) for p in paths]
            self.assertEqual(values[0],values[1])
            self.assertNotEqual(values[0],values[2])
            paths[2].write_bytes(empty+empty)
            with self.assertRaises(AssertionError): module.shared_digest(paths[2],location)


if __name__ == '__main__': unittest.main()
