#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).with_name('verify-play-bundle.py')
SPEC = importlib.util.spec_from_file_location('bundle', SCRIPT)
b = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(b)
MANIFEST = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.hebi.quickcut" android:versionCode="23" android:versionName="0.4.17"/>'


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.aab = Path(self.temp.name) / 'app.aab'
        self.create_bundle()

    def create_bundle(self, names=None):
        names = names or ['base/manifest/AndroidManifest.xml', 'base/lib/arm64-v8a/libquickcut_smartcut.so', 'base/lib/x86_64/libquickcut_smartcut.so']
        with zipfile.ZipFile(self.aab, 'w') as z:
            for name in names: z.writestr(name, b'payload')

    def test_accepts_matching_package_version_and_both_native_abis(self):
        with patch.object(b, 'run', return_value=MANIFEST) as runner:
            b.verify(self.aab, Path('bundletool.jar'), '0.4.17', 23, 'a'*64)
        self.assertTrue(any('dump' in args.args[0] for args in runner.call_args_list))

    def test_wrong_package_name_or_code_rejected(self):
        for manifest in (MANIFEST.replace('com.hebi.quickcut','com.other.app'), MANIFEST.replace('0.4.17','0.4.18'), MANIFEST.replace('"23"','"24"')):
            with self.subTest(manifest=manifest), patch.object(b,'run',return_value=manifest), self.assertRaises(ValueError):
                b.verify(self.aab, Path('tool.jar'), '0.4.17', 23, 'a'*64)

    def test_missing_native_library_or_unexpected_abi_rejected(self):
        for names in (['base/manifest/AndroidManifest.xml'], ['base/lib/armeabi-v7a/libquickcut_smartcut.so']):
            self.create_bundle(names)
            with self.subTest(names=names), patch.object(b,'run',return_value=MANIFEST), self.assertRaises(ValueError):
                b.verify(self.aab, Path('tool.jar'), '0.4.17', 23, 'a'*64)

    def test_signature_verifier_failure_prevents_manifest_acceptance(self):
        with patch.object(b, 'run', side_effect=ValueError('wrong signer')), self.assertRaises(ValueError):
            b.verify(self.aab, Path('tool.jar'), '0.4.17', 23, 'a'*64)

    def test_bundletool_hash_pin_rejects_changed_tool(self):
        tool = Path(self.temp.name) / 'tool.jar'
        tool.write_bytes(b'untrusted')
        with self.assertRaises(ValueError): b.verify_tool(tool)


class JarSignatureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.store = cls.root / 'fixture.p12'
        keytool = Path(os.path.realpath('/usr/bin/java')).parent / 'keytool'
        subprocess.run([str(keytool), '-genkeypair', '-alias','fixture','-keyalg','RSA','-keystore',str(cls.store),'-storepass','fixturepass','-keypass','fixturepass','-dname','CN=Test fixture','-validity','2'],check=True,capture_output=True)
        result = subprocess.run([str(keytool),'-list','-v','-keystore',str(cls.store),'-storepass','fixturepass'], check=True,capture_output=True,text=True).stdout
        cls.pin = next(line.split('SHA256:')[1].replace(':','').strip().lower() for line in result.splitlines() if 'SHA256:' in line)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def signed(self, name):
        jar = self.root / name
        with zipfile.ZipFile(jar,'w') as z: z.writestr('base/classes.dex',b'signed bytes')
        subprocess.run(['java','-m','jdk.jartool/sun.security.tools.jarsigner.Main','-keystore',str(self.store),'-storepass','fixturepass','-keypass','fixturepass',str(jar),'fixture'],check=True,capture_output=True)
        return jar

    def check_jar(self, jar, pin):
        return subprocess.run(['java',str(SCRIPT.with_name('VerifyBundleSignatures.java')),str(jar),pin],capture_output=True,text=True)

    def test_all_payload_signed_by_pinned_certificate_accepted(self):
        self.assertEqual(self.check_jar(self.signed('good.jar'), self.pin).returncode,0)

    def test_wrong_certificate_rejected(self):
        self.assertNotEqual(self.check_jar(self.signed('wrong.jar'), 'a'*64).returncode,0)

    def test_unsigned_added_payload_rejected(self):
        jar=self.signed('unsigned.jar')
        with zipfile.ZipFile(jar,'a') as z: z.writestr('base/unsigned.dex',b'unsigned')
        self.assertNotEqual(self.check_jar(jar,self.pin).returncode,0)

    def test_multiple_payload_signers_rejected(self):
        jar = self.signed('multiple.jar')
        keytool = Path(os.path.realpath('/usr/bin/java')).parent / 'keytool'
        subprocess.run([str(keytool), '-genkeypair', '-alias','second','-keyalg','RSA','-keystore',str(self.store),'-storepass','fixturepass','-keypass','fixturepass','-dname','CN=Second test fixture','-validity','2'],check=True,capture_output=True)
        subprocess.run(['java','-m','jdk.jartool/sun.security.tools.jarsigner.Main','-keystore',str(self.store),'-storepass','fixturepass','-keypass','fixturepass',str(jar),'second'],check=True,capture_output=True)
        self.assertNotEqual(self.check_jar(jar,self.pin).returncode,0)

    def test_tampered_payload_rejected(self):
        jar=self.signed('tampered.jar')
        with zipfile.ZipFile(jar) as z: entries={n:z.read(n) for n in z.namelist()}
        entries['base/classes.dex']=b'tampered'
        with zipfile.ZipFile(jar,'w') as z:
            for name,data in entries.items(): z.writestr(name,data)
        self.assertNotEqual(self.check_jar(jar,self.pin).returncode,0)


if __name__=='__main__': unittest.main()
