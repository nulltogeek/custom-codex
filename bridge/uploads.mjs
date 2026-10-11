import { mkdirSync, writeFileSync, lstatSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

export const MAX_UPLOAD = 20 * 1024 * 1024;
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
