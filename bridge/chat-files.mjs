import { posix } from 'node:path';
import { executeFileOperation } from './workspace-files.mjs';
const failure = (status, message) => Object.assign(Error(message), { status });

// Match Markdown destinations, including angle-bracket paths and balanced parentheses.
export function chatFileReferences(text) {
  const found = []; const pattern = /!?\[[^\]\n]*\]\(/g; let match;
  while ((match = pattern.exec(text))) {
    let index = pattern.lastIndex, start = index, depth = 1;
    if (text[index] === '<') {
      const end = text.indexOf('>', index + 1);
      if (end >= 0 && text[end + 1] === ')') { found.push(text.slice(index + 1, end)); pattern.lastIndex = end + 2; }
      continue;
    }
    while (index < text.length && depth) {
      if (text[index] === '\\') { index += 2; continue; }
      if (text[index] === '(') depth++;
      if (text[index] === ')') depth--;
      index++;
    }
    if (!depth) { found.push(text.slice(start, index - 1)); pattern.lastIndex = index; }
  }
  return found;
}
export function localChatPath(source, cwd) {
  let path = source.trim().replace(/(?::\d+(?::\d+)?|#L\d+(?:C\d+)?)$/, '');
  if (path.startsWith('sandbox:')) path = path.slice(8);
  if (path.startsWith('file://')) {
    const url = new URL(path); if (url.hostname) throw failure(400, 'Only local files can be downloaded.'); path = url.pathname;
  } else if (/^[a-z][a-z0-9+.-]*:/i.test(path)) throw failure(400, 'Unsupported file link.');
  try { path = decodeURIComponent(path); } catch { throw failure(400, 'Invalid file link.'); }
  path = path.replace(/(?::\d+(?::\d+)?|#L\d+(?:C\d+)?)$/, '');
  if (!path || path.includes('\0') || path.length > 4096) throw failure(400, 'Invalid file link.');
  if (!posix.isAbsolute(path) && !posix.isAbsolute(cwd ?? '')) throw failure(404, 'The chat has no folder for this relative file link.');
  return posix.resolve(cwd || '/', path);
}

export async function chatFile(context, input) {
  if (!input || typeof input !== 'object' || Object.keys(input).some(key => !['threadId', 'itemId', 'turnId', 'source'].includes(key)) ||
      !['threadId', 'itemId', 'turnId', 'source'].every(key => typeof input[key] === 'string' && input[key].length <= (key === 'source' ? 4096 : 256)) ||
      !input.threadId || !input.itemId || typeof context.call !== 'function') throw failure(400, 'Invalid chat file request.');
  let metadata;
  try { metadata = (await context.call('thread/read', { threadId: input.threadId, includeTurns: false })).thread; }
  catch { throw failure(404, 'This chat file is unavailable.'); }
  let item;
  const find = turns => (turns ?? []).filter(turn => !input.turnId || turn.id === input.turnId).flatMap(turn => turn.items ?? []).find(item => item.id === input.itemId);
  if (metadata.historyMode === 'paginated') {
    let cursor;
    for (let page = 0; page < 10 && !item; page++) {
      const result = await context.call('thread/items/list', { threadId: input.threadId, ...(input.turnId ? { turnId: input.turnId } : {}), limit: 100,
        sortDirection: 'desc', ...(cursor ? { cursor } : {}) });
      item = (result.data ?? []).find(entry => (!input.turnId || entry.turnId === input.turnId) && entry.item?.id === input.itemId)?.item;
      cursor = result.nextCursor; if (!cursor) break;
    }
  } else {
    try { item = find((await context.call('thread/read', { threadId: input.threadId, includeTurns: true })).thread.turns); }
    catch { throw failure(404, 'This chat file is unavailable.'); }
  }
  const sources = item?.type === 'agentMessage' ? chatFileReferences(item.text ?? '') : item?.type === 'imageGeneration' ? [item.savedPath] : [];
  if (!sources.includes(input.source)) throw failure(403, 'This file is not attached to this Codex reply.');
  const path = localChatPath(input.source, metadata.cwd);
  let root = '/';
  if (context.workspaceRoot) {
    root = [context.workspaceRoot, '/tmp', context.codexHome + '/artifacts'].find(root => path.startsWith(root + '/'));
    if (!root) throw failure(403, 'This file is outside your private workspace.');
  }
  const result = await executeFileOperation(context, { action: 'download', root, boundary: root, path: posix.relative(root, path) });
  return { bytes: Buffer.from(result.data, 'base64'), name: result.name };
}
