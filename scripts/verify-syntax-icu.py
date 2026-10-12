#!/usr/bin/env python3
"""Verify the built app's highlighting patterns using native ICU, as Android does."""
import argparse
import base64
import ctypes
import ctypes.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    library = ctypes.util.find_library('icui18n')
    if not library:
        raise RuntimeError('Install the ICU runtime before running this check')
    native = ctypes.CDLL(library)
    version = re.search(r'\.so\.(\d+)', library)

    def bind(name, result, *arguments):
        function = getattr(native, name + ('_' + version[1] if version else ''))
        function.restype, function.argtypes = result, arguments
        return function

    class ParseError(ctypes.Structure):
        _fields_ = [('line', ctypes.c_int32), ('offset', ctypes.c_int32),
                    ('preContext', ctypes.c_uint16 * 16), ('postContext', ctypes.c_uint16 * 16)]

    uchar = ctypes.POINTER(ctypes.c_uint16)
    status_pointer = ctypes.POINTER(ctypes.c_int32)
    open_regex = bind('uregex_open', ctypes.c_void_p, uchar, ctypes.c_int32, ctypes.c_uint32,
                      ctypes.POINTER(ParseError), status_pointer)
    close_regex = bind('uregex_close', None, ctypes.c_void_p)
    set_text = bind('uregex_setText', None, ctypes.c_void_p, uchar, ctypes.c_int32, status_pointer)
    find_next = bind('uregex_findNext', ctypes.c_int8, ctypes.c_void_p, status_pointer)
    start = bind('uregex_start', ctypes.c_int32, ctypes.c_void_p, ctypes.c_int32, status_pointer)
    end = bind('uregex_end', ctypes.c_int32, ctypes.c_void_p, ctypes.c_int32, status_pointer)

    def utf16(text):
        encoded = text.encode('utf-16-le')
        return (ctypes.c_uint16 * (len(encoded) // 2)).from_buffer_copy(encoded)

    def compile_regex(pattern):
        text, error, status = utf16(pattern), ParseError(), ctypes.c_int32(0)
        handle = open_regex(text, len(text), 0, ctypes.byref(error), ctypes.byref(status))
        return handle, status.value, error.offset

    # Export actual Kotlin-generated rules, including every keyword-map language.
    # No duplicate regex implementation is kept in this verifier.
    stdlib = sorted(ROOT.glob('.downloads/gradle-*/lib/kotlin-stdlib-*.jar'))[-1]
    classes = ROOT / 'app/build/tmp/kotlin-classes/debug'
    java = '''import java.util.*;
import java.nio.charset.StandardCharsets;
import dev.nulltogeek.codexremote.SyntaxHighlightingKt;
class ExportSyntaxPatterns {
  public static void main(String[] args) throws Exception {
    var field = SyntaxHighlightingKt.class.getDeclaredField("keywords");
    field.setAccessible(true);
    var names = new TreeSet<String>(((Map<String, ?>)field.get(null)).keySet());
    names.add("html");
    for (var name : names) emit(name, SyntaxHighlightingKt.syntaxPattern(name));
    emit("diff", "(?m)^.*$");
  }
  static void emit(String name, String pattern) {
    System.out.println(name + "\\t" + Base64.getEncoder().encodeToString(pattern.getBytes(StandardCharsets.UTF_8)));
  }
}'''
    with tempfile.TemporaryDirectory(prefix='codex-syntax-icu-') as directory:
        source = Path(directory) / 'ExportSyntaxPatterns.java'
        source.write_text(java)
        classpath = str(classes) + ':' + str(stdlib)
        subprocess.run(['javac', '-cp', classpath, '-d', directory, str(source)], check=True, capture_output=True, text=True)
        exported = subprocess.run(['java', '-cp', directory + ':' + classpath, 'ExportSyntaxPatterns'],
                                  check=True, capture_output=True, text=True).stdout
    patterns = {name: base64.b64decode(encoded).decode() for name, encoded in
                (line.split('\t', 1) for line in exported.splitlines())}

    old_shell = patterns['shell'].replace(r'\$\{[^}]*\}', r'\$\{[^}]*}')
    assert old_shell != patterns['shell']
    handle, original_error, offset = compile_regex(old_shell)
    if handle:
        close_regex(handle)
    assert not handle and original_error > 0, 'The original Android crash pattern must be reproduced'

    verified = []
    for language, pattern in patterns.items():
        handle, error, location = compile_regex(pattern)
        assert handle and error <= 0, f'{language}: ICU error {error} at offset {location}'
        try:
            if language == 'shell':
                sample = 'echo $HOME ${HOME} ${name:-fallback} $? $1 # comment'
                text, status = utf16(sample), ctypes.c_int32(0)
                set_text(handle, text, len(text), ctypes.byref(status))
                matches = []
                while find_next(handle, ctypes.byref(status)):
                    matches.append(sample[start(handle, 0, ctypes.byref(status)):end(handle, 0, ctypes.byref(status))])
                assert status.value <= 0
                assert all(variable in matches for variable in ['$HOME', '${HOME}', '${name:-fallback}', '$?', '$1'])
        finally:
            close_regex(handle)
        verified.append(language)
    report = {'passed': True, 'icuLibrary': library, 'originalPatternRejected': True,
              'originalErrorCode': original_error, 'originalErrorOffset': offset,
              'verifiedPatterns': verified, 'shellVariablesMatched': True, 'androidDeviceTest': False}
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
