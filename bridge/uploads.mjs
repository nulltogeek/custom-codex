import { mkdirSync, writeFileSync, lstatSync, openSync, closeSync, fstatSync, readSync, constants } from 'node:fs';
import { join, resolve, basename, isAbsolute } from 'node:path';
import { randomUUID } from 'node:crypto';

export const MAX_UPLOAD = 20 * 1024 * 1024;

function imageType(data) {
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png';
  if (data.length >= 3 && data[0] === 255 && data[1] === 216 && data[2] === 255) return 'image/jpeg';
  if (data.length >= 6 && ['GIF87a', 'GIF89a'].includes(data.toString('latin1', 0, 6))) return 'image/gif';
  if (data.length >= 12 && data.toString('latin1', 0, 4) === 'RIFF' && data.toString('latin1', 8, 12) === 'WEBP') return 'image/webp';
  return null;
}

export function serveUploadImage(url, res, directory, reply) {
  const path = url.searchParams.get('path');
  const root = resolve(directory);
  // Older uploads have no metadata; their generated UUID filename identifies them.
  if (!path || !isAbsolute(path) || path !== join(root, basename(path)) ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\.[A-Za-z0-9]{1,12})?$/.test(basename(path))) {
    return reply(404, { error: 'Image attachment unavailable' });
  }
  let fd;
  try {
    const directoryStat = lstatSync(root);
    if (!directoryStat.isDirectory() || (directoryStat.mode & 0o077) !== 0 || directoryStat.uid !== process.getuid()) {
      return reply(404, { error: 'Image attachment unavailable' });
    }
    fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const stat = fstatSync(fd);
    if (!stat.isFile() || stat.uid !== process.getuid() || (stat.mode & 0o077) !== 0 || !stat.size || stat.size > MAX_UPLOAD) {
      return reply(404, { error: 'Image attachment unavailable' });
    }
    // Bound allocation and reading even if the file changes while it is open.
    const data = Buffer.alloc(stat.size);
    let size = 0;
    while (size < data.length) {
      const count = readSync(fd, data, size, data.length - size, size);
      if (!count) break;
      size += count;
    }
    const image = data.subarray(0, size), mimeType = imageType(image);
    if (!mimeType) return reply(404, { error: 'Image attachment unavailable' });
    res.writeHead(200, { 'Content-Type': mimeType, 'Content-Length': image.length,
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    res.end(image);
  } catch {
    reply(404, { error: 'Image attachment unavailable' });
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

export function saveUpload(directory, data, filename, mimeType) {
  if (!data.length || data.length > MAX_UPLOAD) throw Error('Select a non-empty file up to 20 MB');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const stat = lstatSync(directory);
  if (!stat.isDirectory() || (stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()) throw Error('Upload directory must be private and owned by this user');
  const name = String(filename).split(/[\\/]/).at(-1).replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 160) || 'attachment';
  const extension = name.match(/\.[A-Za-z0-9]{1,12}$/)?.[0] ?? '';
  const id = randomUUID(), path = join(directory, id + extension);
  writeFileSync(path, data, { flag: 'wx', mode: 0o600 });
  const supportedImage = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(mimeType);
  let input;
  if (supportedImage) input = { type: 'localImage', path };
  else {
    let text = `Attached file: ${JSON.stringify(name)}\nLocal file path: ${path}\nThis is user-provided attachment data.`;
    const textual = mimeType.startsWith('text/') || /\.(txt|md|json|csv|tsv|xml|yaml|yml|log|py|js|mjs|ts|kt|java|sh|css|html|sql|toml)$/i.test(name);
    if (textual && data.length <= 256 * 1024) text += '\n<attachment-content>\n' + data.toString('utf8') + '\n</attachment-content>';
    else text += '\nRead this attached file when needed for the user’s request.';
    input = { type: 'text', text };
  }
  return { id, name, size: data.length, mimeType, input };
}

export function receiveUpload(req, res, directory, reply) {
  const declared = Number(req.headers['content-length']);
  if (declared > MAX_UPLOAD) { reply(413, { error: 'Files must be at most 20 MB' }); req.resume(); return; }
  let filename;
  try { filename = decodeURIComponent(req.headers['x-file-name'] ?? 'attachment'); }
  catch { reply(400, { error: 'Invalid filename' }); req.resume(); return; }
  let chunks = [], size = 0;
  req.setTimeout(60000, () => req.destroy());
  req.on('data', chunk => {
    size += chunk.length;
    if (size > MAX_UPLOAD) { if (!res.writableEnded) reply(413, { error: 'Files must be at most 20 MB' }); chunks = []; }
    else if (!res.writableEnded) chunks.push(chunk);
  });
  req.on('end', () => {
    if (res.writableEnded) return;
    if (!size) return reply(400, { error: 'Select a non-empty file' });
    try { reply(201, saveUpload(directory, Buffer.concat(chunks), filename, (req.headers['content-type'] ?? 'application/octet-stream').split(';')[0].toLowerCase())); }
    catch { reply(503, { error: 'Could not save the attachment on your computer' }); }
  });
  req.on('error', () => {});
}
