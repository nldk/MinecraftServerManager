const serversEl = document.getElementById('servers');
const refreshBtn = document.getElementById('refreshBtn');
const consoleTitle = document.getElementById('consoleTitle');
const statusEl = document.getElementById('status');
const logOutput = document.getElementById('logOutput');
const commandForm = document.getElementById('commandForm');
const commandInput = document.getElementById('commandInput');
const sendBtn = document.getElementById('sendBtn');
const createServerForm = document.getElementById('createServerForm');
const serverNameInput = document.getElementById('serverNameInput');
const serverTypeSelect = document.getElementById('serverTypeSelect');
const serverPortInput = document.getElementById('serverPortInput');
const serverConfigForm = document.getElementById('serverConfigForm');
const eulaAcceptedInput = document.getElementById('eulaAcceptedInput');
const saveServerConfigBtn = document.getElementById('saveServerConfigBtn');
const propertiesList = document.getElementById('propertiesList');
const propertyForm = document.getElementById('propertyForm');
const propertyKeyInput = document.getElementById('propertyKeyInput');
const propertyValueInput = document.getElementById('propertyValueInput');
const savePropertyBtn = document.getElementById('savePropertyBtn');
const reloadConfigBtn = document.getElementById('reloadConfigBtn');

const INVALID_NAME_PATTERN = /[\/\\]/;

let selectedServer = null;
let socket = null;
let selectedServerRunning = false;
let refreshTimer = null;
let selectedServerInfoTimer = null;
let selectedPropertyKey = '';
let currentServerProperties = [];

// File manager state
let selectedFileManagerServer = null;
let currentFileManagerPath = '';
let selectedFilePath = '';
let selectedFileDirty = false;
let plainEditorEl = null;
let plainEditorSuppressDirty = false;
let monacoEditor = null;
let monacoInitPromise = null;
let monacoSuppressDirty = false;
const FILE_EDITOR_PLACEHOLDER = 'Select a file from the list to edit.';

function setStatus(message) {
  statusEl.textContent = message;
}

function appendLog(line) {
  logOutput.textContent += `${line}\n`;
  logOutput.scrollTop = logOutput.scrollHeight;
}

function clearLog() {
  logOutput.textContent = '';
}

function ensureMonacoEditor() {
  if (monacoEditor) return Promise.resolve(monacoEditor);
  if (monacoInitPromise) return monacoInitPromise;

  monacoInitPromise = new Promise((resolve, reject) => {
    const container = document.getElementById('fileEditor');
    if (!container) return reject(new Error('Editor container not found.'));
    if (!window.require) return reject(new Error('Monaco loader not available.'));

    container.classList.add('monaco-editor-container');
    window.require.config({
      paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.52.2/min/vs' },
    });

    window.require(['vs/editor/editor.main'], () => {
      monacoEditor = window.monaco.editor.create(container, {
        value: FILE_EDITOR_PLACEHOLDER,
        language: 'plaintext',
        theme: 'vs-dark',
        automaticLayout: true,
        readOnly: true,
        minimap: { enabled: true },
        fontSize: 13,
        lineHeight: 20,
        scrollBeyondLastLine: false,
      });

      monacoEditor.onDidChangeModelContent(() => {
        if (!selectedFilePath || monacoSuppressDirty || plainEditorSuppressDirty) return;
        selectedFileDirty = true;
        updateFileEditorState();
      });

      resolve(monacoEditor);
    }, reject);
  });

  return monacoInitPromise;
}

function languageFromPath(path) {
  const ext = getFileExtension(path);
  const map = {
    js: 'javascript',
    mjs: 'javascript',
    cjs: 'javascript',
    ts: 'typescript',
    json: 'json',
    yml: 'yaml',
    yaml: 'yaml',
    xml: 'xml',
    html: 'html',
    htm: 'html',
    css: 'css',
    scss: 'scss',
    less: 'less',
    md: 'markdown',
    java: 'java',
    kt: 'kotlin',
    properties: 'ini',
    toml: 'ini',
    sh: 'shell',
    bat: 'bat',
    ps1: 'powershell',
    sql: 'sql',
    py: 'python',
    rb: 'ruby',
    go: 'go',
    rs: 'rust',
    c: 'c',
    h: 'cpp',
    cpp: 'cpp',
    cc: 'cpp',
    hpp: 'cpp',
    cs: 'csharp',
    php: 'php',
    dockerfile: 'dockerfile',
  };
  return map[ext] || 'plaintext';
}

function setEditorLanguageForPath(path) {
  if (!monacoEditor || !window.monaco) return;
  const model = monacoEditor.getModel();
  if (!model) return;
  window.monaco.editor.setModelLanguage(model, languageFromPath(path));
}

function ensureFileManagerElements() {
  let panel = document.getElementById('fileManagerPanel');
  if (panel) return;

  panel = document.createElement('section');
  panel.id = 'fileManagerPanel';
  panel.className = 'panel file-manager';
  panel.innerHTML = `
    <h2 id="fileManagerTitle">File Manager</h2>
    <div id="fileManagerServerLabel" class="muted">Select a server from the list.</div>
    <div class="file-manager-toolbar">
      <button id="fileUpBtn" type="button">Up</button>
      <button id="fileRefreshBtn" type="button">Refresh</button>
      <button id="fileUploadBtn" type="button">Upload</button>
      <button id="fileDownloadBtn" type="button">Download</button>
      <button id="fileSaveBtn" type="button">Save</button>
      <input id="fileUploadInput" type="file" hidden />
    </div>
    <div id="fileManagerPath" class="muted">Path: /</div>
    <div id="fileEntries" class="file-entries">No server selected.</div>
    <div class="file-editor-head">
      <strong id="fileEditorPath">No file selected</strong>
      <span id="fileDirtyBadge" class="muted"></span>
    </div>
    <div
      id="fileEditor"
      style="height: 700px; min-height: 700px;"
    ></div>
  `;

  const targetParent = propertiesList?.closest('section')?.parentElement || document.body;
  targetParent.appendChild(panel);

  document.getElementById('fileUpBtn').addEventListener('click', () => navigateFileManagerUp());
  document.getElementById('fileRefreshBtn').addEventListener('click', () => loadFileList());
  document.getElementById('fileUploadBtn').addEventListener('click', () => {
    const uploadInput = document.getElementById('fileUploadInput');
    uploadInput.value = '';
    uploadInput.click();
  });
  document.getElementById('fileUploadInput').addEventListener('change', async (event) => {
    const input = event.target;
    if (input.files && input.files.length > 0) {
      await uploadFile(input.files[0]);
    }
  });
  document.getElementById('fileDownloadBtn').addEventListener('click', () => downloadSelectedFile());
  document.getElementById('fileSaveBtn').addEventListener('click', () => saveSelectedFile());

  const editor = document.getElementById('fileEditor');
  plainEditorEl = editor;
  ensureMonacoEditor()
    .then(() => {
      setEditorValue(FILE_EDITOR_PLACEHOLDER);
      setEditorEnabled(false);
    })
    .catch((err) => setStatus(`Failed to initialize editor: ${err.message}`));
}

function getFileExtension(path) {
  const name = (path || '').split('/').pop() || '';
  const index = name.lastIndexOf('.');
  return index >= 0 ? name.slice(index + 1).toLowerCase() : '';
}

function setEditorValue(content) {
  const value = content || '';
  if (monacoEditor) {
    monacoSuppressDirty = true;
    monacoEditor.setValue(value);
    monacoSuppressDirty = false;
    return;
  }
  const editor = plainEditorEl || document.getElementById('fileEditor');
  if (!editor) return;
  plainEditorSuppressDirty = true;
  editor.textContent = value;
  plainEditorSuppressDirty = false;
}

function getEditorValue() {
  if (monacoEditor) return monacoEditor.getValue();
  const editor = plainEditorEl || document.getElementById('fileEditor');
  return editor ? (editor.textContent || '') : '';
}

function setEditorEnabled(enabled) {
  if (monacoEditor) {
    monacoEditor.updateOptions({ readOnly: !enabled });
    return;
  }
  const editor = plainEditorEl || document.getElementById('fileEditor');
  if (!editor) return;
  editor.contentEditable = enabled ? 'true' : 'false';
  editor.style.opacity = enabled ? '1' : '0.75';
}

function updateConsoleInputEnabled() {
  const enabled = Boolean(selectedServer && selectedServerRunning);
  commandInput.disabled = !enabled;
  sendBtn.disabled = !enabled;
}

function updateConfigFormEnabled() {
  const enabled = Boolean(selectedServer);
  eulaAcceptedInput.disabled = !enabled;
  saveServerConfigBtn.disabled = !enabled;
  propertyKeyInput.disabled = !enabled;
  propertyValueInput.disabled = !enabled;
  reloadConfigBtn.disabled = !enabled;
  savePropertyBtn.disabled = !enabled || !propertyKeyInput.value.trim();
}

function renderPropertiesList() {
  propertiesList.innerHTML = '';

  if (!selectedServer) {
    propertiesList.textContent = 'Select a server to view configuration.';
    return;
  }

  if (!currentServerProperties.length) {
    propertiesList.textContent = 'No server.properties values found yet.';
    return;
  }

  currentServerProperties.forEach((property) => {
    const row = document.createElement('div');
    row.className = 'property-row';

    const details = document.createElement('div');
    details.className = 'property-details';

    const keyEl = document.createElement('div');
    keyEl.className = 'property-key';
    keyEl.textContent = property.key;

    const valueEl = document.createElement('div');
    valueEl.className = 'property-value';
    valueEl.textContent = property.value;

    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.textContent = selectedPropertyKey === property.key ? 'Editing' : 'Edit';
    editBtn.disabled = selectedPropertyKey === property.key;
    editBtn.addEventListener('click', () => {
      selectedPropertyKey = property.key;
      propertyKeyInput.value = property.key;
      propertyValueInput.value = property.value;
      updateConfigFormEnabled();
      renderPropertiesList();
      propertyValueInput.focus();
    });

    details.appendChild(keyEl);
    details.appendChild(valueEl);
    row.appendChild(details);
    row.appendChild(editBtn);
    propertiesList.appendChild(row);
  });
}

async function api(path, method = 'GET', body = null) {
  const options = { method };
  if (body) {
    options.headers = { 'Content-Type': 'application/json' };
    options.body = JSON.stringify(body);
  }

  const response = await fetch(path, options);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed (${response.status})`);
  }
  return response;
}

async function loadServers(statusMessage = null) {
  setStatus('Loading servers...');
  try {
    const response = await api('/servers');
    const servers = await response.json();
    renderServers(servers);
    setStatus(statusMessage ?? (selectedServer ? `Selected: ${selectedServer}` : 'Select a server.'));
    return servers;
  } catch (err) {
    setStatus(`Failed to load servers: ${err.message}`);
    return [];
  }
}

async function loadServerConfig(name = selectedServer) {
  if (!name) {
    currentServerProperties = [];
    selectedPropertyKey = '';
    propertyKeyInput.value = '';
    propertyValueInput.value = '';
    renderPropertiesList();
    updateConfigFormEnabled();
    return;
  }

  try {
    const response = await api(`/servers/${encodeURIComponent(name)}/config`);
    const payload = await response.json();
    currentServerProperties = Array.isArray(payload.properties) ? payload.properties : [];

    if (selectedPropertyKey) {
      const selectedProperty = currentServerProperties.find((property) => property.key === selectedPropertyKey);
      if (selectedProperty) {
        propertyKeyInput.value = selectedProperty.key;
        propertyValueInput.value = selectedProperty.value;
      } else {
        selectedPropertyKey = '';
        propertyKeyInput.value = '';
        propertyValueInput.value = '';
      }
    }

    renderPropertiesList();
    updateConfigFormEnabled();
  } catch (err) {
    currentServerProperties = [];
    renderPropertiesList();
    setStatus(`Failed to load configuration for ${name}: ${err.message}`);
  }
}

async function startServer(name) {
  try {
    await api(`/servers/${encodeURIComponent(name)}/start`, 'POST');
    setStatus(`Started ${name}`);
    await loadServers();
  } catch (err) {
    const message = String(err?.message || '');
    const needsEula = /eula/i.test(message);
    if (needsEula) {
      try {
        await agreeToEula(name);
        await api(`/servers/${encodeURIComponent(name)}/start`, 'POST');
        setStatus(`Accepted EULA and started ${name}`);
        await loadServers();
      } catch (retryErr) {
        setStatus(`Failed to start ${name}: ${retryErr.message}`);
      }
      return;
    }
    setStatus(`Failed to start ${name}: ${err.message}`);
  }
}

async function agreeToEula(name, accepted = true) {
  await api(`/servers/${encodeURIComponent(name)}/eula`, 'POST', { accepted });
}

async function saveServerConfig() {
  if (!selectedServer) {
    setStatus('Select a server before saving configuration.');
    return;
  }

  try {
    await agreeToEula(selectedServer, eulaAcceptedInput.checked);
    await loadServers(`Saved EULA for ${selectedServer}`);
    await loadServerConfig(selectedServer);
  } catch (err) {
    setStatus(`Failed to save configuration for ${selectedServer}: ${err.message}`);
  }
}

async function loadSingleServerInfo(name) {
  const response = await api(`/server/${encodeURIComponent(name)}/info`);
  return response.json();
}

function patchSelectedServerCardInfo(server) {
  if (!server || !server.name) return;
  const cards = Array.from(serversEl.querySelectorAll('.server-card'));
  for (const card of cards) {
    const row = card.querySelector('.server-row strong');
    if (!row || row.textContent !== server.name) continue;
    const info = card.querySelector('div:nth-child(2)');
    if (info) {
      info.textContent = `Port ${server.port} - ${server.running ? 'Running' : 'Stopped'}`;
    }
    break;
  }
}

async function refreshSelectedServerInfo() {
  if (!selectedServer) return;
  try {
    const server = await loadSingleServerInfo(selectedServer);
    selectedServerRunning = Boolean(server.running);
    patchSelectedServerCardInfo(server);
    updateConsoleInputEnabled();
  } catch {
    // ignore polling failures
  }
}

function stopSelectedServerInfoRefresh() {
  if (selectedServerInfoTimer) {
    window.clearInterval(selectedServerInfoTimer);
    selectedServerInfoTimer = null;
  }
}

function startSelectedServerInfoRefresh(intervalMs = 1500) {
  stopSelectedServerInfoRefresh();
  if (!selectedServer) return;
  refreshSelectedServerInfo();
  selectedServerInfoTimer = window.setInterval(() => {
    refreshSelectedServerInfo();
  }, intervalMs);
}

function resetConsole() {
  selectedServer = null;
  selectedServerRunning = false;
  stopSelectedServerInfoRefresh();
  disconnectSocket();
  clearLog();
  consoleTitle.textContent = 'Console';
  commandInput.value = '';
  commandInput.disabled = true;
  sendBtn.disabled = true;
  eulaAcceptedInput.checked = false;
  selectedPropertyKey = '';
  currentServerProperties = [];
  propertyKeyInput.value = '';
  propertyValueInput.value = '';
  renderPropertiesList();
  updateConfigFormEnabled();
  setFileManagerServer(null);
}

function validateServerForm(name, port) {
  if (!name) {
    return 'Server name is required.';
  }
  if (INVALID_NAME_PATTERN.test(name)) {
    return 'Server name cannot contain slashes.';
  }
  if (!Number.isInteger(port) || port < 1024 || port > 65535) {
    return 'Port must be a whole number between 1024 and 65535.';
  }
  return null;
}

async function createServer(name, type, port) {
  const validationError = validateServerForm(name, port);
  if (validationError) {
    setStatus(validationError);
    return;
  }

  try {
    await api('/server/create', 'POST', { name, type, running: false, port });
    createServerForm.reset();
    serverTypeSelect.value = 'VANILLA';
    serverPortInput.value = '25565';

    const servers = await loadServers(`Created ${name}`);
    const createdServer = servers.find((server) => server.name === name);
    if (createdServer) {
      selectServer(createdServer.name);
      commandInput.focus();
    }
  } catch (err) {
    setStatus(`Failed to create ${name}: ${err.message}`);
  }
}

async function deleteServer(name) {
  if (!window.confirm(`Delete ${name}? This will stop the server first.`)) {
    return;
  }

  try {
    await api(`/server/delete/${encodeURIComponent(name)}`, 'POST');
    if (selectedServer === name) {
      resetConsole();
      setStatus(`Deleted ${name}`);
    } else {
      setStatus(`Deleted ${name}`);
    }
    await loadServers();
  } catch (err) {
    setStatus(`Failed to delete ${name}: ${err.message}`);
  }
}

function selectServer(name) {
  selectedServer = name;
  startSelectedServerInfoRefresh();
  consoleTitle.textContent = `Console: ${name}`;
  commandInput.disabled = true;
  sendBtn.disabled = true;
  eulaAcceptedInput.checked = true;
  selectedPropertyKey = '';
  propertyKeyInput.value = '';
  propertyValueInput.value = '';
  currentServerProperties = [];
  renderPropertiesList();
  updateConfigFormEnabled();
  loadServerConfig(name);
  setFileManagerServer(name);
  setStatus(`Selected: ${name}`);
}

function syncSelectedServer(servers) {
  if (!selectedServer) return;

  const current = servers.find((server) => server.name === selectedServer);
  if (!current) {
    resetConsole();
    return;
  }

  selectedServerRunning = Boolean(current.running);

  if (selectedServerRunning && (!socket || socket.readyState === WebSocket.CLOSED)) {
    connectSocket(selectedServer);
  }

  if (!selectedServerRunning && socket) {
    disconnectSocket();
    setStatus(`${selectedServer} stopped`);
  }

  updateConsoleInputEnabled();
  updateConfigFormEnabled();
}

function renderServers(servers) {
  serversEl.innerHTML = '';

  if (selectedServer && !servers.some((server) => server.name === selectedServer)) {
    resetConsole();
  }

  syncSelectedServer(servers);

  if (!servers.length) {
    const empty = document.createElement('div');
    empty.textContent = 'No servers found.';
    serversEl.appendChild(empty);
    return;
  }

  servers.forEach((server) => {
    const card = document.createElement('div');
    card.className = 'server-card';

    const row = document.createElement('div');
    row.className = 'server-row';
    row.innerHTML = `<strong>${server.name}</strong><span>${server.type}</span>`;

    const info = document.createElement('div');
    info.textContent = `Port ${server.port} - ${server.running ? 'Running' : 'Stopped'}`;

    const actions = document.createElement('div');
    actions.className = 'server-actions';

    const viewBtn = document.createElement('button');
    viewBtn.type = 'button';
    viewBtn.textContent = 'Open Console';
    viewBtn.addEventListener('click', () => selectServer(server.name));

    const startBtn = document.createElement('button');
    startBtn.type = 'button';
    startBtn.textContent = 'Start';
    startBtn.disabled = server.running;
    startBtn.addEventListener('click', () => startServer(server.name));

    const stopBtn = document.createElement('button');
    stopBtn.type = 'button';
    stopBtn.textContent = 'Stop';
    stopBtn.disabled = !server.running;
    stopBtn.addEventListener('click', () => stopServer(server.name));

    const deleteBtn = document.createElement('button');
    deleteBtn.type = 'button';
    deleteBtn.textContent = 'Delete';
    deleteBtn.className = 'danger';
    deleteBtn.addEventListener('click', () => deleteServer(server.name));

    const filesBtn = document.createElement('button');
    filesBtn.type = 'button';
    filesBtn.textContent = 'File Manager';
    filesBtn.addEventListener('click', () => {
      selectServer(server.name);
      setFileManagerServer(server.name);
      const panel = document.getElementById('fileManagerPanel');
      if (panel) {
        panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    });

    actions.appendChild(viewBtn);
    actions.appendChild(startBtn);
    actions.appendChild(stopBtn);
    actions.appendChild(deleteBtn);
    actions.appendChild(filesBtn);

    card.appendChild(row);
    card.appendChild(info);
    card.appendChild(actions);
    serversEl.appendChild(card);
  });
}

refreshBtn.addEventListener('click', () => loadServers());

commandForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const command = commandInput.value.trim();
  if (!command) return;
  await sendCommand(command);
  commandInput.value = '';
});

createServerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const name = serverNameInput.value.trim();
  const type = serverTypeSelect.value;
  const port = Number.parseInt(serverPortInput.value, 10);
  await createServer(name, type, port);
});

serverConfigForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  await saveServerConfig();
});

propertyForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  await saveProperty();
});

propertyKeyInput.addEventListener('input', () => {
  selectedPropertyKey = propertyKeyInput.value.trim();
  savePropertyBtn.disabled = !selectedServer || !selectedPropertyKey;
});

reloadConfigBtn.addEventListener('click', () => {
  loadServerConfig();
});

window.addEventListener('beforeunload', () => {
  disconnectSocket();
  stopSelectedServerInfoRefresh();
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
    refreshTimer = null;
  }
});

window.addEventListener('resize', () => {
  if (monacoEditor) monacoEditor.layout();
});

renderPropertiesList();
updateConfigFormEnabled();
ensureFileManagerElements();
resetFileManagerEditor();
loadServers();
startAutoRefresh();

async function saveProperty() {
  if (!selectedServer) {
    setStatus('Select a server before saving a property.');
    return;
  }

  const key = propertyKeyInput.value.trim();
  if (!key) {
    setStatus('Property key is required.');
    return;
  }

  try {
    await api(`/servers/${encodeURIComponent(selectedServer)}/config`, 'POST', {
      key,
      value: propertyValueInput.value,
    });
    selectedPropertyKey = key;

    const normalizedKey = key.toLowerCase();
    const isPortProperty = normalizedKey === 'server-port' || normalizedKey === 'port';

    await loadServerConfig(selectedServer);

    if (isPortProperty) {
      try {
        const updatedServer = await loadSingleServerInfo(selectedServer);
        patchSelectedServerCardInfo(updatedServer);
      } catch {
        // ignore polling race
      }
      await loadServers();
      setStatus(`Saved property ${key} for ${selectedServer}`);
    } else {
      await loadServers(`Saved property ${key} for ${selectedServer}`);
    }
  } catch (err) {
    setStatus(`Failed to save property for ${selectedServer}: ${err.message}`);
  }
}

function disconnectSocket() {
  if (!socket) return;
  try {
    socket.onopen = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
    if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
      socket.close();
    }
  } catch {
    // ignore errors
  }
  socket = null;
}

function connectSocket(name) {
  if (!name || (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING))) {
    return;
  }

  try {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(`${protocol}//${window.location.host}/servers/${encodeURIComponent(name)}`);

    socket.onmessage = (event) => appendLog(typeof event.data === 'string' ? event.data : '');
    socket.onerror = () => {
      socket = null;
    };
    socket.onclose = () => {
      socket = null;
      updateConsoleInputEnabled();
    };
  } catch {
    socket = null;
  }
}

async function sendCommand(command) {
  if (!selectedServer || !command) return;
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(command);
    return;
  }
  await api(`/server/${encodeURIComponent(selectedServer)}/command`, 'POST', { command });
}

async function stopServer(name) {
  try {
    await api(`/server/${encodeURIComponent(name)}/stop`, 'POST');
    await loadServers(`Stopped ${name}`);
  } catch (err) {
    setStatus(`Failed to stop ${name}: ${err.message}`);
  }
}

function updateFileEditorState() {
  const pathEl = document.getElementById('fileEditorPath');
  const dirtyEl = document.getElementById('fileDirtyBadge');
  const downloadBtn = document.getElementById('fileDownloadBtn');
  const saveBtn = document.getElementById('fileSaveBtn');

  if (pathEl) pathEl.textContent = selectedFilePath || 'No file selected';
  if (dirtyEl) dirtyEl.textContent = selectedFileDirty ? 'Modified' : '';
  if (downloadBtn) downloadBtn.disabled = !selectedFilePath;
  if (saveBtn) saveBtn.disabled = !selectedFilePath;
}

function resetFileManagerEditor() {
  selectedFilePath = '';
  selectedFileDirty = false;
  setEditorValue(FILE_EDITOR_PLACEHOLDER);
  setEditorLanguageForPath('');
  setEditorEnabled(false);
  updateFileEditorState();
}

function setFileManagerServer(name) {
  selectedFileManagerServer = name;
  currentFileManagerPath = '';
  const labelEl = document.getElementById('fileManagerServerLabel');
  const pathEl = document.getElementById('fileManagerPath');

  if (labelEl) {
    labelEl.textContent = name ? `Server: ${name}` : 'Select a server from the list.';
  }
  if (pathEl) {
    pathEl.textContent = 'Path: /';
  }

  resetFileManagerEditor();
}

function navigateFileManagerUp() {
  if (!currentFileManagerPath) return;
  const parts = currentFileManagerPath.split('/').filter(Boolean);
  parts.pop();
  currentFileManagerPath = parts.join('/');
  loadFileList();
}

async function loadFileList() {
  const entriesEl = document.getElementById('fileEntries');
  if (!entriesEl) return;
  if (!selectedFileManagerServer) {
    entriesEl.textContent = 'No server selected.';
    return;
  }
  entriesEl.textContent = 'File manager listing is not loaded yet.';
}

async function uploadFile() {
  setStatus('Upload not implemented yet.');
}

async function downloadSelectedFile() {
  if (!selectedFilePath) {
    setStatus('Select a file before downloading.');
    return;
  }
  setStatus(`Download not implemented for ${selectedFilePath}.`);
}

async function saveSelectedFile() {
  if (!selectedFilePath) {
    setStatus('Select a file before saving.');
    return;
  }
  setStatus(`Save not implemented for ${selectedFilePath}.`);
}

function startAutoRefresh(intervalMs = 5000) {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
  }
  refreshTimer = window.setInterval(() => {
    loadServers();
  }, intervalMs);
}
