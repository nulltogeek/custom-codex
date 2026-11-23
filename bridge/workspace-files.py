"""Bounded project file operations. Never follow links below the selected root."""
import base64
import hashlib
import json
import os
import stat
import sys
import time
import uuid

MAX_FILE = 256 * 1024
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


class Failure(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def parts(path):
    if not isinstance(path, str) or len(path) > 4096 or path.startswith('/') or '\x00' in path:
        raise Failure(400, 'Choose a relative project path.')
    values = path.split('/') if path else []
    if any(value in ('', '.', '..') for value in values):
        raise Failure(400, 'Invalid project path.')
    return values


def walk(root, values):
    fd = os.dup(root)
    try:
        for value in values:
            next_fd = os.open(value, DIRECTORY_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def file_bytes(parent, name):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise Failure(415, 'Only regular text files can be opened.')
        if info.st_size > MAX_FILE:
            raise Failure(413, 'The editor supports files up to 256 KB.')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read(MAX_FILE + 1)
        if len(data) > MAX_FILE:
            raise Failure(413, 'The editor supports files up to 256 KB.')
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError:
            raise Failure(415, 'This file is not UTF-8 text.')
        if '\x00' in text:
            raise Failure(415, 'Binary files cannot be edited.')
        return data, text, info
    finally:
        os.close(fd)


def operation(request):
    root = os.path.realpath(request['root'])
    boundary = os.path.realpath(request.get('boundary') or root)
    if os.path.commonpath([boundary, root]) != boundary:
        raise Failure(403, 'Choose a folder inside your private workspace.')
    root_fd = os.open(root, DIRECTORY_FLAGS)
    try:
        values = parts(request.get('path', ''))
        action = request['action']
        if action in ('list', 'search'):
            directory = walk(root_fd, values)
            try:
                if action == 'list':
                    rows = []
                    with os.scandir(directory) as entries:
                        for entry in entries:
                            if len(rows) >= 10000:
                                raise Failure(413, 'This folder has too many entries to browse.')
                            info = entry.stat(follow_symlinks=False)
                            kind = 'directory' if stat.S_ISDIR(info.st_mode) else 'file' if stat.S_ISREG(info.st_mode) else 'link' if stat.S_ISLNK(info.st_mode) else 'other'
                            rows.append({'name': entry.name, 'path': '/'.join(values + [entry.name]), 'kind': kind, 'size': info.st_size})
                    rows.sort(key=lambda row: (row['kind'] != 'directory', row['name'].casefold(), row['name']))
                    offset = request.get('offset', 0)
                    if type(offset) is not int or offset < 0 or offset > 10000:
                        raise Failure(400, 'Invalid folder offset.')
                    return {'entries': rows[offset:offset + 200], 'nextOffset': offset + 200 if len(rows) > offset + 200 else None}
                query = request.get('query', '').strip().casefold()
                if not query or len(query) > 200:
                    raise Failure(400, 'Enter a filename to search (up to 200 characters).')
                rows, scanned = [], 0
                deadline = time.monotonic() + 3
                excluded = {'.git', 'node_modules', '.downloads', '.gradle', 'build', '__pycache__'}

                def search(fd, prefix, depth):
                    nonlocal scanned
                    if depth > 32:
                        return True
                    with os.scandir(fd) as entries:
                        for entry in entries:
                            scanned += 1
                            if scanned > 10000 or len(rows) >= 200 or time.monotonic() > deadline:
                                return True
                            if entry.name in excluded or entry.is_symlink():
                                continue
                            relative = prefix + [entry.name]
                            if entry.is_dir(follow_symlinks=False):
                                try:
                                    child = os.open(entry.name, DIRECTORY_FLAGS, dir_fd=fd)
                                except OSError:
                                    continue
                                try:
                                    if search(child, relative, depth + 1):
                                        return True
                                finally:
                                    os.close(child)
                            elif entry.is_file(follow_symlinks=False) and query in entry.name.casefold():
                                rows.append({'name': entry.name, 'path': '/'.join(relative), 'kind': 'file', 'size': entry.stat(follow_symlinks=False).st_size})
                    return False

                truncated = search(directory, values, 0)
                return {'entries': sorted(rows, key=lambda row: row['path']), 'truncated': truncated}
            finally:
                os.close(directory)
        if action not in ('read', 'write') or not values:
            raise Failure(400, 'Choose a file.')
        parent = walk(root_fd, values[:-1])
        try:
            data, text, info = file_bytes(parent, values[-1])
            revision = hashlib.sha256(data).hexdigest()
            if action == 'read':
                return {'path': request['path'], 'content': text, 'revision': revision, 'size': len(data), 'writable': bool(info.st_mode & 0o222)}
            if request.get('revision') != revision:
                raise Failure(409, 'This file changed on the server. Copy your edits, then reload the latest version.')
            content = request.get('content')
            if not isinstance(content, str) or '\x00' in content:
                raise Failure(400, 'Enter valid text.')
            changed = content.encode('utf-8')
            if len(changed) > MAX_FILE:
                raise Failure(413, 'The editor supports files up to 256 KB.')
            if not info.st_mode & 0o222:
                raise Failure(403, 'This file is read-only.')
            temporary = '.codex-edit-' + uuid.uuid4().hex
            try:
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, info.st_mode & 0o777, dir_fd=parent)
                with os.fdopen(fd, 'wb') as stream:
                    stream.write(changed)
                    stream.flush()
                    os.fchmod(stream.fileno(), info.st_mode & 0o777)
                    os.fsync(stream.fileno())
                current, _, current_info = file_bytes(parent, values[-1])
                if hashlib.sha256(current).hexdigest() != revision or (current_info.st_dev, current_info.st_ino) != (info.st_dev, info.st_ino):
                    raise Failure(409, 'This file changed on the server. Copy your edits, then reload the latest version.')
                os.replace(temporary, values[-1], src_dir_fd=parent, dst_dir_fd=parent)
                os.fsync(parent)
            finally:
                try:
                    os.unlink(temporary, dir_fd=parent)
                except FileNotFoundError:
                    pass
            return {'path': request['path'], 'revision': hashlib.sha256(changed).hexdigest(), 'size': len(changed)}
        finally:
            os.close(parent)
    finally:
        os.close(root_fd)


try:
    request = json.loads(base64.b64decode(''.join(sys.argv[1:])))
    result = operation(request)
    print(json.dumps({'status': 200, 'result': result}, ensure_ascii=True))
except Failure as error:
    print(json.dumps({'status': error.status, 'error': error.message}))
except OSError as error:
    status = 404 if error.errno == 2 else 403
    print(json.dumps({'status': status, 'error': 'File or folder is unavailable. Links and special files cannot be opened.'}))
except (ValueError, KeyError, TypeError, UnicodeError):
    print(json.dumps({'status': 400, 'error': 'Invalid file request.'}))
