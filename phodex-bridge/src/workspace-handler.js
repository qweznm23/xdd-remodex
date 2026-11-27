// FILE: workspace-handler.js
// Purpose: Executes workspace-scoped previews, reads, and patch operations without touching unrelated repo changes.
// Layer: Bridge handler
// Exports: handleWorkspaceRequest
// Depends on: child_process, fs, os, path, ./codex-home, ./git-handler

const { execFile } = require("child_process");
const fs = require("fs");
const { createHash } = require("crypto");
const os = require("os");
const path = require("path");
const { promisify, TextDecoder } = require("util");
const { resolveCodexGeneratedImagesRoot } = require("./codex-home");
const { gitStatus } = require("./git-handler");
const {
  workspaceCheckpointCapture,
  workspaceCheckpointCopy,
  workspaceCheckpointDiff,
  workspaceCheckpointRestoreApply,
  workspaceCheckpointRestorePreview,
} = require("./workspace-checkpoints");

const execFileAsync = promisify(execFile);
const GIT_TIMEOUT_MS = 30_000;
const MAX_IMAGE_READ_BYTES = 8 * 1024 * 1024;
const MAX_IMAGE_PREVIEW_READ_BYTES = 2 * 1024 * 1024;
const MAX_TEXT_FILE_READ_BYTES = 2 * 1024 * 1024;
const BINARY_SNIFF_BYTES = 8 * 1024;
const MAX_BASENAME_FALLBACK_MATCHES = 2;
const MIN_IMAGE_PREVIEW_PIXEL_DIMENSION = 128;
const MAX_IMAGE_PREVIEW_PIXEL_DIMENSION = 3_200;
const IMAGE_PREVIEW_RETRY_SCALE = 0.75;
const IMAGE_PREVIEW_TOOL_TIMEOUT_MS = 5_000;
const IMAGE_PREVIEW_TOTAL_TIMEOUT_MS = 15_000;
const IMAGE_MIME_TYPES_BY_EXTENSION = new Map([
  [".jpg", "image/jpeg"],
  [".jpeg", "image/jpeg"],
  [".png", "image/png"],
  [".svg", "image/svg+xml"],
  [".gif", "image/gif"],
  [".webp", "image/webp"],
  [".heic", "image/heic"],
  [".heif", "image/heif"],
]);
/** Match git-handler.js: Node default maxBuffer is 1 MiB. */
const GIT_EXEC_MAX_BUFFER_BYTES = 50 * 1024 * 1024;
const repoMutationLocks = new Map();
const { createWorkspaceUploadHandler, workspaceUploadRoot, isCompletedUploadPath, uploadMethods } = require('./workspace-upload');
const workspaceUpload = createWorkspaceUploadHandler();
const { previewArchive } = require('./archive-preview');
const archiveMethods = new Set(['workspace/archiveList', 'workspace/archiveEntry']);
const fileStreams = process.env.REMODEX_FILE_STREAM_PORT ? require('./file-stream').createFileStreams() : null;
if (fileStreams) fileStreams.server.listen(Number(process.env.REMODEX_FILE_STREAM_PORT), '127.0.0.1').unref();


function handleWorkspaceRequest(rawMessage, sendResponse) {
  let parsed;
  try {
    parsed = JSON.parse(rawMessage);
  } catch {
    return false;
  }

  const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
  if (!method.startsWith("workspace/")) {
    return false;
  }

  const id = parsed.id;
  const params = parsed.params || {};

  handleWorkspaceMethod(method, params)
    .then((result) => {
      sendResponse(JSON.stringify({ id, result }));
    })
    .catch((err) => {
      const errorCode = err.errorCode || "workspace_error";
      const message = err.userMessage || err.message || "Unknown workspace error";
      sendResponse(
        JSON.stringify({
          id,
          error: {
            code: -32000,
            message,
            data: {
              errorCode,
              ...(method === "workspace/fileInfo" || method === "workspace/readFileChunk" || method === "workspace/prepareTransfer" || uploadMethods.has(method) || archiveMethods.has(method) ? {
                operation: method,
                stage: "workspace_file_transfer",
                diagnostic: downloadErrorDiagnostic(err),
              } : {}),
            },
          },
        })
      );
    });

  return true;
}

async function handleWorkspaceMethod(method, params) {
  if (method === 'workspace/prepareTransfer') return prepareFileTransfer(params);
  if (uploadMethods.has(method)) return workspaceUpload(method, params);
  if (method === "workspace/fileInfo" || method === "workspace/readFileChunk" || archiveMethods.has(method)) {
    return workspaceDownloadFile(method, params);
  }
  if (method === "workspace/readImage") {
    return workspaceReadImage(params);
  }
  if (method === "workspace/readFile") {
    return workspaceReadFile(params);
  }

  const cwd = await resolveWorkspaceCwd(params);
  const repoRoot = await resolveRepoRoot(cwd);

  switch (method) {
    case "workspace/checkpointCapture":
      return withRepoMutationLock(repoRoot, () => workspaceCheckpointCapture(repoRoot, params));
    case "workspace/checkpointCopy":
      return withRepoMutationLock(repoRoot, () => workspaceCheckpointCopy(repoRoot, params));
    case "workspace/checkpointDiff":
      return workspaceCheckpointDiff(repoRoot, params);
    case "workspace/checkpointRestorePreview":
      return workspaceCheckpointRestorePreview(repoRoot, params);
    case "workspace/checkpointRestoreApply":
      return withRepoMutationLock(repoRoot, () => workspaceCheckpointRestoreApply(repoRoot, params));
    case "workspace/revertPatchPreview":
      return workspaceRevertPatchPreview(repoRoot, params);
    case "workspace/revertPatchApply":
      return withRepoMutationLock(repoRoot, () => workspaceRevertPatchApply(repoRoot, params));
    case "workspace/revertPatchBatchPreview":
      return workspaceRevertPatchBatchPreview(repoRoot, params);
    case "workspace/revertPatchBatchApply":
      return withRepoMutationLock(repoRoot, () => workspaceRevertPatchBatchApply(repoRoot, params));
    default:
      throw workspaceError("unknown_method", `Unknown workspace method: ${method}`);
  }
}

const DOWNLOAD_CHUNK_BYTES = 256 * 1024;

function downloadErrorDiagnostic(error, depth = 0) {
  if (depth >= 4) return { truncated: true };
  return {
    type: error.name, code: error.code, message: error.message,
    stack: String(error.stack || "").slice(0, 8192),
    truncated: String(error.stack || "").length > 8192,
    cause: error.cause ? downloadErrorDiagnostic(error.cause, depth + 1) : undefined,
    suppressed: error.suppressed?.map(e => downloadErrorDiagnostic(e, depth + 1)),
  };
}

function downloadRevision(stat) {
  return createHash("sha256").update([stat.dev, stat.ino, stat.size, stat.mtimeMs, stat.ctimeMs].join(":")).digest("hex");
}

// Every request is scoped to the paired workspace; only a bounded chunk crosses the relay.
async function workspaceDownloadFile(method, params) {
  const requested = workspaceAttachmentPath(params);
  if (!requested) throw workspaceError("missing_file_path", "The request must include a file path.");
  const resolved = await resolveReadableAttachmentPath(params, requested);
  const file = await fs.promises.open(resolved, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
  let primaryError;
  try {
    const stat = await file.stat();
    if (!stat.isFile()) throw workspaceError("not_a_file", "Only regular files can be downloaded.");
    const revision = downloadRevision(stat);
    const metadata = { path: resolved, fileName: path.basename(resolved), byteLength: stat.size, revision };
    if (method === "workspace/fileInfo") {
      const sample = Buffer.alloc(Math.min(stat.size, BINARY_SNIFF_BYTES));
      const { bytesRead } = await file.read(sample, 0, sample.length, 0);
      const extension = path.extname(resolved).toLowerCase();
      let isText = !sample.subarray(0, bytesRead).includes(0);
      if (isText) {
        try { new TextDecoder("utf-8", { fatal: true }).decode(sample.subarray(0, bytesRead), { stream: bytesRead < stat.size }); }
        catch { isText = false; }
      }
      const binaryTypes = { ".apk": "application/vnd.android.package-archive", ".zip": "application/zip", ".pdf": "application/pdf", ".gz": "application/gzip", ".7z": "application/x-7z-compressed", ".jar": "application/java-archive", ".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm", ".mkv": "video/x-matroska", ".avi": "video/x-msvideo", ".3gp": "video/3gpp", ".3g2": "video/3gpp2", ".ogv": "video/ogg", ".mpeg": "video/mpeg", ".mpg": "video/mpeg", ".m2v": "video/mpeg", ".mp3": "audio/mpeg" };
      const imageMime = IMAGE_MIME_TYPES_BY_EXTENSION.get(extension);
      const mimeType = imageMime || binaryTypes[extension] || (isText ? "text/plain" : "application/octet-stream");
      const previewKind = extension === ".zip" ? "archive" : imageMime && (fileStreams || stat.size <= MAX_IMAGE_READ_BYTES) ? "image"
        : !imageMime && !binaryTypes[extension] && isText && (fileStreams || stat.size <= MAX_TEXT_FILE_READ_BYTES) ? "text" : "none";
      if (downloadRevision(await file.stat()) !== revision) throw workspaceError("file_changed", "The file changed while reading its metadata. Try again.");
      return { ...metadata, mimeType, previewKind, binaryStream: !!fileStreams };
    }
    if (typeof params.revision !== "string" || params.revision !== revision) throw workspaceError("file_changed", "The file changed during download. Download it again.");
    if (archiveMethods.has(method)) {
      return await previewArchive(file, method, params, params.inline === true ? null : fileStreams, metadata, async () => {
        if (downloadRevision(await file.stat()) !== revision) throw workspaceError("file_changed", "ZIP changed during preview. Reopen the archive.");
      });
    }
    const offset = params.offset;
    if (!Number.isSafeInteger(offset) || offset < 0 || offset > stat.size) throw workspaceError("invalid_offset", "Invalid download offset.");
    const buffer = Buffer.alloc(Math.min(DOWNLOAD_CHUNK_BYTES, stat.size - offset));
    let count = 0;
    while (count < buffer.length) {
      const { bytesRead } = await file.read(buffer, count, buffer.length - count, offset + count);
      if (!bytesRead) throw workspaceError("file_changed", "The file ended unexpectedly during download.");
      count += bytesRead;
    }
    if (downloadRevision(await file.stat()) !== revision) throw workspaceError("file_changed", "The file changed during download. Download it again.");
    return { ...metadata, offset, nextOffset: offset + count, eof: offset + count === stat.size,
      dataBase64: buffer.toString("base64"), sha256: createHash("sha256").update(buffer).digest("hex") };
  } catch (error) {
    primaryError = error;
    throw error;
  } finally {
    try { await file.close(); }
    catch (error) {
      if (primaryError) (primaryError.suppressed ||= []).push(error);
      else throw error;
    }
  }
}

// Resolve workspace access before minting a short-lived, file-specific capability.
async function prepareFileTransfer(params) {
  if (!fileStreams) throw workspaceError('stream_unavailable', 'Binary file transfer is not configured');
  const mode = params.mode || 'download';
  if (!['download','image','text'].includes(mode)) throw workspaceError('invalid_mode','Invalid preview mode');
  let info, data, nextOffset;
  if (mode === 'image') {
    const preview = await workspaceReadImage({...params, maxPixelDimension: Number(params.maxPixelDimension) || 1600});
    const stat = await fs.promises.stat(preview.path);
    if (downloadRevision(stat) !== preview.revision) throw workspaceError('file_changed','Image changed during preview');
    info = {path:preview.path, revision:downloadRevision(stat)};
    data = Buffer.from(preview.dataBase64, 'base64');
  } else {
    info = await workspaceDownloadFile('workspace/fileInfo', params);
    if (params.revision && params.revision !== info.revision) throw workspaceError('file_changed','File changed before transfer');
    if (mode === 'text') {
      if (info.previewKind !== 'text') throw workspaceError('not_text','File cannot be previewed as text');
      const offset = params.offset || 0;
      if (!Number.isSafeInteger(offset) || offset < 0 || offset > info.byteLength) throw workspaceError('invalid_offset','Invalid text offset');
      const file = await fs.promises.open(info.path, 'r'); let primary;
      try {
        const buffer = Buffer.alloc(Math.min(64 * 1024, info.byteLength - offset));
        const {bytesRead} = await file.read(buffer,0,buffer.length,offset);
        const content = new TextDecoder('utf-8',{fatal:true}).decode(buffer.subarray(0,bytesRead),{stream:offset+bytesRead<info.byteLength});
        data = Buffer.from(content); nextOffset = offset + data.length;
        if (downloadRevision(await file.stat()) !== info.revision) throw workspaceError('file_changed','File changed during preview');
      } catch(error) { primary=error; throw error; }
      finally { try { await file.close(); } catch(error) { if(primary) (primary.suppressed ||= []).push(error); else throw error; } }
    }
  }
  const transfer = fileStreams.grant({path:info.path,revision:info.revision,byteLength:data ? data.length : info.byteLength,data});
  return {...transfer, nextOffset, totalBytes:info.byteLength};
}

// Reads a UTF-8 text file from the active workspace for the phone-side read-only viewer.
async function workspaceReadFile(params) {
  const requestedPath = workspaceAttachmentPath(params);
  if (!requestedPath) {
    throw workspaceError("missing_file_path", "The request must include a file path.");
  }

  const realFilePath = await resolveReadableAttachmentPath(params, requestedPath);

  const stat = await fs.promises.stat(realFilePath);
  if (!stat.isFile()) {
    throw workspaceError("file_not_found", "The path is not a file.");
  }
  if (stat.size > MAX_TEXT_FILE_READ_BYTES) {
    throw workspaceError(
      "file_too_large",
      "This file is too large to send to the phone. Open it on the Mac or ask for a smaller section."
    );
  }

  const result = {
    path: realFilePath,
    fileName: path.basename(realFilePath),
    byteLength: stat.size,
    mtimeMs: stat.mtimeMs,
    encoding: "utf-8",
  };
  if (params.includeContent === false || params.metadataOnly === true) {
    return result;
  }
  if (isUnchangedTextFileRead(params, stat)) {
    return {
      ...result,
      notModified: true,
    };
  }

  const data = await fs.promises.readFile(realFilePath);
  const content = decodeUtf8TextFile(data);
  return {
    ...result,
    content,
    lineCount: countLines(content),
  };
}

async function resolveReadableAttachmentPath(params, requested) {
  const uploadRoot = workspaceUploadRoot();
  if (isCompletedUploadPath(uploadRoot, requested)) {
    try {
      return await resolveCompletedUpload(uploadRoot, requested, 'file_path_not_allowed');
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      throw Object.assign(workspaceError('file_not_found', 'The uploaded file no longer exists on this computer.'), { cause: error });
    }
  }
  const cwd = await resolveWorkspaceCwd(params);
  const root = await resolveReadableWorkspaceRoot(cwd);
  const resolved = await resolveWorkspaceTextFilePath(cwd, requested, root);
  if (!resolved) throw workspaceError('file_not_found', 'The file no longer exists on this computer.');
  if (!root || !isPathInside(resolved, root)) throw workspaceError('file_path_not_allowed', 'Only files in the current workspace or completed uploads can be read.');
  return resolved;
}

function workspaceAttachmentPath(params) {
  const candidates = [params.path, params.filePath, params.localPath];
  const root = workspaceUploadRoot();
  return candidates.find(candidate => isCompletedUploadPath(root, candidate)) || firstNonEmptyString(candidates);
}

async function resolveCompletedUpload(root, candidate, errorCode) {
  const [realRoot, resolved] = await Promise.all([fs.promises.realpath(root), fs.promises.realpath(candidate)]);
  if (!isCompletedUploadPath(realRoot, resolved)) throw workspaceError(errorCode, 'The path must refer to a completed upload.');
  return resolved;
}

// Reads recognized local image files from the bound repo, Codex image cache, or host temp screenshot folders.
async function workspaceReadImage(params) {
  const requestedPath = workspaceAttachmentPath(params);
  if (!requestedPath) {
    throw workspaceError("missing_image_path", "The request must include an image path.");
  }

  const cwd = firstNonEmptyString([params.cwd, params.currentWorkingDirectory])
    ? await resolveWorkspaceCwd(params)
    : null;
  const imagePath = path.isAbsolute(requestedPath)
    ? path.resolve(requestedPath)
    : path.resolve(cwd || process.cwd(), requestedPath);
  const extension = path.extname(imagePath).toLowerCase();
  let mimeType = IMAGE_MIME_TYPES_BY_EXTENSION.get(extension);

  const [realImagePath, realGeneratedImagesRoot] = await Promise.all([
    realpathOrNull(imagePath),
    realpathOrNull(resolveCodexGeneratedImagesRoot()),
  ]);
  if (!realImagePath) {
    throw workspaceError("image_not_found", "The image file no longer exists on this Mac.");
  }

  const [realWorkspaceRoot, realTempRoots, realUploadRoot] = await Promise.all([
    cwd ? resolveImageWorkspaceRoot(cwd) : null,
    realTemporaryImageRoots(),
    realpathOrNull(workspaceUploadRoot()),
  ]);
  const isAllowed =
    (realWorkspaceRoot && isPathInside(realImagePath, realWorkspaceRoot))
    || (realGeneratedImagesRoot && isPathInside(realImagePath, realGeneratedImagesRoot))
    || (realUploadRoot && isCompletedUploadPath(realUploadRoot, realImagePath))
    || realTempRoots.some((tempRoot) => isPathInside(realImagePath, tempRoot));
  if (!isAllowed) {
    throw workspaceError("image_path_not_allowed", "Only images in this workspace, completed uploads, Codex generated images, or temporary screenshots can be previewed.");
  }

  const stat = await fs.promises.stat(realImagePath);
  if (!stat.isFile()) {
    throw workspaceError("image_not_found", "The image path is not a file.");
  }
  if (!mimeType) {
    mimeType = await sniffImageMimeType(realImagePath);
  }
  if (!mimeType) {
    throw workspaceError("unsupported_image_type", "Only local image files can be previewed.");
  }
  const includeData = params.includeData !== false && params.metadataOnly !== true;
  const maxPixelDimension = normalizedPreviewPixelDimension(params);
  if (stat.size > MAX_IMAGE_READ_BYTES && !maxPixelDimension) {
    throw workspaceError(
      "image_too_large",
      "This image is too large to send to the phone. Open it on the Mac or move a smaller preview into the workspace."
    );
  }

  const result = {
    path: realImagePath,
    revision: downloadRevision(stat),
    fileName: path.basename(realImagePath),
    mimeType,
    byteLength: stat.size,
    mtimeMs: stat.mtimeMs,
    previewMaxPixelDimension: maxPixelDimension || undefined,
  };
  if (!includeData) {
    return result;
  }
  if (isUnchangedImageRead(params, stat, maxPixelDimension)) {
    return {
      ...result,
      notModified: true,
    };
  }

  const isVector = mimeType === "image/svg+xml";
  const data = isVector
    ? await readSVGPreviewData(realImagePath, stat.size)
    : maxPixelDimension
    ? await readPreviewImageData(realImagePath, maxPixelDimension, stat.size)
    : await fs.promises.readFile(realImagePath);
  return {
    ...result,
    mimeType: !isVector && maxPixelDimension && process.platform === "linux" ? "image/jpeg" : mimeType,
    dataByteLength: data.length,
    dataBase64: data.toString("base64"),
  };
}

// Enforce the same limits when a configured endpoint supplies the image bytes.
function workspaceImageResponseFits(params, result, responseBytes) {
  const isPreview = normalizedPreviewPixelDimension(params) || result?.mimeType === "image/svg+xml";
  const limit = isPreview ? MAX_IMAGE_PREVIEW_READ_BYTES : MAX_IMAGE_READ_BYTES;
  const data = result?.dataBase64;
  const maxEnvelopeBytes = Math.ceil(limit / 3) * 4 + 64 * 1024;
  return responseBytes <= maxEnvelopeBytes
    && (typeof data !== "string" || Buffer.byteLength(data, "base64") <= limit);
}

function normalizedPreviewPixelDimension(params) {
  const requested = Number(params.maxPixelDimension || params.previewMaxPixelDimension);
  if (!Number.isFinite(requested) || requested <= 0) {
    return null;
  }
  return Math.min(
    MAX_IMAGE_PREVIEW_PIXEL_DIMENSION,
    Math.max(MIN_IMAGE_PREVIEW_PIXEL_DIMENSION, Math.round(requested))
  );
}

async function sniffImageMimeType(filePath) {
  let header;
  try {
    const handle = await fs.promises.open(filePath, "r");
    try {
      header = Buffer.alloc(512);
      const read = await handle.read(header, 0, header.length, 0);
      header = header.subarray(0, read.bytesRead);
    } finally {
      await handle.close();
    }
  } catch {
    return null;
  }

  return imageMimeTypeFromHeader(header);
}

function imageMimeTypeFromHeader(header) {
  if (header.length >= 8 && header.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return "image/png";
  }
  if (header.length >= 3 && header[0] === 0xff && header[1] === 0xd8 && header[2] === 0xff) {
    return "image/jpeg";
  }
  if (header.length >= 6 && (header.subarray(0, 6).toString("ascii") === "GIF87a" || header.subarray(0, 6).toString("ascii") === "GIF89a")) {
    return "image/gif";
  }
  if (header.length >= 12 && header.subarray(0, 4).toString("ascii") === "RIFF" && header.subarray(8, 12).toString("ascii") === "WEBP") {
    return "image/webp";
  }
  if (looksLikeSVGHeader(header)) {
    return "image/svg+xml";
  }

  return null;
}

// A runtime on another filesystem needs the original bytes inline. The normal
// local Codex path uses localImage and avoids reading the upload into the bridge.
async function inlineUploadedTurnImages(message, root = workspaceUploadRoot()) {
  if (!['turn/start', 'turn/steer'].includes(message?.method) || !Array.isArray(message?.params?.input)) return message;
  const items = message.params.input;
  const isUpload = (item) => item?.type === 'localImage' && isCompletedUploadPath(root, item.path);
  if (!items.some(isUpload)) return message;
  try {
    return await encodeUploadedTurnImages(message, isUpload, root);
  } catch (error) {
    error.attachmentStage = 'uploaded_image_input';
    if (!error.errorCode) error.errorCode = 'image_upload_read_failed';
    throw error;
  }
}

async function encodeUploadedTurnImages(message, isUpload, root) {
  const input = [];
  let remaining = MAX_IMAGE_READ_BYTES;
  for (const item of message.params.input) {
    if (!isUpload(item)) {
      input.push(item);
      continue;
    }
    const realPath = await resolveCompletedUpload(root, item.path, 'image_path_not_allowed');
    const { bytes, mimeType } = await readUploadedTurnImage(realPath, remaining);
    remaining -= bytes.length;
    const image = { type: 'image', url: `data:${mimeType};base64,${bytes.toString('base64')}` };
    if (item.detail != null) image.detail = item.detail;
    input.push(image);
  }
  return { ...message, params: { ...message.params, input } };
}

async function readUploadedTurnImage(filePath, remaining) {
  const handle = await fs.promises.open(filePath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  let failure;
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size === 0) throw workspaceError('invalid_uploaded_image', 'Uploaded image is empty or is not a regular file.');
    if (stat.size > remaining) throw workspaceError('inline_upload_images_too_large', 'This runtime needs inline images. Uploaded images in one message must total at most 8 MiB.');
    const bytes = Buffer.alloc(stat.size + 1);
    let length = 0;
    while (length < bytes.length) {
      const { bytesRead } = await handle.read(bytes, length, bytes.length - length, length);
      if (bytesRead === 0) break;
      length += bytesRead;
    }
    if (length !== stat.size) throw workspaceError('uploaded_image_changed', 'The uploaded image changed while reading it.');
    const mimeType = imageMimeTypeFromHeader(bytes.subarray(0, Math.min(length, 512)));
    if (!mimeType || mimeType === 'image/svg+xml') throw workspaceError('unsupported_uploaded_image_type', 'This runtime requires a PNG, JPEG, WebP, or GIF image.');
    return { bytes: bytes.subarray(0, length), mimeType };
  } catch (error) {
    failure = error;
    throw error;
  } finally {
    try { await handle.close(); }
    catch (error) {
      if (!failure) throw error;
      failure.suppressed = [...(failure.suppressed || []), error];
    }
  }
}

function looksLikeSVGHeader(header) {
  const sample = header.toString("utf8").replace(/^\uFEFF/, "").trimStart().toLowerCase();
  return sample.startsWith("<svg") || (sample.startsWith("<?xml") && sample.includes("<svg"));
}

async function realTemporaryImageRoots() {
  const candidates = [
    os.tmpdir(),
    process.env.TMPDIR,
  ];

  if (process.platform === "darwin") {
    candidates.push("/tmp");
    candidates.push(path.join(os.homedir(), "Library", "Caches", "com.raycast-x.macos", "clipboard"));
    candidates.push(path.join(os.homedir(), "Library", "Application Support", "CleanShot", "media"));
    candidates.push(path.join(os.homedir(), "Library", "Application Support", "CleanShot X", "media"));
  }

  const roots = await Promise.all(
    Array.from(new Set(candidates.filter(Boolean))).map((candidate) => realpathOrNull(candidate))
  );
  return Array.from(new Set(roots.filter(Boolean)));
}

// Read-only previews can scope non-git Codex scratch folders to cwd while rejecting broad roots.
async function resolveReadableWorkspaceRoot(cwd) {
  const realRepoRoot = await resolveRepoRoot(cwd).then(realpathOrNull).catch(() => null);
  if (realRepoRoot) {
    return realRepoRoot;
  }

  const realCwd = await realpathOrNull(cwd);
  if (!realCwd || isBroadWorkspaceRoot(realCwd)) {
    return null;
  }
  return realCwd;
}

async function resolveImageWorkspaceRoot(cwd) {
  return resolveReadableWorkspaceRoot(cwd);
}

// Handles assistant links that only include a filename by finding one unique workspace match.
async function resolveWorkspaceTextFilePath(cwd, requestedPath, realWorkspaceRoot) {
  const filePath = path.isAbsolute(requestedPath)
    ? path.resolve(requestedPath)
    : path.resolve(cwd, requestedPath);
  const realFilePath = await realpathOrNull(filePath);
  if (realFilePath || path.isAbsolute(requestedPath) || !realWorkspaceRoot) {
    return realFilePath;
  }

  if (!isBareFileName(requestedPath)) {
    return null;
  }

  return resolveUniqueWorkspaceBasenameMatch(realWorkspaceRoot, requestedPath);
}

async function resolveUniqueWorkspaceBasenameMatch(realWorkspaceRoot, requestedFileName) {
  const matches = await findWorkspaceBasenameMatches(realWorkspaceRoot, requestedFileName);
  if (matches.length === 0) {
    return null;
  }
  if (matches.length > 1) {
    throw workspaceError(
      "file_path_ambiguous",
      `Multiple files named "${requestedFileName}" exist in this workspace. Use a path with folders.`
    );
  }
  return matches[0];
}

async function findWorkspaceBasenameMatches(realWorkspaceRoot, requestedFileName) {
  const matches = [];
  const output = await git(realWorkspaceRoot, "ls-files", "-co", "--exclude-standard").catch(() => "");
  const relativePaths = output
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  for (const relativePath of relativePaths) {
    if (path.basename(relativePath) !== requestedFileName) {
      continue;
    }
    const realCandidate = await realpathOrNull(path.resolve(realWorkspaceRoot, relativePath));
    if (realCandidate && isPathInside(realCandidate, realWorkspaceRoot)) {
      matches.push(realCandidate);
      if (matches.length >= MAX_BASENAME_FALLBACK_MATCHES) {
        break;
      }
    }
  }
  return matches;
}

function isBareFileName(candidatePath) {
  return candidatePath === path.basename(candidatePath)
    && candidatePath !== "."
    && candidatePath !== "..";
}

function isBroadWorkspaceRoot(candidatePath) {
  const normalized = path.resolve(candidatePath);
  return normalized === path.parse(normalized).root
    || normalized === path.resolve(os.homedir());
}

function decodeUtf8TextFile(data) {
  const sample = data.subarray(0, Math.min(data.length, BINARY_SNIFF_BYTES));
  if (sample.includes(0)) {
    throw workspaceError("binary_file", "This file looks binary, so it cannot be shown as text.");
  }

  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(data);
  } catch {
    throw workspaceError("unsupported_text_encoding", "Only UTF-8 text files can be viewed.");
  }
}

function countLines(content) {
  if (!content) {
    return 0;
  }
  const newlineCount = (content.match(/\n/g) || []).length;
  return content.endsWith("\n") ? newlineCount : newlineCount + 1;
}

const thumbnailCache = new Map();
const thumbnailPending = new Map();
let thumbnailBytes = 0;
let thumbnailActive = 0;
const thumbnailWaiters = [];
async function readPreviewImageData(imagePath, maxPixelDimension, originalByteLength) {
  const stat = await fs.promises.stat(imagePath);
  const key = `${imagePath}:${downloadRevision(stat)}:${maxPixelDimension}`;
  const cached = thumbnailCache.get(key);
  if (cached) { thumbnailCache.delete(key); thumbnailCache.set(key,cached); return cached; }
  if (thumbnailPending.has(key)) return thumbnailPending.get(key);
  if (thumbnailPending.size >= 32) throw workspaceError('preview_busy','Image previews are busy; retry shortly');
  const work = (async () => {
    if (thumbnailActive >= 2) await new Promise(resolve => thumbnailWaiters.push(resolve));
    else thumbnailActive++;
    try { return await generatePreviewImageData(imagePath,maxPixelDimension,originalByteLength); }
    finally { const next=thumbnailWaiters.shift(); if(next) next(); else thumbnailActive--; }
  })().then(data => {
    thumbnailCache.set(key,data); thumbnailBytes += data.length;
    while (thumbnailBytes > 32 * 1024 * 1024) { const oldest=thumbnailCache.keys().next().value; thumbnailBytes-=thumbnailCache.get(oldest).length; thumbnailCache.delete(oldest); }
    return data;
  }).finally(()=>thumbnailPending.delete(key));
  thumbnailPending.set(key,work);
  return work;
}
async function generatePreviewImageData(imagePath, maxPixelDimension, originalByteLength) {
  if (process.platform === 'linux') {
    try {
      const {stdout} = await execFileAsync(process.env.REMODEX_IMAGE_PYTHON || '/usr/bin/python3', [path.join(__dirname,'image-preview-linux.py'),imagePath,String(maxPixelDimension)], {encoding:'buffer',maxBuffer:MAX_IMAGE_PREVIEW_READ_BYTES,timeout:IMAGE_PREVIEW_TOTAL_TIMEOUT_MS});
      return stdout;
    } catch(cause) {
      const error=workspaceError('image_preview_failed','Image preview conversion failed'); error.cause=cause; throw error;
    }
  }
  if (!usesNativeImagePreview()) {
    if (originalByteLength <= MAX_IMAGE_PREVIEW_READ_BYTES) {
      return fs.promises.readFile(imagePath);
    }

    throw workspaceError(
      "image_preview_unsupported_platform",
      "This computer cannot resize image previews yet. Try a smaller image or open it on the computer."
    );
  }

  let sawConversionFailure = false;
  const previewDeadline = Date.now() + IMAGE_PREVIEW_TOTAL_TIMEOUT_MS;
  for (const candidateDimension of previewPixelDimensionCandidates(maxPixelDimension)) {
    const remainingTimeoutMs = previewDeadline - Date.now();
    if (remainingTimeoutMs <= 0) {
      throw workspaceError(
        "image_preview_timed_out",
        "This image preview took too long to resize. Try a smaller image or open it on the computer."
      );
    }

    try {
      const previewData = await downsampleImageNative(
        imagePath,
        candidateDimension,
        Math.min(IMAGE_PREVIEW_TOOL_TIMEOUT_MS, remainingTimeoutMs)
      );
      if (previewData && previewData.length > 0 && previewData.length <= MAX_IMAGE_PREVIEW_READ_BYTES) {
        return previewData;
      }
    } catch (err) {
      if (isImagePreviewTimeoutError(err)) {
        throw workspaceError(
          "image_preview_timed_out",
          "This image preview took too long to resize. Try a smaller image or open it on the computer."
        );
      }
      sawConversionFailure = true;
    }
  }

  if (sawConversionFailure) {
    throw workspaceError(
      "image_preview_failed",
      "This image could not be converted into a lightweight phone preview."
    );
  }

  throw workspaceError(
    "image_preview_too_large",
    "This image preview is still too large to send to the phone."
  );
}

// SVGs are already compact vector source; send them as-is so the phone can render them in WebKit.
async function readSVGPreviewData(imagePath, originalByteLength) {
  if (originalByteLength > MAX_IMAGE_PREVIEW_READ_BYTES) {
    throw workspaceError(
      "image_too_large",
      "This SVG is too large to send to the phone. Open it on the Mac or move a smaller preview into the workspace."
    );
  }
  return fs.promises.readFile(imagePath);
}

function previewPixelDimensionCandidates(maxPixelDimension) {
  const dimensions = [];
  let next = maxPixelDimension;
  while (next >= MIN_IMAGE_PREVIEW_PIXEL_DIMENSION) {
    dimensions.push(next);
    if (next === MIN_IMAGE_PREVIEW_PIXEL_DIMENSION) {
      break;
    }
    next = Math.max(
      MIN_IMAGE_PREVIEW_PIXEL_DIMENSION,
      Math.floor(next * IMAGE_PREVIEW_RETRY_SCALE)
    );
  }

  // Hard-to-compress previews can stay oversized after one resize; these checkpoints keep retry behavior predictable.
  for (const checkpoint of [1024, 768, 512, 384, 256, MIN_IMAGE_PREVIEW_PIXEL_DIMENSION]) {
    if (checkpoint <= maxPixelDimension) {
      dimensions.push(checkpoint);
    }
  }

  return Array.from(new Set(dimensions)).sort((a, b) => b - a);
}

function usesNativeImagePreview() {
  const normalizedPlatform = String(process.platform || "").trim().toLowerCase();
  return normalizedPlatform === "darwin"
    || normalizedPlatform === "macos"
    || normalizedPlatform === "mac"
    || normalizedPlatform === "win32"
    || normalizedPlatform === "windows";
}

async function downsampleImageNative(imagePath, maxPixelDimension, timeoutMs = IMAGE_PREVIEW_TOOL_TIMEOUT_MS) {
  return usesWindowsImagePreview()
    ? downsampleImageWithPowerShell(imagePath, maxPixelDimension, timeoutMs)
    : downsampleImageWithSips(imagePath, maxPixelDimension, timeoutMs);
}

function usesWindowsImagePreview() {
  const normalizedPlatform = String(process.platform || "").trim().toLowerCase();
  return normalizedPlatform === "win32" || normalizedPlatform === "windows";
}

async function downsampleImageWithSips(imagePath, maxPixelDimension, timeoutMs = IMAGE_PREVIEW_TOOL_TIMEOUT_MS) {
  const tempDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "remodex-image-preview-"));
  const outputPath = path.join(tempDir, `preview${path.extname(imagePath) || ".png"}`);
  const command = resolveHostCommand("sips");
  try {
    await execFileAsync(command.file, [
      ...command.args,
      "-Z",
      String(maxPixelDimension),
      imagePath,
      "--out",
      outputPath,
    ], {
      timeout: Math.max(1, Math.floor(timeoutMs)),
      maxBuffer: 1024 * 1024,
    });
    return await fs.promises.readFile(outputPath);
  } finally {
    await fs.promises.rm(tempDir, { recursive: true, force: true }).catch(() => {});
  }
}

function resolveHostCommand(commandName) {
  if (path.delimiter !== ";") {
    return { file: commandName, args: [] };
  }

  const pathEntries = String(process.env.PATH || "")
    .split(path.delimiter)
    .filter(Boolean);
  for (const entry of pathEntries) {
    const jsShim = path.join(entry, `${commandName}.js`);
    if (fs.existsSync(jsShim)) {
      return { file: process.execPath, args: [jsShim] };
    }
  }
  return { file: commandName, args: [] };
}

async function downsampleImageWithPowerShell(imagePath, maxPixelDimension, timeoutMs = IMAGE_PREVIEW_TOOL_TIMEOUT_MS) {
  const tempDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "remodex-image-preview-"));
  const outputPath = path.join(tempDir, `preview${path.extname(imagePath) || ".png"}`);
  const scriptPath = path.join(tempDir, "resize-preview.ps1");
  const script = `
param(
  [Parameter(Mandatory=$true)][string]$InputPath,
  [Parameter(Mandatory=$true)][string]$OutputPath,
  [Parameter(Mandatory=$true)][int]$MaxDimension
)
Add-Type -AssemblyName System.Drawing
$image = [System.Drawing.Image]::FromFile($InputPath)
try {
  $longest = [Math]::Max($image.Width, $image.Height)
  if ($longest -le 0) { throw "Invalid image dimensions." }
  $scale = [Math]::Min(1.0, [double]$MaxDimension / [double]$longest)
  $targetWidth = [Math]::Max(1, [int][Math]::Round($image.Width * $scale))
  $targetHeight = [Math]::Max(1, [int][Math]::Round($image.Height * $scale))
  $bitmap = New-Object System.Drawing.Bitmap $targetWidth, $targetHeight
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
      $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
      $graphics.DrawImage($image, 0, 0, $targetWidth, $targetHeight)
    } finally {
      $graphics.Dispose()
    }
    $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
  } finally {
    if ($bitmap) { $bitmap.Dispose() }
  }
} finally {
  $image.Dispose()
}
`;
  try {
    await fs.promises.writeFile(scriptPath, script, "utf8");
    await execFileAsync("powershell.exe", [
      "-NoProfile",
      "-NonInteractive",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      scriptPath,
      imagePath,
      outputPath,
      String(maxPixelDimension),
    ], {
      timeout: Math.max(1, Math.floor(timeoutMs)),
      maxBuffer: 1024 * 1024,
      windowsHide: true,
    });
    return await fs.promises.readFile(outputPath);
  } finally {
    await fs.promises.rm(tempDir, { recursive: true, force: true }).catch(() => {});
  }
}

function isImagePreviewTimeoutError(err) {
  return err?.code === "ETIMEDOUT"
    || (err?.killed === true && err?.signal === "SIGTERM")
    || /timed out|timeout/i.test(String(err?.message || ""));
}

function isUnchangedImageRead(params, stat, maxPixelDimension) {
  const cachedByteLength = Number(params.ifByteLength);
  const cachedMtimeMs = Number(params.ifMtimeMs);
  const cachedPreviewMaxPixelDimension = Number(params.ifPreviewMaxPixelDimension || params.ifMaxPixelDimension);
  const previewDimensionMatches = maxPixelDimension
    ? Number.isFinite(cachedPreviewMaxPixelDimension) && cachedPreviewMaxPixelDimension === maxPixelDimension
    : !Number.isFinite(cachedPreviewMaxPixelDimension);
  return Number.isFinite(cachedByteLength)
    && Number.isFinite(cachedMtimeMs)
    && previewDimensionMatches
    && cachedByteLength === stat.size
    && cachedMtimeMs === stat.mtimeMs;
}

function isUnchangedTextFileRead(params, stat) {
  const cachedByteLength = Number(params.ifByteLength);
  const cachedMtimeMs = Number(params.ifMtimeMs);
  return Number.isFinite(cachedByteLength)
    && Number.isFinite(cachedMtimeMs)
    && cachedByteLength === stat.size
    && cachedMtimeMs === stat.mtimeMs;
}

// Validates the reverse patch against the current tree without writing repo files.
async function workspaceRevertPatchPreview(repoRoot, params) {
  const forwardPatch = resolveForwardPatch(params);
  const analysis = analyzeUnifiedPatch(forwardPatch);
  const stagedFiles = await findStagedTargetedFiles(repoRoot, analysis.affectedFiles);

  if (analysis.unsupportedReasons.length || stagedFiles.length) {
    return {
      canRevert: false,
      affectedFiles: analysis.affectedFiles,
      conflicts: [],
      unsupportedReasons: analysis.unsupportedReasons,
      stagedFiles,
    };
  }

  const applyCheck = await checkReversePatch(repoRoot, forwardPatch);
  const conflicts = applyCheck.ok
    ? []
    : parseApplyConflicts(applyCheck.stderr || applyCheck.stdout || "Patch does not apply.");

  return {
    canRevert: applyCheck.ok && conflicts.length === 0,
    affectedFiles: analysis.affectedFiles,
    conflicts,
    unsupportedReasons: [],
    stagedFiles,
  };
}

// Reverse-applies the patch only after the same safety checks pass in the locked mutation path.
async function workspaceRevertPatchApply(repoRoot, params) {
  const forwardPatch = resolveForwardPatch(params);
  const preview = await workspaceRevertPatchPreview(repoRoot, params);
  if (!preview.canRevert) {
    return {
      success: false,
      revertedFiles: [],
      conflicts: preview.conflicts,
      unsupportedReasons: preview.unsupportedReasons,
      stagedFiles: preview.stagedFiles,
    };
  }

  const checkedPatch = await checkReversePatch(repoRoot, forwardPatch);
  const applyResult = checkedPatch.ok
    ? await runGitApply(repoRoot, checkedPatch.applyArgs, forwardPatch)
    : checkedPatch;
  if (!applyResult.ok) {
    return {
      success: false,
      revertedFiles: [],
      conflicts: parseApplyConflicts(applyResult.stderr || applyResult.stdout || "Patch does not apply."),
      unsupportedReasons: [],
      stagedFiles: [],
      status: await gitStatus(repoRoot).catch(() => null),
    };
  }

  await resetTargetedFilesIndex(repoRoot, preview.affectedFiles);
  const status = await gitStatus(repoRoot).catch(() => null);
  return {
    success: true,
    revertedFiles: preview.affectedFiles,
    conflicts: [],
    unsupportedReasons: [],
    stagedFiles: [],
    status,
  };
}

// Validates a newest-first patch batch as one reverse operation so dependent patches see the right state.
async function workspaceRevertPatchBatchPreview(repoRoot, params) {
  const patches = resolveForwardPatchBatch(params);
  const analyses = patches.map((patch) => analyzeUnifiedPatch(patch.forwardPatch));
  const affectedFiles = uniqueSorted(analyses.flatMap((analysis) => analysis.affectedFiles));
  const unsupportedReasons = uniqueSorted(analyses.flatMap((analysis) => analysis.unsupportedReasons));
  const stagedFiles = await findStagedTargetedFiles(repoRoot, affectedFiles);

  if (unsupportedReasons.length || stagedFiles.length) {
    return {
      canRevert: false,
      affectedFiles,
      conflicts: [],
      unsupportedReasons,
      stagedFiles,
      patchResults: patches.map((patch, index) => ({
        id: patch.id,
        canRevert: analyses[index].unsupportedReasons.length === 0,
        unsupportedReasons: analyses[index].unsupportedReasons,
      })),
    };
  }

  const sequenceCheck = await previewReversePatchSequence(repoRoot, patches, affectedFiles);
  const conflicts = sequenceCheck.ok
    ? []
    : parseApplyConflicts(sequenceCheck.stderr || sequenceCheck.stdout || "Patch batch does not apply.");

  return {
    canRevert: sequenceCheck.ok && conflicts.length === 0,
    affectedFiles,
    conflicts,
    unsupportedReasons: [],
    stagedFiles,
    patchResults: patches.map((patch) => ({
      id: patch.id,
      canRevert: sequenceCheck.ok && conflicts.length === 0,
    })),
  };
}

// Applies all batch patches under one repo lock and only marks success after the full reverse patch lands.
async function workspaceRevertPatchBatchApply(repoRoot, params) {
  const patches = resolveForwardPatchBatch(params);
  const preview = await workspaceRevertPatchBatchPreview(repoRoot, params);
  if (!preview.canRevert) {
    return {
      success: false,
      revertedFiles: [],
      conflicts: preview.conflicts,
      unsupportedReasons: preview.unsupportedReasons,
      stagedFiles: preview.stagedFiles,
      patchResults: preview.patchResults || [],
    };
  }

  const applyResult = await applyReversePatchSequence(repoRoot, patches, preview.affectedFiles);
  if (!applyResult.ok) {
    return {
      success: false,
      revertedFiles: [],
      conflicts: parseApplyConflicts(applyResult.stderr || applyResult.stdout || "Patch batch does not apply."),
      unsupportedReasons: [],
      stagedFiles: [],
      patchResults: patches.map((patch) => ({
        id: patch.id,
        applied: applyResult.appliedPatchIds.includes(patch.id),
      })),
      status: await gitStatus(repoRoot).catch(() => null),
    };
  }

  await resetTargetedFilesIndex(repoRoot, preview.affectedFiles);
  const status = await gitStatus(repoRoot).catch(() => null);
  return {
    success: true,
    revertedFiles: preview.affectedFiles,
    conflicts: [],
    unsupportedReasons: [],
    stagedFiles: [],
    patchResults: patches.map((patch) => ({ id: patch.id, applied: true })),
    status,
  };
}

function resolveForwardPatch(params) {
  const forwardPatch =
    typeof params.forwardPatch === "string" ? params.forwardPatch : "";

  if (!forwardPatch.trim()) {
    throw workspaceError("missing_patch", "The request must include a non-empty forwardPatch.");
  }

  return forwardPatch.endsWith("\n") ? forwardPatch : `${forwardPatch}\n`;
}

function resolveForwardPatchBatch(params) {
  const rawPatches = Array.isArray(params.patches) ? params.patches : [];
  const patches = rawPatches.map((rawPatch, index) => {
    if (typeof rawPatch === "string") {
      return {
        id: String(index),
        forwardPatch: rawPatch.endsWith("\n") ? rawPatch : `${rawPatch}\n`,
      };
    }

    const forwardPatch = rawPatch && typeof rawPatch.forwardPatch === "string"
      ? rawPatch.forwardPatch
      : "";
    return {
      id: rawPatch && typeof rawPatch.id === "string" ? rawPatch.id : String(index),
      forwardPatch: forwardPatch.endsWith("\n") ? forwardPatch : `${forwardPatch}\n`,
    };
  }).filter((patch) => patch.forwardPatch.trim());

  if (!patches.length) {
    throw workspaceError("missing_patch", "The request must include at least one non-empty patch.");
  }

  return patches;
}

function analyzeUnifiedPatch(rawPatch) {
  const patch = rawPatch.trim();
  if (!patch) {
    return {
      affectedFiles: [],
      unsupportedReasons: ["No exact patch was captured."],
    };
  }

  const chunks = splitPatchIntoChunks(patch);
  if (!chunks.length) {
    return {
      affectedFiles: [],
      unsupportedReasons: ["No exact patch was captured."],
    };
  }

  const affectedFiles = [];
  const unsupportedReasons = new Set();

  for (const chunk of chunks) {
    const analysis = analyzePatchChunk(chunk);
    if (analysis.path) {
      affectedFiles.push(analysis.path);
    }
    for (const reason of analysis.unsupportedReasons) {
      unsupportedReasons.add(reason);
    }
  }

  if (!affectedFiles.length) {
    unsupportedReasons.add("No exact patch was captured.");
  }

  return {
    affectedFiles: [...new Set(affectedFiles)].sort(),
    unsupportedReasons: [...unsupportedReasons].sort(),
  };
}

function splitPatchIntoChunks(patch) {
  const lines = patch.split("\n");
  if (!lines.length) {
    return [];
  }

  const chunks = [];
  let current = [];

  for (const line of lines) {
    if (line.startsWith("diff --git ") && current.length) {
      chunks.push(current);
      current = [];
    }
    current.push(line);
  }

  if (current.length) {
    chunks.push(current);
  }

  return chunks;
}

function analyzePatchChunk(lines) {
  const path = extractPatchPath(lines);
  const isBinary = lines.some((line) => line.startsWith("Binary files ") || line === "GIT binary patch");
  const isRenameOrModeOnly = lines.some((line) =>
    line.startsWith("rename from ")
      || line.startsWith("rename to ")
      || line.startsWith("copy from ")
      || line.startsWith("copy to ")
      || line.startsWith("old mode ")
      || line.startsWith("new mode ")
      || line.startsWith("similarity index ")
      || line.startsWith("new file mode 120")
      || line.startsWith("deleted file mode 120")
  );

  let additions = 0;
  let deletions = 0;
  for (const line of lines) {
    if (line.startsWith("+") && !line.startsWith("+++")) {
      additions += 1;
    } else if (line.startsWith("-") && !line.startsWith("---")) {
      deletions += 1;
    }
  }

  const unsupportedReasons = [];
  if (isBinary) {
    unsupportedReasons.push("Binary changes are not auto-revertable in v1.");
  }
  if (isRenameOrModeOnly) {
    unsupportedReasons.push("Rename, mode-only, or symlink changes are not auto-revertable in v1.");
  }
  if (!path || (!additions && !deletions && !lines.includes("--- /dev/null") && !lines.includes("+++ /dev/null"))) {
    if (!isBinary && !isRenameOrModeOnly) {
      unsupportedReasons.push("No exact patch was captured.");
    }
  }

  return { path, unsupportedReasons };
}

function extractPatchPath(lines) {
  for (const line of lines) {
    if (line.startsWith("+++ ")) {
      const normalized = normalizeDiffPath(line.slice(4).trim());
      if (normalized && normalized !== "/dev/null") {
        return normalized;
      }
    }
  }

  for (const line of lines) {
    if (line.startsWith("diff --git ")) {
      const components = line.trim().split(/\s+/);
      if (components.length >= 4) {
        return normalizeDiffPath(components[3]);
      }
    }
  }

  return "";
}

function normalizeDiffPath(rawPath) {
  if (!rawPath) {
    return "";
  }

  if (rawPath.startsWith("a/") || rawPath.startsWith("b/")) {
    return rawPath.slice(2);
  }

  return rawPath;
}

async function findStagedTargetedFiles(cwd, affectedFiles) {
  if (!affectedFiles.length) {
    return [];
  }

  try {
    const output = await git(cwd, "diff", "--name-only", "--cached", "--", ...affectedFiles);
    return output
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .sort();
  } catch {
    return [];
  }
}

async function previewReversePatchSequence(repoRoot, patches, affectedFiles) {
  const sandboxRoot = await createPatchSandbox(repoRoot, affectedFiles);

  try {
    for (const patch of patches) {
      const check = await checkReversePatch(sandboxRoot, patch.forwardPatch);
      if (!check.ok) {
        return { ...check, failedPatchId: patch.id };
      }

      const applied = await runGitApply(sandboxRoot, check.applyArgs, patch.forwardPatch);
      if (!applied.ok) {
        return { ...applied, failedPatchId: patch.id };
      }
      await syncPatchSandboxIndex(sandboxRoot);
    }

    return { ok: true, stdout: "", stderr: "" };
  } finally {
    await fs.promises.rm(sandboxRoot, { recursive: true, force: true }).catch(() => {});
  }
}

async function applyReversePatchSequence(repoRoot, patches, affectedFiles) {
  const appliedPatchIds = [];
  const backup = await createPatchBackup(repoRoot, affectedFiles);

  try {
    for (const patch of patches) {
      const checkedPatch = await checkReversePatch(repoRoot, patch.forwardPatch);
      if (!checkedPatch.ok) {
        await restorePatchBackup(repoRoot, backup);
        return { ...checkedPatch, appliedPatchIds, failedPatchId: patch.id };
      }

      const appliedPatch = await runGitApply(repoRoot, checkedPatch.applyArgs, patch.forwardPatch);
      if (!appliedPatch.ok) {
        await restorePatchBackup(repoRoot, backup);
        return { ...appliedPatch, appliedPatchIds, failedPatchId: patch.id };
      }

      appliedPatchIds.push(patch.id);
    }

    return { ok: true, stdout: "", stderr: "", appliedPatchIds };
  } finally {
    await fs.promises.rm(backup.root, { recursive: true, force: true }).catch(() => {});
  }
}

async function createPatchSandbox(repoRoot, affectedFiles) {
  const sandboxRoot = await fs.promises.mkdtemp(path.join(os.tmpdir(), "remodex-revert-preview-"));

  for (const affectedFile of affectedFiles) {
    const sourcePath = path.resolve(repoRoot, affectedFile);
    if (!isPathInside(sourcePath, repoRoot)) {
      continue;
    }

    const destinationPath = path.resolve(sandboxRoot, affectedFile);
    if (!isPathInside(destinationPath, sandboxRoot) || !fs.existsSync(sourcePath)) {
      continue;
    }

    await fs.promises.mkdir(path.dirname(destinationPath), { recursive: true });
    await fs.promises.copyFile(sourcePath, destinationPath);
  }

  await initializePatchSandboxGitRepo(sandboxRoot);
  return sandboxRoot;
}

async function initializePatchSandboxGitRepo(sandboxRoot) {
  await git(sandboxRoot, "init", "-q");
  await git(sandboxRoot, "config", "user.email", "remodex@example.local");
  await git(sandboxRoot, "config", "user.name", "Remodex");
  await syncPatchSandboxIndex(sandboxRoot);
  await git(sandboxRoot, "commit", "-qm", "snapshot", "--allow-empty");
}

async function syncPatchSandboxIndex(sandboxRoot) {
  await git(sandboxRoot, "add", "-A");
}

async function createPatchBackup(repoRoot, affectedFiles) {
  const backupRoot = await fs.promises.mkdtemp(path.join(os.tmpdir(), "remodex-revert-backup-"));
  const entries = [];

  for (const affectedFile of affectedFiles) {
    const sourcePath = path.resolve(repoRoot, affectedFile);
    if (!isPathInside(sourcePath, repoRoot)) {
      continue;
    }

    const backupPath = path.resolve(backupRoot, affectedFile);
    if (!isPathInside(backupPath, backupRoot)) {
      continue;
    }

    const exists = fs.existsSync(sourcePath);
    entries.push({ relativePath: affectedFile, exists });
    if (!exists) {
      continue;
    }

    await fs.promises.mkdir(path.dirname(backupPath), { recursive: true });
    await fs.promises.copyFile(sourcePath, backupPath);
  }

  return { root: backupRoot, entries };
}

async function restorePatchBackup(repoRoot, backup) {
  for (const entry of backup.entries) {
    const targetPath = path.resolve(repoRoot, entry.relativePath);
    if (!isPathInside(targetPath, repoRoot)) {
      continue;
    }

    if (!entry.exists) {
      await fs.promises.rm(targetPath, { force: true, recursive: true }).catch(() => {});
      continue;
    }

    const backupPath = path.resolve(backup.root, entry.relativePath);
    if (!isPathInside(backupPath, backup.root)) {
      continue;
    }

    await fs.promises.mkdir(path.dirname(targetPath), { recursive: true });
    await fs.promises.copyFile(backupPath, targetPath);
  }

  await resetTargetedFilesIndex(repoRoot, backup.entries.map((entry) => entry.relativePath));
}

async function resetTargetedFilesIndex(cwd, affectedFiles) {
  if (!affectedFiles.length) {
    return;
  }

  await git(cwd, "reset", "-q", "--", ...affectedFiles);
}

async function checkReversePatch(cwd, patchText) {
  if (isFileLifecyclePatch(patchText)) {
    const plainCheck = await runGitApply(cwd, ["apply", "--reverse", "--check"], patchText);
    return { ...plainCheck, applyArgs: ["apply", "--reverse"] };
  }

  const codexCheckArgs = ["apply", "--reverse", "--check", "--3way"];
  const plainCheckArgs = ["apply", "--reverse", "--check"];
  const codexCheck = await runGitApply(cwd, codexCheckArgs, patchText);

  if (codexCheck.ok) {
    return { ...codexCheck, applyArgs: ["apply", "--reverse", "--3way"] };
  }

  // git apply --3way requires index/worktree agreement; assistant edits are usually unstaged.
  if (!isIndexMismatch(codexCheck)) {
    return { ...codexCheck, applyArgs: ["apply", "--reverse", "--3way"] };
  }

  const plainCheck = await runGitApply(cwd, plainCheckArgs, patchText);
  return { ...plainCheck, applyArgs: ["apply", "--reverse"] };
}

function isFileLifecyclePatch(patchText) {
  return String(patchText || "")
    .split("\n")
    .some((line) => line === "--- /dev/null" || line === "+++ /dev/null");
}

function isIndexMismatch(result) {
  const output = `${result.stderr || ""}\n${result.stdout || ""}`;
  return output.includes("does not match index");
}

function uniqueSorted(values) {
  return [...new Set(values.filter(Boolean))].sort();
}

async function runGitApply(cwd, args, patchText) {
  const tempPatchPath = await writeTempPatchFile(patchText);

  try {
    const { stdout, stderr } = await execFileAsync("git", [...args, tempPatchPath], {
      cwd,
      timeout: GIT_TIMEOUT_MS,
      maxBuffer: GIT_EXEC_MAX_BUFFER_BYTES,
    });
    return { ok: true, stdout, stderr };
  } catch (err) {
    return {
      ok: false,
      stdout: err.stdout || "",
      stderr: err.stderr || err.message || "",
    };
  } finally {
    try {
      fs.unlinkSync(tempPatchPath);
    } catch {
      // Ignore temp cleanup failures.
    }
  }
}

async function writeTempPatchFile(patchText) {
  const tempPatchPath = path.join(
    os.tmpdir(),
    `remodex-revert-${Date.now()}-${Math.random().toString(16).slice(2)}.patch`
  );
  await fs.promises.writeFile(tempPatchPath, patchText, "utf8");
  return tempPatchPath;
}

function parseApplyConflicts(stderr) {
  const lines = String(stderr || "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  const conflictsByPath = new Map();
  for (const line of lines) {
    let path = "unknown";
    const patchFailedMatch = line.match(/^error:\s+patch failed:\s+(.+?):\d+$/i);
    const doesNotApplyMatch = line.match(/^error:\s+(.+?):\s+patch does not apply$/i);

    if (patchFailedMatch) {
      path = patchFailedMatch[1];
    } else if (doesNotApplyMatch) {
      path = doesNotApplyMatch[1];
    }

    if (!conflictsByPath.has(path)) {
      conflictsByPath.set(path, { path, message: line });
    }
  }

  if (!conflictsByPath.size && lines.length) {
    return [{ path: "unknown", message: lines.join(" ") }];
  }

  return [...conflictsByPath.values()];
}

async function withRepoMutationLock(cwd, callback) {
  const previous = repoMutationLocks.get(cwd) || Promise.resolve();
  let releaseCurrent = null;
  const current = new Promise((resolve) => {
    releaseCurrent = resolve;
  });
  const chained = previous.then(() => current);
  repoMutationLocks.set(cwd, chained);

  await previous;
  try {
    return await callback();
  } finally {
    releaseCurrent();
    if (repoMutationLocks.get(cwd) === chained) {
      repoMutationLocks.delete(cwd);
    }
  }
}

async function resolveWorkspaceCwd(params) {
  const requestedCwd = firstNonEmptyString([params.cwd, params.currentWorkingDirectory]);

  if (!requestedCwd) {
    throw workspaceError(
      "missing_working_directory",
      "Workspace actions require a bound local working directory."
    );
  }

  if (!isExistingDirectory(requestedCwd)) {
    throw workspaceError(
      "missing_working_directory",
      "The requested local working directory does not exist on this Mac."
    );
  }

  return requestedCwd;
}

// Resolves the canonical repo root so revert safety checks stay stable from nested chat folders.
async function resolveRepoRoot(cwd) {
  try {
    const output = await git(cwd, "rev-parse", "--show-toplevel");
    const repoRoot = output.trim();
    if (repoRoot) {
      return repoRoot;
    }
  } catch {
    // Fall through to the user-facing error below.
  }

  throw workspaceError(
    "missing_working_directory",
    "The selected local folder is not inside a Git repository."
  );
}

function firstNonEmptyString(candidates) {
  for (const candidate of candidates) {
    if (typeof candidate !== "string") {
      continue;
    }

    const trimmed = candidate.trim();
    if (trimmed) {
      return trimmed;
    }
  }

  return null;
}

function isExistingDirectory(candidatePath) {
  try {
    return fs.statSync(candidatePath).isDirectory();
  } catch {
    return false;
  }
}

async function realpathOrNull(candidatePath) {
  try {
    return await fs.promises.realpath(candidatePath);
  } catch {
    return null;
  }
}

function isPathInside(candidatePath, rootPath) {
  const relative = path.relative(rootPath, candidatePath);
  return relative === "" || (relative && !relative.startsWith("..") && !path.isAbsolute(relative));
}

function workspaceError(errorCode, userMessage) {
  const err = new Error(userMessage);
  err.errorCode = errorCode;
  err.userMessage = userMessage;
  return err;
}

function git(cwd, ...args) {
  return execFileAsync("git", args, {
    cwd,
    timeout: GIT_TIMEOUT_MS,
    maxBuffer: GIT_EXEC_MAX_BUFFER_BYTES,
  })
    .then(({ stdout }) => stdout)
    .catch((err) => {
      const msg = (err.stderr || err.message || "").trim();
      throw new Error(msg || "git command failed");
    });
}

module.exports = { handleWorkspaceMethod, handleWorkspaceRequest, workspaceImageResponseFits, inlineUploadedTurnImages, downloadErrorDiagnostic };
