const base = location.pathname.endsWith("/") ? location.pathname : `${location.pathname}/`;
const $ = (selector) => document.querySelector(selector);
const fileInput = $("#fileInput");
const folderInput = $("#folderInput");
const dropZone = $("#dropZone");
const queue = $("#queue");
const transferPanel = $("#transferPanel");
const overallBar = $("#overallBar");
const overallPercent = $("#overallPercent");
const transferTitle = $("#transferTitle");
const transferDetail = $("#transferDetail");
const transferSize = $("#transferSize");
const fileList = $("#fileList");
const empty = $("#empty");
const toast = $("#toast");
let uploading = false;

const api = (path, params = {}) => {
  const url = new URL(`${base}api/${path}`, location.origin);
  Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, value));
  return url;
};

function readableSize(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

function showToast(message) {
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => { toast.hidden = true; }, 3200);
}

function extension(name) {
  const part = name.includes(".") ? name.split(".").pop() : "FILE";
  return part.slice(0, 4).toUpperCase();
}

function fileRow(file) {
  const row = document.createElement("div");
  row.className = "file-row";
  const icon = document.createElement("div");
  icon.className = "file-icon";
  icon.textContent = extension(file.name);
  const info = document.createElement("div");
  info.className = "file-info";
  const name = document.createElement("div");
  name.className = "file-name";
  name.textContent = file.name;
  name.title = file.name;
  const meta = document.createElement("div");
  meta.className = "file-meta";
  meta.textContent = `${readableSize(file.size)} · ${new Date(file.modified).toLocaleString()}`;
  info.append(name, meta);
  const actions = document.createElement("div");
  actions.className = "file-actions";
  const download = document.createElement("a");
  download.className = "action";
  download.textContent = "下载";
  download.href = api("download", { name: file.name });
  const remove = document.createElement("button");
  remove.className = "action delete";
  remove.textContent = "删除";
  remove.addEventListener("click", () => deleteFile(file.name));
  actions.append(download, remove);
  row.append(icon, info, actions);
  return row;
}

async function loadFiles() {
  try {
    const response = await fetch(api("files"), { cache: "no-store" });
    if (!response.ok) throw new Error("读取文件失败");
    const { files } = await response.json();
    fileList.replaceChildren(...files.map(fileRow));
    fileList.hidden = files.length === 0;
    empty.hidden = files.length !== 0;
    $("#heroFileCount").textContent = files.length;
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteFile(name) {
  if (!confirm(`确定删除“${name}”吗？`)) return;
  const response = await fetch(api("file", { name }), { method: "DELETE" });
  showToast(response.ok ? "文件已删除" : "删除失败");
  if (response.ok) loadFiles();
}

function makeUploadItem(file) {
  const item = document.createElement("div");
  item.className = "upload-item";
  const meta = document.createElement("div");
  meta.className = "upload-meta";
  const name = document.createElement("span");
  name.textContent = file.webkitRelativePath || file.name;
  const status = document.createElement("span");
  status.textContent = "等待中";
  meta.append(name, status);
  const track = document.createElement("div");
  track.className = "item-track";
  const bar = document.createElement("div");
  bar.className = "item-bar";
  track.append(bar);
  item.append(meta, track);
  return { item, status, bar };
}

function uploadOne(entry, onProgress) {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    const relativeName = entry.file.webkitRelativePath || entry.file.name;
    xhr.open("POST", api("upload", { name: relativeName }));
    entry.status.textContent = "上传中";
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable) return;
      entry.loaded = event.loaded;
      const percent = Math.round(event.loaded / event.total * 100);
      entry.bar.style.width = `${percent}%`;
      entry.status.textContent = `${percent}%`;
      onProgress();
    };
    xhr.onload = () => {
      entry.loaded = entry.file.size;
      entry.bar.style.width = "100%";
      entry.ok = xhr.status >= 200 && xhr.status < 300;
      entry.status.textContent = entry.ok ? "完成" : "失败";
      onProgress();
      resolve();
    };
    xhr.onerror = () => {
      entry.ok = false;
      entry.status.textContent = "连接失败";
      onProgress();
      resolve();
    };
    xhr.send(entry.file);
  });
}

async function uploadFiles(selectedFiles) {
  const files = [...selectedFiles].filter((file) => file.size >= 0);
  if (!files.length) {
    showToast("没有读取到文件。手机浏览器可能不支持选择文件夹，请改用选择文件。");
    return;
  }
  if (uploading) {
    showToast("请等待当前传输完成");
    return;
  }
  uploading = true;
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  const entries = files.map((file) => ({ file, loaded: 0, ok: null, ...makeUploadItem(file) }));
  queue.replaceChildren(...entries.map((entry) => entry.item));
  transferPanel.hidden = false;
  transferTitle.textContent = `已选择 ${files.length} 个文件`;
  transferDetail.textContent = "正在开始传输...";
  transferSize.textContent = `0 B / ${readableSize(totalBytes)}`;
  transferPanel.scrollIntoView({ behavior: "smooth", block: "nearest" });

  const updateOverall = () => {
    const loaded = entries.reduce((sum, entry) => sum + entry.loaded, 0);
    const finished = entries.filter((entry) => entry.ok !== null).length;
    const failed = entries.filter((entry) => entry.ok === false).length;
    const percent = totalBytes ? Math.round(loaded / totalBytes * 100) : Math.round(finished / entries.length * 100);
    overallBar.style.width = `${percent}%`;
    overallPercent.textContent = `${percent}%`;
    transferDetail.textContent = `${finished} / ${entries.length} 完成${failed ? ` · ${failed} 个失败` : ""}`;
    transferSize.textContent = `${readableSize(loaded)} / ${readableSize(totalBytes)}`;
  };

  let next = 0;
  const worker = async () => {
    while (next < entries.length) {
      const entry = entries[next++];
      await uploadOne(entry, updateOverall);
    }
  };
  await Promise.all(Array.from({ length: Math.min(3, entries.length) }, worker));
  updateOverall();
  const failed = entries.filter((entry) => entry.ok === false).length;
  transferTitle.textContent = failed ? "部分文件传输失败" : "传输完成";
  showToast(failed ? `${failed} 个文件上传失败` : `${files.length} 个文件已上传`);
  uploading = false;
  fileInput.value = "";
  folderInput.value = "";
  await loadFiles();
}

$("#chooseFiles").addEventListener("click", () => fileInput.click());
$("#chooseFolder").addEventListener("click", () => {
  if (!("webkitdirectory" in folderInput)) {
    showToast("当前浏览器不支持选择文件夹，请使用 Chrome、Edge 或安卓浏览器");
    return;
  }
  folderInput.click();
});
fileInput.addEventListener("change", () => uploadFiles(fileInput.files));
folderInput.addEventListener("change", () => uploadFiles(folderInput.files));
$("#refresh").addEventListener("click", loadFiles);

for (const eventName of ["dragenter", "dragover"]) {
  dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropZone.classList.add("dragging");
  });
}
for (const eventName of ["dragleave", "drop"]) {
  dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropZone.classList.remove("dragging");
  });
}
dropZone.addEventListener("drop", (event) => uploadFiles(event.dataTransfer.files));

loadFiles();
setInterval(loadFiles, 10000);
