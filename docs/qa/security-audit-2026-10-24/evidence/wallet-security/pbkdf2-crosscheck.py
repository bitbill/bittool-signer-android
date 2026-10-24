#!/usr/bin/env python3
"""Compile repository portable PBKDF2 in a temp directory and cross-check hashlib.

Usage:
  python3 pbkdf2-crosscheck.py /path/to/repository --java-home /path/to/jdk

No repository files are modified. The temporary C wrapper includes the exact
production pbkdf2_jni.c and links its companion sha256.c. ARMv8 acceleration
and JNI calls are deliberately not exercised by this host-side cross-check.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repo_root', type=Path)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--cc', default=shutil.which('clang') or shutil.which('cc'))
    args = parser.parse_args()
    root = args.repo_root.resolve()
    cpp = root / 'core/crypto/src/main/cpp'
    sources = [cpp / 'pbkdf2_jni.c', cpp / 'sha256.c', cpp / 'sha256.h']
    for source in sources:
        if not source.is_file():
            parser.error(f'Missing production source: {source}')
    if args.java_home is None:
        java = shutil.which('javac')
        if java:
            args.java_home = Path(java).resolve().parent.parent
    if args.java_home is None or not (args.java_home / 'include/jni.h').is_file():
        parser.error('Pass --java-home pointing to a JDK with include/jni.h')
    if not args.cc:
        parser.error('No clang/cc found; pass --cc')
    host = platform.system()
    jni_os = {'Darwin': 'darwin', 'Linux': 'linux'}.get(host)
    if jni_os is None:
        parser.error('This script supports macOS and Linux hosts')
    compiler = subprocess.check_output([args.cc, '--version'], text=True).splitlines()[0]
    commit = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    print(f'Repository HEAD: {commit}')
    print(f'Host: {host} {platform.machine()}')
    print(f'Compiler: {compiler}')
    print(f'Hashlib backend: {__import__("ssl").OPENSSL_VERSION}')
    print('Target: production portable PBKDF2-HMAC-SHA256 implementation (not ARMv8 or JNI runtime)')
    for source in sources:
        print(f'Source SHA-256 {source.name}: {hashlib.sha256(source.read_bytes()).hexdigest()}')
    with tempfile.TemporaryDirectory(prefix='bittool-pbkdf2-audit-') as temp:
        work = Path(temp)
        wrapper = work / 'wrapper.c'
        # JSON quoting yields valid C string syntax for ordinary filesystem paths.
        wrapper.write_text('#include ' + json.dumps(str(cpp / 'pbkdf2_jni.c')) + '\n' + r'''
void audit_derive(const unsigned char *password, size_t password_length,
                  const unsigned char *salt, size_t salt_length,
                  unsigned int iterations, unsigned char *out, size_t out_length) {
    derive(password, password_length, salt, salt_length, iterations, out, out_length,
           bts_sha256_compress_portable);
}
''')
        library = work / ('libaudit_pbkdf2.dylib' if host == 'Darwin' else 'libaudit_pbkdf2.so')
        command = [args.cc, '-shared', '-fPIC', '-O2',
                   '-I', str(args.java_home / 'include'),
                   '-I', str(args.java_home / 'include' / jni_os),
                   str(wrapper), str(cpp / 'sha256.c'), '-o', str(library)]
        subprocess.run(command, check=True)
        derive = ctypes.CDLL(str(library)).audit_derive
        derive.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_char_p,
                           ctypes.c_size_t, ctypes.c_uint, ctypes.c_void_p,
                           ctypes.c_size_t]
        derive.restype = None
        rng = random.Random(10024)
        cases = [(0, 0, 1, 32), (1, 1, 1, 1), (64, 64, 2, 32),
                 (65, 65, 3, 33), (1024, 32, 600000, 32),
                 (4096, 1024, 10, 1024)]
        cases += [(rng.randint(0, 300), rng.randint(0, 200),
                   rng.randint(1, 1000), rng.randint(1, 300)) for _ in range(40)]
        print('Deterministic test generation seed: 10024')
        for index, (password_length, salt_length, iterations, out_length) in enumerate(cases, 1):
            password = rng.randbytes(password_length)
            salt = rng.randbytes(salt_length)
            out = ctypes.create_string_buffer(out_length)
            derive(password, password_length, salt, salt_length, iterations, out, out_length)
            expected = hashlib.pbkdf2_hmac('sha256', password, salt, iterations, out_length)
            status = 'PASS' if out.raw == expected else 'FAIL'
            print(f'{status} case={index:02d} password_bytes={password_length} salt_bytes={salt_length} '
                  f'iterations={iterations} output_bytes={out_length}')
            if status != 'PASS':
                raise AssertionError(f'Portable C mismatch on case {index}')
        print(f'RESULT: {len(cases)}/{len(cases)} cases passed')
    print('Temporary build artifacts removed; no production files modified.')


if __name__ == '__main__':
    main()
