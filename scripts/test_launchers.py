"""Launcher contracts against the assembled application; requires a supported Java installation."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = os.name == 'nt'
JAVA_HOME = Path(os.environ['OCELOT_TEST_JAVA_HOME'])
JAVA = JAVA_HOME / 'bin' / ('java.exe' if WINDOWS else 'java')


class Launchers(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ocelot launchers with spaces ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'application with spaces'
        self.bin = self.root / 'bin'
        self.bin.mkdir(parents=True)
        for p in (ROOT / 'scripts').iterdir():
            if p.is_file() and (p.name.startswith(('ocelot', 'run-harness', 'java-common'))):
                shutil.copy2(p, self.bin / p.name)
        (self.root / 'lib').mkdir()
        shutil.copyfile(ROOT / 'modules/app/target/ocelot-harness.jar', self.root / 'lib/ocelot-harness.jar')
        fake = self.base / 'fake path'
        fake.mkdir()
        self.fake = fake / ('java.cmd' if WINDOWS else 'java')
        self.fake.write_text('@echo off\necho openjdk version "1.7.0" 1>&2\nexit /b 0\n' if WINDOWS else '#!/bin/sh\necho \'openjdk version "1.7.0"\' >&2\nexit 0\n')
        self.fake.chmod(0o755)
        self.env = dict(os.environ)
        for key in ('JAVA_HOME', 'OCELOT_JAVA', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
            self.env.pop(key, None)
        self.env['PATH'] = str(fake) + os.pathsep + self.env['PATH']

    def run_cli(self, *args, launcher='ocelotctl'):
        script = self.bin / (launcher + ('.cmd' if WINDOWS else ''))
        command = ['cmd.exe', '/d', '/c', 'call', str(script), *args] if WINDOWS else ['sh', str(script), *args]
        return subprocess.run(command, env=self.env, cwd=self.base, capture_output=True, text=True, timeout=30)

    def test_java_home_overrides_unsupported_path_java(self):
        self.env['JAVA_HOME'] = str(JAVA_HOME)
        p = self.run_cli('--help')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('project init', p.stdout)

    def test_explicit_java_overrides_bad_java_home(self):
        (self.root / 'runtime').mkdir()
        self.env.update(OCELOT_JAVA=str(JAVA), JAVA_HOME=str(self.base / 'missing'))
        p = self.run_cli('--help')
        self.assertEqual(p.returncode, 0, p.stderr)

    def test_invalid_explicit_java_does_not_fall_back(self):
        self.env.update(OCELOT_JAVA=str(self.base / 'missing java'), JAVA_HOME=str(JAVA_HOME))
        p = self.run_cli('--help')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('OCELOT_JAVA', p.stderr)

    def test_unsupported_explicit_java_is_actionable(self):
        self.env.update(OCELOT_JAVA=str(self.fake), JAVA_HOME=str(JAVA_HOME))
        p = self.run_cli('--help')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('Java 8 or newer is required', p.stderr)

    def test_unsupported_path_java_is_rejected(self):
        self.bundle_runtime()
        p = self.run_cli('--help')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('Java 8 or newer is required', p.stderr)

    def test_supported_versions_reach_application_and_preserve_exit(self):
        for version in ('1.8.0_504', '17.0.20.1', '21.0.12.1', '25'):
            with self.subTest(version=version):
                if WINDOWS:
                    body = f'@echo off\nif "%~1"=="-version" (\necho openjdk version "{version}" 1>&2\nexit /b 0\n)\nexit /b 37\n'
                else:
                    body = f'#!/bin/sh\nif [ "$1" = "-version" ]; then\necho \'openjdk version "{version}"\' >&2\nexit 0\nfi\nexit 37\n'
                self.fake.write_text(body)
                self.env['OCELOT_JAVA'] = str(self.fake)
                p = self.run_cli('--help')
                self.assertEqual(p.returncode, 37, p.stderr)

    def bundle_runtime(self):
        runtime = self.root / 'runtime'
        if WINDOWS:
            subprocess.run(['cmd.exe', '/d', '/c', 'mklink', '/J', str(runtime), str(JAVA_HOME)], check=True, capture_output=True)
            self.addCleanup(os.rmdir, runtime)
        else:
            runtime.symlink_to(JAVA_HOME, target_is_directory=True)
            self.addCleanup(runtime.unlink)

    def remove_path_java(self):
        if WINDOWS:
            system = Path(os.environ['SystemRoot']) / 'System32'
            self.env['PATH'] = str(system) + os.pathsep + str(system / 'WindowsPowerShell/v1.0')
        else:
            tools = self.base / 'system tools'
            tools.mkdir()
            for name in ('sh', 'dirname', 'sed', 'head'):
                (tools / name).symlink_to(shutil.which(name))
            self.env['PATH'] = str(tools)

    def test_java_home_precedes_bundled_runtime(self):
        (self.root / 'runtime').mkdir()  # A broken bundle must not affect installed Java.
        self.env['JAVA_HOME'] = str(JAVA_HOME)
        p = self.run_cli('--help')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('project init', p.stdout)

    def test_path_java_precedes_bundled_runtime(self):
        (self.root / 'runtime').mkdir()
        self.env['PATH'] = str(JAVA.parent) + os.pathsep + self.env['PATH']
        p = self.run_cli('--help')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('project init', p.stdout)

    def test_bundled_runtime_is_used_without_system_java(self):
        self.bundle_runtime()
        self.remove_path_java()
        p = self.run_cli('--help')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('project init', p.stdout)

    def test_invalid_java_home_does_not_silently_fall_back(self):
        self.bundle_runtime()
        self.env['JAVA_HOME'] = str(self.base / 'missing')
        p = self.run_cli('--help')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('JAVA_HOME', p.stderr)

    def test_missing_system_and_bundled_java_is_actionable(self):
        self.remove_path_java()
        p = self.run_cli('--help')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('Java 8 or newer is required', p.stderr)

    def test_build_mode_does_not_select_bundled_jre(self):
        self.bundle_runtime()
        self.remove_path_java()
        if WINDOWS:
            command = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                       str(self.bin / 'java-common.ps1'), '-JavaSelectionRoot', str(self.root),
                       '-JavaSelectionMode', 'build']
        else:
            command = ['sh', '-c', '. "$1"; ocelot_select_java "$2" build', 'sh',
                       str(self.bin / 'java-common.sh'), str(self.root)]
        p = subprocess.run(command, env=self.env, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('Java 8 or newer is required', p.stderr)

    def test_paths_and_arguments_with_spaces(self):
        self.env['JAVA_HOME'] = str(JAVA_HOME)
        project = self.base / 'project with spaces'
        p = self.run_cli('project', 'init', str(project), '--template', 'single-computer', '--json')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertTrue((project / 'ocelot-harness.conf').is_file())
        p = self.run_cli('--project', str(project), 'project', 'validate', '--json')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('manifest', p.stdout)

    def test_exit_code_is_preserved(self):
        self.env['JAVA_HOME'] = str(JAVA_HOME)
        p = self.run_cli('not-a-command')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('ERROR', p.stderr)

    def test_viewer_entrypoint(self):
        self.env['JAVA_HOME'] = str(JAVA_HOME)
        p = self.run_cli('--help', launcher='ocelot-viewer')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertTrue(p.stdout.startswith('usage: ocelot-viewer '))


if __name__ == '__main__':
    unittest.main()
