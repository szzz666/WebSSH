(() => {
  'use strict';
  const configKey = 'webssh.settings';
  const connectionsKey = 'webssh.connections';
  const historyKey = 'webssh.history';
  const activeConnectionKey = 'webssh.activeConnection';
  const iconPositionKey = 'webssh.desktop-icons';
  const desktopDirectory = '/root/.webssh/desktop';
  const systemTheme = matchMedia('(prefers-color-scheme: dark)');
  const icons = { folder: '#i-folder', file: '#i-file', download: '#i-download', trash: '#i-trash', copy: '#i-copy', refresh: '#i-refresh', settings: '#i-settings' };
  const terminalThemes = {
    light: { background: '#f5faff', foreground: '#10233e', cursor: '#1379ea', selectionBackground: '#1379ea40' },
    dark: { background: '#071523', foreground: '#edf6ff', cursor: '#65b5ff', selectionBackground: '#65b5ff52' }
  };
  const blankForm = () => ({ id: '', name: '', host: '', port: 22, username: '', authMethod: 'password', password: '', privateKey: '', passphrase: '' });
  const safeConnection = item => item && typeof item.host === 'string' && item.host.trim() && typeof item.username === 'string' && item.username.trim() && Number(item.port) >= 1 && Number(item.port) <= 65535 && ['password', 'key'].includes(item.authMethod);
  const normalizeConnection = data => ({ id: data.id || (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`), name: String(data.name || '').trim(), host: String(data.host || '').trim(), port: Number(data.port) || 22, username: String(data.username || '').trim(), authMethod: data.authMethod === 'key' ? 'key' : 'password', password: data.authMethod === 'password' ? String(data.password || '') : '', privateKey: data.authMethod === 'key' ? String(data.privateKey || '') : '', passphrase: data.authMethod === 'key' ? String(data.passphrase || '') : '' });

  Vue.createApp({
    data() {
      return {
        connection: null, socket: null, socketConnected: false, appZ: 21, terminalLine: '', reconnectTimer: null,
        screenSessions: [], screenWindows: {}, screenTimer: null,
        metricsTimer: null, suppressClose: false, terminal: null, terminalFit: null, terminalOpened: false,
        editor: null, editorLoading: null, editorPath: '', editorCleanValue: '', editorDirty: false, editorState: 'UTF-8',
        settings: { theme: 'system', reconnect: true, confirm: true, save: true, screenAutoExit: true, autoOpenTerminal: true, showWelcome: true }, font: 14,
        form: blankForm(), formError: '', connecting: false, testing: false, connections: [],
        windows: { welcome: { visible: true, z: 21, x: null, y: null, maximized: false }, connect: { visible: false, z: 20, x: null, y: null, maximized: false }, terminal: { visible: false, z: 20, x: null, y: null, maximized: false }, files: { visible: false, z: 20, x: null, y: null, maximized: false }, editor: { visible: false, z: 20, x: null, y: null, maximized: false }, monitor: { visible: false, z: 20, x: null, y: null, maximized: false }, history: { visible: false, z: 20, x: null, y: null, maximized: false }, settings: { visible: false, z: 20, x: null, y: null, maximized: false } },
        desktopApps: [
          { app: 'connect', label: '新建连接', icon: '#i-plus', title: '新建 SSH 连接' },
          { app: 'terminal', label: '终端', icon: '#i-terminal', title: '打开终端' },
          { app: 'files', label: '文件管理', icon: '#i-folder', title: '打开文件管理' },
          { app: 'monitor', label: '资源监控', icon: '#i-monitor', title: '打开系统监控' },
          { app: 'history', label: '会话记录', icon: '#i-history', title: '打开操作历史' },
          { app: 'settings', label: '设置', icon: '#i-settings', title: '打开设置' }
        ],
        startMenuOpen: false, contextMenu: { visible: false, x: 0, y: 0, items: [] },
        wallpaperUrl: 'https://www.loliapi.com/acg/', wallpaperFailed: false, clock: '--:--', serverTimeOffset: 0,
        path: '/', pathInput: '/', fileEntries: [], fileSelection: [], fileClipboard: null, fileLoading: false, fileMessage: '', uploadDirectory: '/',
        desktopEntries: [], loadedDesktopConnectionId: null, history: [], historyFilter: '', metrics: [], metricData: null, metricsTime: '等待数据',
        toasts: [], toastId: 0,
        fingerprint: { visible: false, message: '', code: '', resolve: null },
        dialog: { visible: false, type: '', title: '', kicker: '', description: '', value: '', submitLabel: '', icon: '#i-file', danger: false, error: '', resolve: null },
        progress: { visible: false, title: '', detail: '', percent: 0, processed: 0, total: 0, error: '', done: false },
        drag: null, iconDrag: null, iconPositions: {}, suppressIconClick: false
      };
    },
    computed: {
      connectionLabel() { return this.connection ? `${this.connection.username}@${this.connection.host}` : '未连接主机'; },
      resolvedTheme() { return this.settings.theme === 'system' ? (systemTheme.matches ? 'dark' : 'light') : this.settings.theme; },
      themeIcon() { return { system: '#i-theme-auto', light: '#i-sun', dark: '#i-moon' }[this.settings.theme] || '#i-theme-auto'; },
      themeTitle() { return `界面主题：${{ system: '自动', light: '浅色', dark: '暗色' }[this.settings.theme]}（点击切换）`; },
      editorName() { return this.editorPath ? this.editorPath.split('/').pop() : '未打开文件'; },
      parentPath() { return this.path === '/' ? '/' : this.path.slice(0, this.path.lastIndexOf('/')) || '/'; },
      sortedFiles() { return this.fileEntries.slice().sort((a, b) => a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'directory' ? -1 : 1); },
      isFileSelected() { return item => this.fileSelection.includes(this.filePath(item)); },
      allFilesSelected() { return Boolean(this.fileEntries.length && this.fileSelection.length === this.fileEntries.length); },
      someFilesSelected() { return this.fileSelection.length > 0 && !this.allFilesSelected; },
      fileCountLabel() { return this.fileSelection.length ? `${this.fileEntries.length} 个项目，已选择 ${this.fileSelection.length} 个` : `${this.fileEntries.length} 个项目`; },
      filteredHistory() { const filter = this.historyFilter.toLowerCase(); return this.history.filter(item => `${item.type} ${item.value}`.toLowerCase().includes(filter)).slice().reverse(); },
      metricCards() {
        const d = this.metricData;
        if (!d) return [];
        return [
          { title: '处理器', value: this.percent(d.cpu), icon: '#i-monitor', details: [['逻辑核心', `${Number(d.cpuCores) || '--'} 核`], ['型号', d.cpuModel || '--']] },
          { title: '内存', value: this.percent(d.memory), icon: '#i-server', details: [['已用', this.formatBytes(d.memoryUsed)], ['可用', this.formatBytes(d.memoryAvailable)], ['总计', this.formatBytes(d.memoryTotal)]] },
          { title: '交换分区', value: this.percent(d.swap), icon: '#i-history', details: [['已用', this.formatBytes(d.swapUsed)], ['总计', this.formatBytes(d.swapTotal)]] },
          { title: '根目录磁盘', value: this.percent(d.disk), icon: '#i-folder', details: [['已用', this.formatBytes(d.diskUsed)], ['可用', this.formatBytes(d.diskAvailable)], ['总计', this.formatBytes(d.diskTotal)]] }
        ];
      },
      systemFacts() {
        const d = this.metricData;
        return d ? [['主机名', d.hostname || '--'], ['系统', d.kernel || '--'], ['系统负载', d.load ?? '--'], ['运行时间', this.formatUptime(d.uptime)], ['进程数', d.processes ?? '--'], ['累计接收', this.formatBytes(d.download)], ['累计发送', this.formatBytes(d.upload)]] : [];
      },
      progressBytes() { return this.progress.total ? `${this.formatBytes(this.progress.processed)} / ${this.formatBytes(this.progress.total)}` : this.formatBytes(this.progress.processed); }
    },
    watch: {
      'settings.theme'() { this.applyTheme(); },
      'settings.save'(value) { if (!value) { this.connections.forEach(item => { item.password = ''; item.privateKey = ''; item.passphrase = ''; }); } },
       font() { if (this.terminal) this.terminal.options.fontSize = this.font; Object.values(this.screenWindows).forEach(win => { if (win.terminal) { win.terminal.options.fontSize = this.font; win.fit?.fit(); } }); const surface = document.querySelector('#terminal-surface'); if (surface) surface.style.setProperty('--terminal-size', `${this.font}px`); }
    },
    methods: {
      updateClock() { const now = this.connection && this.serverTimeOffset ? new Date(Date.now() + this.serverTimeOffset) : new Date(); this.clock = now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }); },
      async syncServerTime() { if (!this.connection) return; try { const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/time`); if (data.serverTime) this.serverTimeOffset = data.serverTime - Date.now(); } catch (_) {} },
      async api(url, options = {}) {
        try {
          const response = await axios({ url, method: options.method || 'GET', data: options.data, params: options.params, headers: options.headers, responseType: options.responseType, onUploadProgress: options.onUploadProgress });
          return response.data;
        } catch (error) {
          const payload = error.response?.data;
          const detail = payload?.error || {};
          const e = new Error(detail.message || error.message || `请求失败 (${error.response?.status || '网络错误'})`);
          e.status = error.response?.status; e.code = detail.code; e.data = payload; throw e;
        }
      },
      loadLocal() {
        try {
          const saved = JSON.parse(localStorage.getItem(configKey) || '{}');
          Object.assign(this.settings, saved);
          this.font = Number(saved.font || 14);
          this.connections = JSON.parse(localStorage.getItem(connectionsKey) || '[]').filter(safeConnection).map(normalizeConnection);
          if (!this.connections.length && saved.profile && safeConnection(saved.profile)) this.connections = [normalizeConnection(saved.profile)];
          this.history = JSON.parse(localStorage.getItem(historyKey) || '[]');
        } catch (_) {}
      },
      persist() { localStorage.setItem(configKey, JSON.stringify({ ...this.settings, font: this.font })); localStorage.setItem(connectionsKey, JSON.stringify(this.connections)); localStorage.setItem(historyKey, JSON.stringify(this.history.slice(-150))); },
      persistActive() { if (!this.connection) return sessionStorage.removeItem(activeConnectionKey); const { id, host, port, username, name } = this.connection; sessionStorage.setItem(activeConnectionKey, JSON.stringify({ id, host, port, username, name })); },
      applyTheme() {
        if (!['light', 'dark', 'system'].includes(this.settings.theme)) this.settings.theme = 'system';
        const theme = this.resolvedTheme;
        document.documentElement.dataset.theme = theme;
        document.documentElement.style.colorScheme = theme;
         if (this.terminal) { this.terminal.options.theme = terminalThemes[theme]; this.terminal.options.fontSize = this.font; }
         Object.values(this.screenWindows).forEach(win => { if (win.terminal) win.terminal.options.theme = terminalThemes[theme]; });
        this.editor?.setTheme(theme === 'dark' ? 'ace/theme/tomorrow_night_blue' : 'ace/theme/chrome');
        const toggle = document.querySelector('#theme-toggle');
        if (toggle) { toggle.setAttribute('aria-pressed', String(theme === 'dark')); }
      },
      settingsChanged() { this.applyTheme(); this.persist(); },
      saveSettingChanged() { this.persist(); },
      fontChanged() { this.persist(); },
      toggleTheme() { const themes = ['system', 'light', 'dark']; this.settings.theme = themes[(themes.indexOf(this.settings.theme) + 1) % themes.length]; this.settingsChanged(); },
      toast(message, type = '') { const item = { id: ++this.toastId, message, type }; this.toasts.push(item); setTimeout(() => { this.toasts = this.toasts.filter(value => value.id !== item.id); }, 3400); },
      profile() { return { ...this.form, port: Number(this.form.port), trustFingerprint: false }; },
      loadConnection(item) { Object.assign(this.form, blankForm(), item); this.formError = ''; this.toast((item.password || item.privateKey) ? '已加载连接配置和认证信息' : '已加载连接配置，请填写认证信息'); },
      removeConnection(item) { this.connections = this.connections.filter(saved => saved.id !== item.id); this.persist(); this.toast('连接配置已删除'); },
      saveConnection() {
        const data = this.profile();
        if (!data.host || !data.username || !data.port) return this.toast('请先填写主机、端口和用户名', 'error');
        const saved = normalizeConnection(data);
        if (!this.settings.save) { saved.password = ''; saved.privateKey = ''; saved.passphrase = ''; }
        const index = this.connections.findIndex(item => item.id === saved.id || (item.host === saved.host && item.port === saved.port && item.username === saved.username));
        if (index >= 0) { saved.id = this.connections[index].id; this.connections.splice(index, 1, saved); } else this.connections.unshift(saved);
        this.persist(); this.toast(this.settings.save ? '连接配置和认证信息已保存' : '连接配置已保存', 'success');
      },
      fingerprintConfirm(data) { return new Promise(resolve => { this.fingerprint.message = `服务器返回了一个新的主机身份。请确认它与可信来源一致后继续连接 ${data.host}:${data.port}。`; this.fingerprint.code = `${data.algorithm || 'ssh'}  ${data.value || 'fingerprint unavailable'}`; this.fingerprint.visible = true; this.fingerprint.resolve = resolve; }); },
      async resolveFingerprint(value) { const resolve = this.fingerprint.resolve; await this.animateOverlayClose('#modal-backdrop .modal', '#modal-backdrop'); this.fingerprint.visible = false; this.fingerprint.resolve = null; resolve?.(value); },
      async connect(isTest = false) {
        if (!this.form.host || !this.form.username || !this.form.port) { this.formError = '请完整填写连接信息'; return; }
        const data = this.profile(); this.formError = ''; this.connecting = true; this.testing = isTest;
        try {
          const tested = await this.api('/api/v1/connections/test', { method: 'POST', data });
          if (isTest) return this.toast(`认证成功，指纹为 ${tested.fingerprint?.value || '已验证'}`, 'success');
          if (!await this.fingerprintConfirm({ ...data, ...(tested.fingerprint || {}) })) return;
          data.trustFingerprint = true;
          const result = await this.api('/api/v1/connections', { method: 'POST', data });
          this.connection = { ...data, id: result.id }; this.persistActive(); this.suppressClose = false;
           this.windows.welcome.visible = false; if (this.settings.autoOpenTerminal) this.openApp('terminal'); this.addHistory('CONNECT', `${data.username}@${data.host}:${data.port}`); this.toast('SSH 连接已建立', 'success');
          this.syncServerTime();
        } catch (error) { this.formError = error.message; this.toast(error.message, 'error'); }
        finally { this.connecting = false; this.testing = false; }
      },
      async restoreConnection() {
        let saved;
        try { saved = JSON.parse(sessionStorage.getItem(activeConnectionKey) || 'null'); } catch (_) { sessionStorage.removeItem(activeConnectionKey); return; }
        if (!saved?.id || !saved.host || !saved.username) return;
        try { await this.api(`/api/v1/connections/${encodeURIComponent(saved.id)}/status`); this.connection = saved; this.suppressClose = false; this.windows.welcome.visible = false; this.toast('已恢复 SSH 连接', 'success'); this.syncServerTime(); }
        catch (_) { sessionStorage.removeItem(activeConnectionKey); }
      },
      wsUrl(id, screenName = '') { const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'; const host = ['localhost', '::1', '[::1]'].includes(location.hostname) ? '127.0.0.1' : location.hostname; const endpoint = screenName ? `screen/${encodeURIComponent(screenName)}` : 'terminal'; return `${scheme}//${host}:8081/api/v1/sessions/${encodeURIComponent(id)}/${endpoint}`; },
      connectTerminal() {
        if (!this.connection || !this.terminal) return;
        if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
        this.suppressClose = false; this.terminalLine = '';
        const previous = this.socket; this.socket = null; previous?.close();
        const socket = new WebSocket(this.wsUrl(this.connection.id)); this.socket = socket;
        socket.onopen = () => { if (this.socket !== socket) return; this.ensureTerminal(); this.terminal.write('\r\n[webssh] terminal channel opening...\r\n'); this.sendResize(); this.terminal.focus(); };
        socket.onmessage = event => { if (this.socket !== socket) return; try { const message = JSON.parse(event.data); if (message.type === 'output') this.terminal.write(message.data); else if (message.type === 'status') { this.setTerminalStatus(message.message === 'connected'); if (message.message === 'connected') this.terminal.focus(); } else if (message.type === 'error') this.toast(message.message, 'error'); } catch (_) {} };
        socket.onclose = () => { if (this.socket === socket) { this.socket = null; this.socketConnected = false; this.terminalLine = ''; this.setTerminalStatus(false); if (this.settings.reconnect && !this.suppressClose) this.reconnectTimer = setTimeout(() => this.connectTerminal(), 1800); } };
        socket.onerror = () => this.toast('终端 WebSocket 连接失败', 'error');
      },
      ensureTerminal() {
        if (!this.terminal) return;
        if (this.terminalOpened) { this.terminalFit?.fit(); return; }
        const surface = document.querySelector('#terminal-surface');
        if (!surface) return;
        const open = () => { try { this.terminal.open(surface); this.terminalOpened = true; this.terminal.onData(data => this.bufferTerminalInput(data)); this.terminalFit?.fit(); } catch (e) { this.terminalOpened = false; this.toast('终端初始化失败: ' + e.message, 'error'); } };
        if (surface.clientWidth > 4 && surface.clientHeight > 4) open(); else requestAnimationFrame(open);
      },
      setTerminalStatus(connected) {
        this.socketConnected = connected;
        const state = document.querySelector('#terminal-state');
        if (state) { state.classList.toggle('connected', connected); state.lastChild.textContent = connected ? ' 已连接' : ' 未连接'; }
        const overlay = document.querySelector('#terminal-overlay');
        if (overlay) overlay.style.display = (connected || this.connection) ? 'none' : '';
      },
      send(message) { if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message)); },
      bufferTerminalInput(data) { const input = data.replaceAll('\u001b[200~', '').replaceAll('\u001b[201~', ''); if (!input) return; this.send({ type: 'input', data: input }); if (input.startsWith('\u001b')) return; for (const character of input) { if (character === '\r' || character === '\n') { if (this.terminalLine) this.addHistory('COMMAND', this.terminalLine); this.terminalLine = ''; } else if (character === '\u007f' || character === '\b') this.terminalLine = Array.from(this.terminalLine).slice(0, -1).join(''); else if (character === '\u0003' || character === '\u0015') this.terminalLine = ''; else if (character >= ' ') this.terminalLine += character; } },
      sendResize() { if (!this.terminal || !this.terminalOpened) return; this.terminalFit?.fit(); const size = document.querySelector('#terminal-size'); if (size) size.textContent = `${this.terminal.cols} x ${this.terminal.rows}`; this.send({ type: 'resize', cols: this.terminal.cols, rows: this.terminal.rows }); },
      disconnectTerminal() { this.suppressClose = true; this.terminalLine = ''; clearTimeout(this.reconnectTimer); this.reconnectTimer = null; this.socket?.close(); this.socket = null; this.socketConnected = false; },
      async loadScreenSessions() { if (!this.connection) return; try { const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/screen-sessions`); const active = data.sessions || []; const names = new Set(active.map(item => item.name)); this.screenSessions = active; Object.entries(this.screenWindows).forEach(([name, win]) => { if (!names.has(name)) { win.socket?.close(); win.terminal?.dispose(); delete this.screenWindows[name]; } }); } catch (_) {} },
      async runRemoteEntry(entry) { const path = entry.type === 'shortcut' ? entry.target : entry.path; const type = entry.type === 'shortcut' ? entry.targetType : entry.type; if (type !== 'file') return this.toast('只能运行文件', 'error'); try { const session = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/screen-sessions`, { method: 'POST', data: { path, autoExit: this.settings.screenAutoExit } }); this.screenSessions = [...this.screenSessions.filter(item => item.name !== session.name), session]; this.addHistory('FILE RUN', path); this.openScreenSession(session); await this.loadScreenSessions(); this.toast(`已在 screen 中运行 ${session.label}`, 'success'); } catch (error) { this.toast(error.message, 'error'); } },
      async createScreenSession() { if (!this.connection) return this.toast('请先连接 SSH 主机', 'error'); const label = await this.openDialog({ title: '新建 Screen', description: '创建一个在后台持续运行的独立终端会话', value: 'Screen', submitLabel: '创建', icon: '#i-terminal' }); if (!label) return; try { const session = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/screen-sessions`, { method: 'POST', data: { label } }); this.screenSessions = [...this.screenSessions.filter(item => item.name !== session.name), session]; this.openScreenSession(session); await this.loadScreenSessions(); } catch (error) { this.toast(error.message, 'error'); } },
      screenWindow(session) { let win = this.screenWindows[session.name]; if (!win) { win = Vue.reactive({ session, visible: false, z: 20, x: null, y: null, maximized: false, terminal: null, fit: null, socket: null, connected: false }); this.screenWindows[session.name] = win; } else win.session = session; return win; },
      openScreenSession(session) { const win = this.screenWindow(session); win.visible = true; win.z = ++this.appZ; this.$nextTick(() => { this.initScreenTerminal(win); this.connectScreenTerminal(win); }); },
      initScreenTerminal(win) { if (win.terminal) { win.fit?.fit(); return; } const surface = document.getElementById(`screen-terminal-${win.session.name}`); if (!surface || !window.Terminal) return; win.terminal = Vue.markRaw(new window.Terminal({ convertEol: true, cursorBlink: true, fontFamily: 'Consolas, "SFMono-Regular", "Liberation Mono", monospace', fontSize: this.font, lineHeight: 1.35, scrollback: 5000, theme: terminalThemes[this.resolvedTheme] })); win.fit = window.FitAddon ? Vue.markRaw(new window.FitAddon.FitAddon()) : null; if (win.fit) win.terminal.loadAddon(win.fit); win.terminal.open(surface); win.terminal.onData(data => { if (win.socket?.readyState === WebSocket.OPEN) win.socket.send(JSON.stringify({ type: 'input', data })); }); win.fit?.fit(); },
      connectScreenTerminal(win) { if (!this.connection || !win.terminal || win.socket?.readyState === WebSocket.OPEN || win.socket?.readyState === WebSocket.CONNECTING) return; const socket = new WebSocket(this.wsUrl(this.connection.id, win.session.name)); win.socket = socket; socket.onopen = () => { if (win.socket !== socket) return; win.fit?.fit(); socket.send(JSON.stringify({ type: 'resize', cols: win.terminal.cols, rows: win.terminal.rows })); }; socket.onmessage = event => { if (win.socket !== socket) return; try { const message = JSON.parse(event.data); if (message.type === 'output') win.terminal.write(message.data); else if (message.type === 'status') { win.connected = message.message === 'connected'; if (win.connected) win.terminal.focus(); } else if (message.type === 'error') this.toast(message.message, 'error'); } catch (_) {} }; socket.onclose = () => { if (win.socket === socket) { win.socket = null; win.connected = false; } }; },
      activateScreenWindow(name) { const win = this.screenWindows[name]; if (win) win.z = ++this.appZ; },
      async minimizeScreenWindow(name) { const win = this.screenWindows[name]; if (!win) return; await this.animateWindowToDock(`[data-screen-name="${name}"]`, `[data-dock-screen="${name}"]`); win.visible = false; win.socket?.close(); win.socket = null; win.connected = false; },
      maximizeScreenWindow(name) { const win = this.screenWindows[name]; if (!win) return; win.maximized = !win.maximized; this.$nextTick(() => { win.fit?.fit(); if (win.socket?.readyState === WebSocket.OPEN) win.socket.send(JSON.stringify({ type: 'resize', cols: win.terminal.cols, rows: win.terminal.rows })); }); },
      screenWindowStyle(win) { if (win.maximized) return { zIndex: win.z, left: '0', top: '0', width: '100%', height: '100%' }; const slot = Math.max(0, this.screenSessions.findIndex(item => item.name === win.session.name)) % 8; const width = Math.min(900, Math.max(0, innerWidth - 24)), height = Math.min(620, Math.max(0, innerHeight - 86)); const left = Math.max(0, Math.min(24 + slot * 24, innerWidth - width - 12)), top = Math.max(0, Math.min(24 + slot * 24, innerHeight - height - 74)); return { zIndex: win.z, left: win.x == null ? `${left}px` : `${win.x}px`, top: win.y == null ? `${top}px` : `${win.y}px` }; },
      screenBadge(label) { return Array.from(String(label || 'Screen').trim())[0]?.toUpperCase() || 'S'; },
      startScreenWindowDrag(event, name) { const win = this.screenWindows[name]; if (!win || event.target.closest('button') || matchMedia('(max-width: 767px)').matches || win.maximized) return; const element = event.currentTarget.closest('.app-window'); this.drag = { screen: true, name, startX: event.clientX, startY: event.clientY, left: element.offsetLeft, top: element.offsetTop }; },
      async killScreenSession(session) { if (this.settings.confirm && !await this.confirmDialog(`退出 ${session.label}？`, '这会终止该 screen 中运行的程序和终端，会话无法恢复。', '退出会话', true)) return; try { await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/screen-sessions`, { method: 'POST', data: { action: 'kill', name: session.name } }); const win = this.screenWindows[session.name]; win?.socket?.close(); win?.terminal?.dispose(); delete this.screenWindows[session.name]; this.screenSessions = this.screenSessions.filter(item => item.name !== session.name); } catch (error) { this.toast(error.message, 'error'); } },
      screenDockMenu(event, session) { event.preventDefault(); event.stopPropagation(); const win = this.screenWindows[session.name]; this.showContext(event, [{ label: win?.visible ? '打开窗口' : '打开 / 恢复', icon: '#i-terminal', action: () => this.openScreenSession(session) }, { label: '最小化到后台', icon: '#i-min', disabled: !win?.visible, action: () => this.minimizeScreenWindow(session.name) }, { label: '新建 Screen', icon: '#i-plus', action: () => this.createScreenSession() }, { separator: true }, { label: '退出会话', icon: '#i-close', danger: true, action: () => this.killScreenSession(session) }]); },
      openMainTerminal() { this.openApp('terminal'); },
      toggleDockApp(name) { const win = this.windows[name]; if (!win) return; win.visible ? this.minimizeWindow(name) : (name === 'terminal' ? this.openMainTerminal() : this.openApp(name)); },
      toggleScreenWindow(session) { const win = this.screenWindows[session.name]; win?.visible ? this.minimizeScreenWindow(session.name) : this.openScreenSession(session); },
      async disconnect() { if (!this.connection) return; if (this.settings.confirm && !await this.confirmDialog('断开 SSH 连接？', `将断开 ${this.connectionLabel} 的终端和文件会话。`, '断开连接', true)) return; const old = this.connection; try { await this.api(`/api/v1/connections/${encodeURIComponent(old.id)}`, { method: 'DELETE' }); } catch (_) {} this.disconnectTerminal(); Object.values(this.screenWindows).forEach(win => { win.socket?.close(); win.terminal?.dispose(); }); this.screenWindows = {}; this.stopMetrics(); this.addHistory('DISCONNECT', `${old.username}@${old.host}`); this.connection = null; this.screenSessions = []; this.serverTimeOffset = 0; this.persistActive(); this.desktopEntries = []; this.fileEntries = []; this.toast('连接已断开'); },
      async rebootServer() { if (!this.connection) return this.toast('请先连接 SSH 主机', 'error'); if (!await this.confirmDialog('重启远程服务器？', `将立即重启 ${this.connectionLabel}，所有 SSH 和 Screen 连接都会中断。`, '立即重启', true)) return; const old = this.connection; try { await this.api(`/api/v1/connections/${encodeURIComponent(old.id)}/power`, { method: 'POST', data: { action: 'reboot' } }); this.addHistory('REBOOT', `${old.username}@${old.host}`); this.startMenuOpen = false; this.disconnectTerminal(); Object.values(this.screenWindows).forEach(win => { win.socket?.close(); win.terminal?.dispose(); }); this.screenWindows = {}; this.stopMetrics(); this.connection = null; this.screenSessions = []; this.desktopEntries = []; this.fileEntries = []; this.persistActive(); this.toast('服务器重启命令已发送', 'success'); } catch (error) { this.toast(error.message, 'error'); } },
      focusTerminal() { if (this.terminalOpened) this.terminal.focus(); },
      clearTerminal() { this.terminal?.clear(); },
      async copyTerminal() { const selected = this.terminal?.getSelection(); selected ? await this.copyText(selected) : this.toast('请先选中终端文字', 'error'); },
      changeFont(delta) { this.font = Math.max(11, Math.min(22, this.font + delta)); if (this.terminal) { this.terminal.options.fontSize = this.font; this.terminalFit?.fit(); this.sendResize(); } this.persist(); },
      initTerminal() {
        if (!window.Terminal) return this.toast('终端组件加载失败，请检查网络连接', 'error');
        this.terminal = new window.Terminal({ convertEol: true, cursorBlink: true, fontFamily: 'Consolas, "SFMono-Regular", "Liberation Mono", monospace', fontSize: this.font, lineHeight: 1.35, scrollback: 5000, theme: terminalThemes[this.resolvedTheme] });
        this.terminalFit = window.FitAddon ? new window.FitAddon.FitAddon() : null;
        if (this.terminalFit) this.terminal.loadAddon(this.terminalFit);
        this.terminal.attachCustomKeyEventHandler(event => {
          if (event.type !== 'keydown' || !(event.ctrlKey || event.metaKey) || event.altKey) return true;
          const key = event.key.toLowerCase();
          if (key === 'c') { if (this.terminal.hasSelection()) { this.copyText(this.terminal.getSelection()).then(() => this.terminal.clearSelection()); return false; } else { this.send({ type: 'input', data: '\u0003' }); return false; } }
          if (key === 'v') { this.send({ type: 'input', data: '\u0016' }); return false; }
          return true;
        });
        const overlayBtn = document.querySelector('#terminal-overlay button');
        if (overlayBtn) overlayBtn.addEventListener('click', () => this.openApp('connect'));
      },
      loadScript(src) { return new Promise((resolve, reject) => { const script = document.createElement('script'); script.src = src; script.onload = resolve; script.onerror = () => reject(new Error('组件下载失败')); document.head.appendChild(script); }); },
      async ensureEditor() {
        if (this.editor) return true;
        if (!this.editorLoading) this.editorLoading = (async () => {
          const base = 'https://cdn.jsdelivr.net/npm/ace-builds@1.43.3/src-min-noconflict';
          if (!window.ace) await this.loadScript(`${base}/ace.js`);
          await this.loadScript(`${base}/ext-modelist.js`);
          this.initEditor();
        })().catch(error => { this.editorState = '编辑器组件加载失败'; this.editorLoading = null; throw error; });
        try { await this.editorLoading; return Boolean(this.editor); } catch (error) { this.toast(`文本编辑器加载失败：${error.message}`, 'error'); return false; }
      },
      initEditor() {
        if (!window.ace || this.editor) return;
        window.ace.config.set('basePath', 'https://cdn.jsdelivr.net/npm/ace-builds@1.43.3/src-min-noconflict');
        this.editor = window.ace.edit('text-editor', { fontSize: 14, showPrintMargin: false, tabSize: 2, useSoftTabs: true, wrap: false });
        this.editor.setTheme(this.resolvedTheme === 'dark' ? 'ace/theme/tomorrow_night_blue' : 'ace/theme/chrome');
        this.editor.session.on('change', () => { this.editorDirty = Boolean(this.editorPath && this.editor.getValue() !== this.editorCleanValue); this.editorState = this.editorDirty ? '未保存 · UTF-8' : '已保存 · UTF-8'; });
        this.editor.commands.addCommand({ name: 'saveRemoteFile', bindKey: { win: 'Ctrl-S', mac: 'Command-S' }, exec: () => this.saveTextFile() });
      },
      async openTextFile(path, discard = false) {
        if (!this.connection || !await this.ensureEditor()) return;
        if (!discard && this.editorDirty && !await this.confirmDialog('打开其他文件？', '当前文件尚未保存，继续后未保存的修改将丢失。', '放弃并打开', true)) return;
        this.editorState = '加载中...';
        try { const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/files/content`, { params: { path } }); this.editorPath = data.path; this.editorCleanValue = data.content; this.editor.setValue(data.content, -1); this.editor.session.setMode(window.ace.require('ace/ext/modelist').getModeForPath(path).mode); this.editorDirty = false; this.editorState = '已保存 · UTF-8'; this.openApp('editor'); this.addHistory('FILE OPEN', path); }
        catch (error) { this.editorState = 'UTF-8'; this.toast(error.message, 'error'); }
      },
      async saveTextFile() {
        if (!this.connection || !this.editorPath || !this.editorDirty) return;
        this.editorState = '保存中...';
        try { await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/files/operation`, { method: 'POST', data: { action: 'write', path: this.editorPath, content: this.editor.getValue() } }); this.editorCleanValue = this.editor.getValue(); this.editorDirty = false; this.editorState = '已保存 · UTF-8'; this.addHistory('FILE WRITE', this.editorPath); this.toast('文件已保存', 'success'); this.loadFiles(this.path); }
        catch (error) { this.editorState = '未保存 · UTF-8'; this.toast(error.message, 'error'); }
      },
      async reloadEditor() { if (!this.editorPath) return; if (this.editorDirty && !await this.confirmDialog('重新加载文件？', '未保存的修改将丢失。', '放弃并重新加载', true)) return; this.openTextFile(this.editorPath, true); },
      joinPath(a, b) { return `${a === '/' ? '' : a.replace(/\/$/, '')}/${b}` || '/'; },
      filePath(item) { return this.joinPath(this.path, item.name); },
      fileEntry(item) { return { ...item, path: this.filePath(item), fileName: item.name }; },
      isExecutableEntry(entry) { const type = entry.type === 'shortcut' ? entry.targetType : entry.type; return type === 'file' && (entry.path?.toLowerCase().endsWith('.sh') || /x/.test(entry.permissions || '') || (parseInt(entry.mode || '0', 8) & 0o111) !== 0); },
      openFileEntry(item) { const entry = this.fileEntry(item); item.type === 'directory' ? this.loadFiles(entry.path) : entry.path.toLowerCase().endsWith('.sh') ? this.runRemoteEntry(entry) : this.openTextFile(entry.path); },
      async loadFiles(path = this.path) {
        if (!this.connection) return; this.fileLoading = true; this.fileMessage = '';
        try { const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/files`, { params: { path } }); const oldPath = this.path; this.path = data.path || path; this.pathInput = this.path; this.fileEntries = Array.isArray(data.entries) ? data.entries : []; if (oldPath !== this.path) this.fileSelection = []; const available = new Set(this.fileEntries.map(item => this.filePath(item))); this.fileSelection = this.fileSelection.filter(item => available.has(item)); this.fileMessage = this.fileEntries.length ? '' : '目录为空'; }
        catch (error) { this.fileMessage = error.message; this.toast(error.message, 'error'); }
        finally { this.fileLoading = false; }
      },
      selectFile(item, checked) { const path = this.filePath(item); this.fileSelection = checked ? [...new Set([...this.fileSelection, path])] : this.fileSelection.filter(value => value !== path); },
      selectAllFiles(checked) { this.fileSelection = checked ? this.fileEntries.map(item => this.filePath(item)) : []; },
      selectedEntries(fallback) { const selected = this.fileEntries.filter(item => this.fileSelection.includes(this.filePath(item))).map(item => this.fileEntry(item)); return selected.length ? selected : fallback ? [fallback] : []; },
      async runFileOperation(value, reload = true) { const result = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/files/operation`, { method: 'POST', data: value }); this.addHistory(`FILE ${value.action.toUpperCase()}`, value.path); if (reload) await this.loadFiles(this.path); return result; },
      async createEntry(directory, action) { if (!this.connection) return; const folder = action === 'mkdir'; const name = await this.openDialog({ title: folder ? '新建文件夹' : '新建文件', description: `创建位置：${directory}`, submitLabel: '创建', icon: folder ? icons.folder : icons.file }); if (!name) return; try { await this.runFileOperation({ action, path: this.joinPath(directory, name) }, false); await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); this.toast(folder ? '文件夹已创建' : '文件已创建', 'success'); } catch (error) { this.toast(error.message, 'error'); } },
      async renameEntry(entry) { const name = await this.openDialog({ title: '重命名', description: entry.path, value: entry.name, submitLabel: '重命名', icon: entry.type === 'directory' ? icons.folder : icons.file }); if (!name || name === entry.name) return; const parent = entry.path.slice(0, entry.path.lastIndexOf('/')) || '/'; try { await this.runFileOperation({ action: 'rename', path: entry.path, destination: this.joinPath(parent, name) }, false); await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); this.toast('重命名完成', 'success'); } catch (error) { this.toast(error.message, 'error'); } },
      async changePermissions(entries) { entries = Array.isArray(entries) ? entries : [entries]; const mode = await this.openDialog({ type: 'permissions', title: entries.length > 1 ? `设置 ${entries.length} 个项目的权限` : `"${entries[0].name}"的权限`, description: entries.length > 1 ? '所选项目将统一应用相同权限' : entries[0].path, value: entries[0].mode || (entries[0].type === 'directory' ? '0755' : '0644'), submitLabel: '应用权限' }); if (!mode) return; let completed = 0; for (const entry of entries) try { await this.runFileOperation({ action: 'chmod', path: entry.path, mode }, false); completed++; } catch (error) { this.toast(`${entry.name}：${error.message}`, 'error'); } await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); if (completed) this.toast(`已更新 ${completed} 个项目的权限`, 'success'); },
      async deleteEntries(entries) { entries = Array.isArray(entries) ? entries : [entries]; if (this.settings.confirm && !await this.confirmDialog(entries.length > 1 ? `删除 ${entries.length} 个项目？` : `删除"${entries[0].name}"？`, '删除后无法恢复，请确认是否继续。', '删除', true)) return; let completed = 0; for (const entry of entries) try { await this.runFileOperation({ action: 'delete', path: entry.path }, false); completed++; } catch (error) { this.toast(`${entry.name}：${error.message}`, 'error'); } await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); if (completed) this.toast(`已删除 ${completed} 个项目`, 'success'); },
      async waitForFileJob(jobId, title) { this.showProgress(title, '正在准备文件...', 0); while (true) { await new Promise(resolve => setTimeout(resolve, 400)); const job = await this.api(`/api/v1/file-jobs/${encodeURIComponent(jobId)}`); this.showProgress(title, job.status === 'queued' ? '正在排队...' : '正在处理远程文件...', job.percent, job.processed, job.total); if (job.status === 'completed') { this.finishProgress(); return; } if (job.status === 'failed') { const message = job.error || '文件任务失败'; this.finishProgress(message); throw new Error(message); } } },
      async compressEntries(entries) { const parent = entries[0].path.slice(0, entries[0].path.lastIndexOf('/')) || '/'; const initial = entries.length === 1 ? `${entries[0].name.replace(/\.zip$/i, '')}.zip` : 'archive.zip'; let name = await this.openDialog({ title: `压缩 ${entries.length} 个项目`, description: `保存位置：${parent}`, value: initial, submitLabel: '开始压缩', icon: '#i-download' }); if (!name) return; if (!name.toLowerCase().endsWith('.zip')) name += '.zip'; try { const job = await this.runFileOperation({ action: 'compress', path: entries[0].path, paths: entries.map(entry => entry.type === 'shortcut' ? entry.target : entry.path), destination: this.joinPath(parent, name) }, false); await this.waitForFileJob(job.jobId, '正在压缩'); await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); this.toast('压缩完成', 'success'); } catch (error) { if (!this.progress.visible) this.toast(error.message, 'error'); } },
      async extractEntry(entry) { const source = entry.type === 'shortcut' ? entry.target : entry.path; const parent = source.slice(0, source.lastIndexOf('/')) || '/'; const base = (source.split('/').pop() || '').replace(/\.zip$/i, ''); const initial = base ? `${base}-解压` : '解压内容'; const name = await this.openDialog({ title: '解压 ZIP 文件', description: source, value: initial, submitLabel: '开始解压', icon: icons.folder }); if (!name) return; try { const job = await this.runFileOperation({ action: 'extract', path: source, destination: this.joinPath(parent, name) }, false); await this.waitForFileJob(job.jobId, '正在解压'); await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); this.toast('解压完成', 'success'); } catch (error) { if (!this.progress.visible) this.toast(error.message, 'error'); } },
      setFileClipboard(entries, mode) { entries = Array.isArray(entries) ? entries : [entries]; this.fileClipboard = { connectionId: this.connection.id, mode, entries: entries.map(entry => ({ path: entry.path, name: entry.name, type: entry.type })) }; this.toast(`${entries.length} 个项目已${mode === 'copy' ? '复制' : '剪切'}到文件剪贴板`); },
      clipboardAvailable() { return Boolean(this.connection && this.fileClipboard?.connectionId === this.connection.id && this.fileClipboard.entries.length); },
      async pasteFiles(directory) { if (!this.clipboardAvailable()) return; const clip = this.fileClipboard; const failed = []; let completed = 0; for (const entry of clip.entries) { let destination = this.joinPath(directory, entry.name); if (destination === entry.path) destination = this.joinPath(directory, `${entry.name} - 副本`); try { await this.runFileOperation({ action: clip.mode === 'cut' ? 'move' : 'copy', path: entry.path, destination }, false); completed++; } catch (error) { failed.push(entry); this.toast(`${entry.name}：${error.message}`, 'error'); } } if (clip.mode === 'cut') this.fileClipboard = failed.length ? { ...clip, entries: failed } : null; await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); if (completed) this.toast(`已粘贴 ${completed} 个项目`, 'success'); },
      chooseUpload(directory) { if (!this.connection) return; this.uploadDirectory = directory; const input = document.querySelector('#file-upload-input'); input.value = ''; input.click(); },
      async uploadFiles(fileList) { const files = [...fileList]; if (!this.connection || !files.length) return; const total = files.reduce((sum, file) => sum + file.size, 0); let loaded = 0, completed = 0; this.showProgress('正在上传', '正在准备文件...', 0, 0, total); for (const file of files) { const form = new FormData(); form.append('file', file, file.name); try { const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/files/upload`, { method: 'POST', params: { path: this.joinPath(this.uploadDirectory, file.name) }, data: form, onUploadProgress: event => { const current = loaded + (event.loaded || 0); this.showProgress('正在上传', file.name, total ? current * 100 / total : 0, current, total); } }); loaded += file.size; completed++; this.addHistory('UPLOAD', data.path || this.joinPath(this.uploadDirectory, file.name)); } catch (error) { loaded += file.size; this.toast(`${file.name}：${error.message}`, 'error'); } } await Promise.all([this.loadFiles(this.path), this.loadDesktopEntries()]); this.finishProgress(completed === files.length ? '' : `${files.length - completed} 个文件上传失败`); if (completed) this.toast(`已上传 ${completed} 个文件`, 'success'); },
      downloadFile(path) { if (!this.connection) return; this.addHistory('DOWNLOAD', path); const a = document.createElement('a'); a.href = `/api/v1/connections/${this.connection.id}/files/download?path=${encodeURIComponent(path)}`; a.download = ''; a.click(); },
      downloadEntries(entries) { entries.forEach((entry, index) => setTimeout(() => this.downloadFile(entry.type === 'shortcut' ? entry.target : entry.path), index * 350)); },
      async loadDesktopEntries() { if (!this.connection) { this.desktopEntries = []; return; } const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/desktop`); const entries = data.entries || []; this.placeNewDesktopIcons(entries); this.desktopEntries = entries; this.$nextTick(() => this.normalizeDesktopIcons()); },
      entryIcon(entry) { const type = entry.type === 'shortcut' ? entry.targetType : entry.type; return type === 'directory' ? icons.folder : icons.file; },
      openRemoteEntry(entry) { const path = entry.type === 'shortcut' ? entry.target : entry.path; const type = entry.type === 'shortcut' ? entry.targetType : entry.type; type === 'directory' ? (this.path = path, this.openApp('files')) : path.toLowerCase().endsWith('.sh') ? this.runRemoteEntry(entry) : this.openTextFile(path); },
      async addDesktopShortcut(entry) { try { await this.runFileOperation({ action: 'desktop-shortcut', path: entry.path, type: entry.type, name: entry.name }, false); await this.loadDesktopEntries(); this.toast('已在桌面创建快捷方式', 'success'); } catch (error) { this.toast(error.message, 'error'); } },
      entryMenu(entry, desktop = false) { const entries = desktop ? [entry] : this.selectedEntries(entry); const multiple = entries.length > 1; const targetType = entry.type === 'shortcut' ? entry.targetType : entry.type; const targetName = entry.type === 'shortcut' ? entry.target : entry.name; const executable = !multiple && this.isExecutableEntry(entry); const zip = !multiple && targetType !== 'directory' && targetName?.toLowerCase().endsWith('.zip'); const entryPath = entry.type === 'shortcut' ? entry.target : entry.path; const uploadPath = entryPath.slice(0, entryPath.lastIndexOf('/')) || '/'; const primary = targetType === 'directory' ? [{ label: '打开', icon: icons.folder, action: () => this.openRemoteEntry(entry) }] : executable ? [{ label: '使用 screen 运行', icon: '#i-terminal', action: () => this.runRemoteEntry(entry) }, { label: '编辑', icon: icons.file, action: () => this.openTextFile(entryPath) }] : [{ label: multiple ? `编辑（仅 ${entry.name}）` : '编辑', icon: icons.file, action: () => this.openTextFile(entryPath) }, ...(!multiple ? [{ label: '使用 screen 运行', icon: '#i-terminal', action: () => this.runRemoteEntry(entry) }] : [])]; return [...primary, { label: '上传到所在文件夹', icon: '#i-up', disabled: !this.connection, action: () => this.chooseUpload(uploadPath) }, { label: multiple ? `下载 ${entries.length} 个项目` : '下载', icon: icons.download, action: () => this.downloadEntries(entries) }, { label: multiple ? `压缩 ${entries.length} 个项目` : '压缩为 ZIP', icon: '#i-download', action: () => this.compressEntries(entries) }, ...(zip ? [{ label: '解压到文件夹', icon: icons.folder, action: () => this.extractEntry(entry) }] : []), { separator: true }, { label: multiple ? `复制 ${entries.length} 个项目` : '复制', icon: icons.copy, action: () => this.setFileClipboard(entries, 'copy') }, { label: multiple ? `剪切 ${entries.length} 个项目` : '剪切', icon: '#i-up', action: () => this.setFileClipboard(entries, 'cut') }, { label: '重命名', icon: icons.file, disabled: multiple, action: () => this.renameEntry(entry) }, { separator: true }, { label: multiple ? `权限管理（${entries.length} 项）` : '权限管理', icon: icons.settings, action: () => this.changePermissions(entries) }, ...(!desktop ? [{ label: multiple ? `发送 ${entries.length} 项到桌面` : '发送到桌面', icon: '#i-monitor', action: () => Promise.all(entries.map(item => this.addDesktopShortcut(item))) }] : []), { separator: true }, { label: multiple ? `删除 ${entries.length} 个项目` : '删除', icon: icons.trash, danger: true, action: () => this.deleteEntries(entries) }]; },
      backgroundMenu(directory, desktop = false) { return [{ label: '上传文件', icon: '#i-up', disabled: !this.connection, action: () => this.chooseUpload(directory) }, { label: '粘贴', icon: icons.copy, disabled: !this.clipboardAvailable(), action: () => this.pasteFiles(directory) }, { label: '刷新', icon: icons.refresh, action: () => desktop ? this.loadDesktopEntries() : this.loadFiles(this.path) }, ...(desktop ? [{ label: '自动排列', icon: '#i-grid', action: () => this.autoArrangeDesktop() }] : []), { separator: true }, { label: '新建文件', icon: icons.file, disabled: !this.connection, action: () => this.createEntry(directory, 'create') }, { label: '新建文件夹', icon: icons.folder, disabled: !this.connection, action: () => this.createEntry(directory, 'mkdir') }]; },
      openDesktopContext(event) { if (event.target.closest('.app-window,.start-menu,.dock,.topbar,.context-menu')) return; const icon = event.target.closest('.desktop-icon'); if (icon) { const desktopPath = icon.dataset.desktopPath || icon.getAttribute('data-desktop-path'); if (desktopPath) { const entry = this.desktopEntries.find(item => item.path === desktopPath); if (entry) { event.preventDefault(); this.showContext(event, this.entryMenu(entry, true)); return; } } } event.preventDefault(); this.showContext(event, this.backgroundMenu(desktopDirectory, true)); },
      openEntryContext(event, entry, desktop = false) { if (!desktop && !this.fileSelection.includes(entry.path)) this.fileSelection = [entry.path]; event.preventDefault(); this.showContext(event, this.entryMenu(entry, desktop)); },
      openFileBackgroundContext(event) { if (event.target.closest('tr')) return; event.preventDefault(); this.showContext(event, this.backgroundMenu(this.path)); },
      showContext(event, items) { const width = 220, height = Math.max(80, items.length * 36); this.contextMenu = { visible: true, x: Math.max(8, Math.min(event.clientX, window.innerWidth - width - 8)), y: Math.max(8, Math.min(event.clientY, window.innerHeight - height - 8)), items }; },
      runMenuItem(item) { if (item.disabled) return; this.contextMenu.visible = false; item.action?.(); },
      dismissMenus(event) { if (!event.target.closest('.context-menu')) this.contextMenu.visible = false; if (!event.target.closest('.start-menu') && !event.target.closest('#brand-button') && !event.target.closest('#dock-launcher')) this.startMenuOpen = false; },
      handleEscape() { this.closeFloatingMenus(); if (this.dialog.visible) this.resolveDialog(null); if (this.fingerprint.visible) this.resolveFingerprint(false); if (this.progress.visible && this.progress.done) this.closeProgress(); },
      openDialog(options) { return new Promise(resolve => { Object.assign(this.dialog, { visible: true, type: options.type || 'input', title: options.title, kicker: options.type === 'confirm' ? '操作确认' : options.type === 'permissions' ? '权限管理' : '文件操作', description: options.description || '', value: options.value || '', submitLabel: options.submitLabel || '确定', icon: options.icon || icons.file, danger: Boolean(options.danger), error: '', resolve }); }); },
      confirmDialog(title, description, submitLabel = '继续', danger = false) { return this.openDialog({ type: 'confirm', title, description, submitLabel, danger, icon: danger ? icons.trash : icons.settings }); },
      submitDialog() { if (this.dialog.type === 'confirm') return this.resolveDialog(true); const value = this.dialog.value.trim(); if (!value) { this.dialog.error = '请输入有效内容'; return; } if (this.dialog.type === 'permissions' && !/^[0-7]{3,4}$/.test(value)) { this.dialog.error = '请输入 3 或 4 位八进制权限，例如 644 或 0755'; return; } if (this.dialog.type !== 'permissions' && (value.includes('/') || value === '.' || value === '..')) { this.dialog.error = '名称不能包含 /，也不能是 . 或 ..'; return; } this.resolveDialog(value); },
      async resolveDialog(value) { const resolve = this.dialog.resolve; await this.animateOverlayClose('#file-dialog', '#file-dialog-backdrop'); this.dialog.visible = false; this.dialog.resolve = null; resolve?.(value); },
      syncPermissionMode() { this.$nextTick(() => { const mode = [...document.querySelectorAll('[data-permission]')].reduce((m, input) => m + (input.checked ? parseInt(input.dataset.permission, 8) : 0), 0).toString(8).padStart(3, '0'); this.dialog.value = mode; }); },
      syncPermissionChecks() { const mode = this.dialog.value.trim(); if (/^[0-7]{1,4}$/.test(mode)) { const value = parseInt(mode, 8); document.querySelectorAll('[data-permission]').forEach(input => { const bit = parseInt(input.dataset.permission, 8); input.checked = Number.isFinite(value) && (value & bit) === bit; }); } },
      showProgress(title, detail, percent = 0, processed = 0, total = 0) { Object.assign(this.progress, { visible: true, title, detail, percent: Math.max(0, Math.min(100, percent)), processed, total, error: '', done: false }); },
      finishProgress(error = '') { Object.assign(this.progress, { percent: error ? this.progress.percent : 100, detail: error ? this.progress.detail : '操作已完成', error, done: true }); },
      async closeProgress() { if (!this.progress.done) return; await this.animateOverlayClose('#progress-dialog-backdrop .progress-dialog', '#progress-dialog-backdrop'); this.progress.visible = false; },
      async loadMetrics() { if (!this.connection) return; try { const data = await this.api(`/api/v1/connections/${encodeURIComponent(this.connection.id)}/metrics`); this.metricData = data; this.metrics.push({ cpu: Number(data.cpu || 0), memory: Number(data.memory || 0) }); this.metrics = this.metrics.slice(-20); this.metricsTime = new Date(data.collectedAt || Date.now()).toLocaleTimeString(); } catch (error) { this.metricsTime = '错误'; this.toast(error.message, 'error'); } },
      startMetrics() { this.stopMetrics(); const run = async () => { if (!this.connection || !this.windows.monitor.visible) return; await this.loadMetrics(); if (this.windows.monitor.visible) this.metricsTimer = setTimeout(run, 1000); }; run(); },
      stopMetrics() { clearTimeout(this.metricsTimer); this.metricsTimer = null; },
      trendPoints(key) { return this.metrics.map((item, index) => `${this.metrics.length < 2 ? 0 : index * 700 / (this.metrics.length - 1)},${220 - Math.min(100, item[key]) * 1.9}`).join(' '); },
      percent(value) { return Math.min(100, Math.max(0, Number(value || 0))); },
      addHistory(type, value) { this.history.push({ time: new Date().toISOString(), type, value }); this.persist(); },
      historyType(type) { return { CONNECT: '连接', DISCONNECT: '断开', REBOOT: '重启服务器', COMMAND: '命令', DOWNLOAD: '下载', UPLOAD: '上传', 'FILE OPEN': '打开文件', 'FILE RUN': '运行文件', 'FILE WRITE': '保存文件', 'FILE MKDIR': '新建目录', 'FILE CREATE': '新建文件', 'FILE RENAME': '重命名', 'FILE COPY': '复制', 'FILE MOVE': '移动', 'FILE DELETE': '删除' }[type] || type; },
      async copyHistory() { const text = this.filteredHistory.map(item => `${this.formatDateTime(item.time)}\t${this.historyType(item.type)}\t${item.value}`).join('\n'); await this.copyText(text); this.toast('历史已复制', 'success'); },
      exportHistory() { const rows = [['time', 'type', 'value'], ...this.filteredHistory.map(item => [item.time, item.type, item.value])]; const csv = rows.map(row => row.map(value => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n'); const blob = new Blob(['\ufeff', csv], { type: 'text/csv;charset=utf-8' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `webssh-history-${Date.now()}.csv`; a.click(); URL.revokeObjectURL(a.href); },
      async clearHistory() { if (this.settings.confirm && !await this.confirmDialog('清空操作历史？', '此操作无法撤销。', '清空', true)) return; this.history = []; this.persist(); },
      async copyText(text) { try { await navigator.clipboard.writeText(text); } catch (_) { const helper = document.createElement('textarea'); helper.value = text; helper.style.position = 'fixed'; helper.style.opacity = '0'; document.body.appendChild(helper); helper.select(); document.execCommand('copy'); helper.remove(); } },
      openApp(name) { const win = this.windows[name]; if (!win) return; win.visible = true; this.activateWindow(name); this.startMenuOpen = false; if (name === 'files') this.loadFiles(); if (name === 'monitor') this.startMetrics(); if (name === 'terminal') this.$nextTick(() => { if (this.connection && !this.socket) this.connectTerminal(); else { this.ensureTerminal(); this.sendResize(); } }); if (name === 'editor') this.ensureEditor().then(() => this.$nextTick(() => { this.editor?.resize(); this.editor?.focus(); })); },
      async closeApp(name) { if (name === 'editor' && this.editorDirty && !await this.confirmDialog('关闭未保存的文件？', '当前修改尚未保存，关闭后将丢失。', '放弃并关闭', true)) return; await this.animateWindowClose(`[data-app="${name}"]`); if (name === 'terminal') this.disconnectTerminal(); if (name === 'monitor') this.stopMetrics(); this.windows[name].visible = false; if (name === 'welcome' && !this.connection) this.openApp('connect'); },
      async minimizeWindow(name) { if (name === 'monitor') this.stopMetrics(); await this.animateWindowToDock(`[data-app="${name}"]`, `[data-dock-app="${name}"]`); this.windows[name].visible = false; },
      maximizeWindow(name) { this.windows[name].maximized = !this.windows[name].maximized; if (name === 'editor') this.$nextTick(() => this.editor?.resize()); if (name === 'terminal') this.$nextTick(() => this.sendResize()); },
      activateWindow(name) { this.appZ++; this.windows[name].z = this.appZ; },
      async animateOverlayClose(contentSelector, backdropSelector) { if (matchMedia('(prefers-reduced-motion: reduce)').matches) return; const content = document.querySelector(contentSelector), backdrop = document.querySelector(backdropSelector); const animations = []; if (content?.animate) animations.push(content.animate([{ transform: 'translate3d(0,0,0) scale(1)', opacity: 1, filter: 'blur(0)' }, { transform: 'translate3d(0,4px,0) scale(1.015)', opacity: .96, offset: .25 }, { transform: 'translate3d(0,18px,0) scale(.88)', opacity: 0, filter: 'blur(7px)' }], { duration: 240, easing: 'cubic-bezier(.4,0,1,1)' }).finished); if (backdrop?.animate) animations.push(backdrop.animate([{ opacity: 1, backdropFilter: 'blur(9px)' }, { opacity: 0, backdropFilter: 'blur(0)' }], { duration: 240, easing: 'ease-in' }).finished); try { await Promise.all(animations); } catch (_) {} },
      async closeFloatingMenus() { const animations = []; const context = document.querySelector('#context-menu'), start = document.querySelector('#start-menu'); if (this.contextMenu.visible && context?.animate) animations.push(context.animate([{ opacity: 1, transform: 'scale(1)' }, { opacity: 0, transform: 'translateY(5px) scale(.94)' }], { duration: 150, easing: 'ease-in' }).finished); if (this.startMenuOpen && start?.animate) animations.push(start.animate([{ opacity: 1, transform: 'scale(1)' }, { opacity: 0, transform: 'translateY(10px) scale(.95)' }], { duration: 170, easing: 'ease-in' }).finished); try { await Promise.all(animations); } catch (_) {} this.contextMenu.visible = false; this.startMenuOpen = false; },
      async animateWindowClose(windowSelector) { if (matchMedia('(prefers-reduced-motion: reduce)').matches) return; const element = document.querySelector(windowSelector); if (!element || typeof element.animate !== 'function') return; const animation = element.animate([{ transform: 'translate3d(0,0,0) scale(1)', opacity: 1, filter: 'blur(0)' }, { transform: 'translate3d(0,5px,0) scale(1.015)', opacity: .96, offset: .25 }, { transform: 'translate3d(0,22px,0) scale(.82)', opacity: 0, filter: 'blur(9px)' }], { duration: 300, easing: 'cubic-bezier(.4,0,1,1)', fill: 'none' }); try { await animation.finished; } catch (_) {} },
      async animateWindowToDock(windowSelector, dockSelector) { if (matchMedia('(prefers-reduced-motion: reduce)').matches) return; const element = document.querySelector(windowSelector), target = document.querySelector(dockSelector); if (!element || !target || typeof element.animate !== 'function') return; const from = element.getBoundingClientRect(), to = target.getBoundingClientRect(); const dx = to.left + to.width / 2 - (from.left + from.width / 2), dy = to.top + to.height / 2 - (from.top + from.height / 2); const animation = element.animate([{ transform: 'translate3d(0,0,0) scale(1)', opacity: 1, filter: 'blur(0)' }, { transform: `translate3d(${dx * .18}px,${dy * .18}px,0) scale(.94)`, opacity: .94, offset: .45 }, { transform: `translate3d(${dx}px,${dy}px,0) scale(.08)`, opacity: 0, filter: 'blur(8px)' }], { duration: 430, easing: 'cubic-bezier(.32,.72,0,1)', fill: 'none' }); try { await animation.finished; } catch (_) {} },
      windowStyle(name) { const win = this.windows[name]; if (win.maximized) return { zIndex: win.z, left: '0', top: '0', width: '100%', height: '100%' }; const style = { zIndex: win.z }; if (win.x !== null) { style.left = `${win.x}px`; style.top = `${win.y}px`; } return style; },
      startWindowDrag(event, name) { if (event.target.closest('button') || matchMedia('(max-width: 767px)').matches || this.windows[name].maximized) return; const element = event.currentTarget.closest('.app-window'); this.drag = { name, startX: event.clientX, startY: event.clientY, left: element.offsetLeft, top: element.offsetTop }; event.currentTarget.setPointerCapture?.(event.pointerId); },
      moveWindow(event) { if (!this.drag) return; const win = this.drag.screen ? this.screenWindows[this.drag.name] : this.windows[this.drag.name]; if (!win) return; const selector = this.drag.screen ? `[data-screen-name="${this.drag.name}"]` : `[data-app="${this.drag.name}"]`; const element = document.querySelector(selector), layer = document.querySelector('#window-layer'); const maxX = Math.max(0, (layer?.clientWidth || innerWidth) - (element?.offsetWidth || 0)); const maxY = Math.max(0, (layer?.clientHeight || innerHeight) - (element?.offsetHeight || 0) - 66); win.x = Math.min(maxX, Math.max(0, this.drag.left + event.clientX - this.drag.startX)); win.y = Math.min(maxY, Math.max(0, this.drag.top + event.clientY - this.drag.startY)); },
      constrainWindow(name, win, screen = false) { if (!win || win.maximized) return; const selector = screen ? `[data-screen-name="${name}"]` : `[data-app="${name}"]`; const element = document.querySelector(selector), layer = document.querySelector('#window-layer'); if (!element || !layer) return; const maxX = Math.max(0, layer.clientWidth - element.offsetWidth), maxY = Math.max(0, layer.clientHeight - element.offsetHeight - 66); if (win.x != null) win.x = Math.min(maxX, Math.max(0, win.x)); if (win.y != null) win.y = Math.min(maxY, Math.max(0, win.y)); },
      normalizeLayout() { this.$nextTick(() => { Object.entries(this.windows).forEach(([name, win]) => this.constrainWindow(name, win)); Object.entries(this.screenWindows).forEach(([name, win]) => { this.constrainWindow(name, win, true); if (win.visible && win.terminal) { win.fit?.fit(); if (win.socket?.readyState === WebSocket.OPEN) win.socket.send(JSON.stringify({ type: 'resize', cols: win.terminal.cols, rows: win.terminal.rows })); } }); this.editor?.resize(); this.sendResize(); this.normalizeDesktopIcons(); }); },
      refreshWallpaper() { this.wallpaperFailed = false; const mode = matchMedia('(max-width: 700px)').matches ? 'pe' : 'pc'; this.wallpaperUrl = `https://www.loliapi.com/acg/${mode}/?t=${Date.now()}`; },
      loadIconPositions() { try { this.iconPositions = JSON.parse(localStorage.getItem(iconPositionKey) || '{}'); } catch (_) { this.iconPositions = {}; } },
      desktopGrid() { const mobile = matchMedia('(max-width:767px)').matches; const startX = mobile ? 10 : 24, startY = mobile ? 66 : 78, stepX = mobile ? 70 : 104, stepY = mobile ? 90 : 110; return { startX, startY, stepX, stepY, rows: Math.max(1, Math.floor((window.innerHeight - startY - 72) / stepY)) }; },
      gridIconPosition(index) { const g = this.desktopGrid(); return { x: g.startX + Math.floor(index / g.rows) * g.stepX, y: g.startY + (index % g.rows) * g.stepY }; },
      iconGridIndex(position) { const g = this.desktopGrid(); const column = Math.max(0, Math.round((Number(position.x) - g.startX) / g.stepX)); const row = Math.max(0, Math.round((Number(position.y) - g.startY) / g.stepY)); return column * g.rows + Math.min(g.rows - 1, row); },
      iconPosition(key, index) { return this.iconPositions[key] || this.gridIconPosition(index); },
      iconStyle(key, index) { const pos = this.iconPosition(key, index), mobile = matchMedia('(max-width:767px)').matches, width = mobile ? 66 : 92, height = mobile ? 82 : 96; return { left: `${Math.min(Math.max(8, innerWidth - width - 8), Math.max(8, Number(pos.x)))}px`, top: `${Math.min(Math.max(58, innerHeight - height - 74), Math.max(58, Number(pos.y)))}px`, '--icon-index': index }; },
      normalizeDesktopIcons() { const mobile = matchMedia('(max-width:767px)').matches, width = mobile ? 66 : 92, height = mobile ? 82 : 96, maxX = Math.max(8, innerWidth - width - 8), maxY = Math.max(58, innerHeight - height - 74), g = this.desktopGrid(); const keys = [...this.desktopApps.map(item => item.app), ...this.desktopEntries.map(item => item.path)], occupied = new Set(); let changed = false; keys.forEach((key, index) => { const current = this.iconPositions[key] || this.gridIconPosition(index); let next = { x: Math.min(maxX, Math.max(8, Number(current.x) || 8)), y: Math.min(maxY, Math.max(58, Number(current.y) || 58)) }; let cell = `${Math.round((next.x - g.startX) / g.stepX)}:${Math.round((next.y - g.startY) / g.stepY)}`; if (occupied.has(cell)) { for (let slot = 0; slot < 500; slot++) { const candidate = this.gridIconPosition(slot); if (candidate.x > maxX || candidate.y > maxY) continue; const candidateCell = `${Math.round((candidate.x - g.startX) / g.stepX)}:${Math.round((candidate.y - g.startY) / g.stepY)}`; if (!occupied.has(candidateCell)) { next = candidate; cell = candidateCell; break; } } } occupied.add(cell); if (next.x !== current.x || next.y !== current.y) { this.iconPositions = { ...this.iconPositions, [key]: next }; changed = true; } }); if (changed) localStorage.setItem(iconPositionKey, JSON.stringify(this.iconPositions)); },
      placeNewDesktopIcons(entries) { const previous = new Set(this.desktopEntries.map(item => item.path)); const occupied = new Set(); this.desktopApps.forEach((item, index) => occupied.add(this.iconGridIndex(this.iconPosition(item.app, index)))); this.desktopEntries.forEach((item, index) => occupied.add(this.iconGridIndex(this.iconPosition(item.path, index + this.desktopApps.length)))); let next = occupied.size ? Math.max(...occupied) + 1 : this.desktopApps.length; let changed = false; entries.forEach(entry => { if (previous.has(entry.path) || this.iconPositions[entry.path]) return; while (occupied.has(next)) next++; this.iconPositions = { ...this.iconPositions, [entry.path]: this.gridIconPosition(next) }; occupied.add(next++); changed = true; }); if (changed) localStorage.setItem(iconPositionKey, JSON.stringify(this.iconPositions)); },
      autoArrangeDesktop() { const positions = {}; [...this.desktopApps, ...this.desktopEntries].forEach((item, index) => { positions[item.app || item.path] = this.gridIconPosition(index); }); localStorage.setItem(iconPositionKey, JSON.stringify(positions)); this.loadIconPositions(); },
      startIconDrag(event, key, index) { if (event.button !== undefined && event.button !== 0) return; const pos = this.iconPosition(key, index); this.iconDrag = { key, startX: event.clientX, startY: event.clientY, left: Number(pos.x), top: Number(pos.y), moved: false }; event.currentTarget.setPointerCapture?.(event.pointerId); },
      moveIcon(event) { const drag = this.iconDrag; if (!drag) return; const dx = event.clientX - drag.startX, dy = event.clientY - drag.startY; if (!drag.moved && Math.hypot(dx, dy) < 6) return; drag.moved = true; this.iconPositions = { ...this.iconPositions, [drag.key]: { x: Math.min(window.innerWidth - 80, Math.max(8, drag.left + dx)), y: Math.min(window.innerHeight - 100, Math.max(58, drag.top + dy)) } }; },
      finishIconDrag(event) { const drag = this.iconDrag; if (!drag) return; if (drag.moved) { this.snapIconToGrid(drag.key); localStorage.setItem(iconPositionKey, JSON.stringify(this.iconPositions)); this.suppressIconClick = true; setTimeout(() => { this.suppressIconClick = false; }, 250); } this.iconDrag = null; },
      onDesktopAppClick(app) { if (this.suppressIconClick) return; this.openApp(app); },
      onDesktopEntryClick(entry) { if (this.suppressIconClick || !matchMedia('(hover: none), (pointer: coarse)').matches) return; this.openRemoteEntry(entry); },
      snapIconToGrid(key) {
        const mobile = matchMedia('(max-width:767px)').matches;
        const g = mobile ? { x: 70, y: 90, startX: 10, startY: 66, cols: 3 } : { x: 104, y: 110, startX: 24, startY: 78, cols: Math.max(1, Math.floor((window.innerWidth - 36) / 104)) };
        const pos = this.iconPositions[key]; if (!pos) return;
        const used = new Set();
        Object.entries(this.iconPositions).forEach(([k, p]) => { if (k !== key) used.add(`${Math.round((p.x - g.startX) / g.x)}:${Math.round((p.y - g.startY) / g.y)}`); });
        let best = null, bestDist = Infinity;
        for (let r = 0; r < 20; r++) for (let c = 0; c < g.cols; c++) { const sk = `${c}:${r}`; if (used.has(sk)) continue; const sx = g.startX + c * g.x, sy = g.startY + r * g.y; const d = Math.hypot(pos.x - sx, pos.y - sy); if (d < bestDist) { bestDist = d; best = { x: sx, y: sy }; } }
        if (best) this.iconPositions = { ...this.iconPositions, [key]: best };
      },
      formatSize(size) { if (!size) return '0 B'; const units = ['B', 'KB', 'MB', 'GB']; let value = Number(size), index = 0; while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; } return `${value.toFixed(index ? 1 : 0)} ${units[index]}`; },
      formatBytes(value) { const bytes = Number(value); if (!Number.isFinite(bytes)) return '--'; const units = ['B', 'KB', 'MB', 'GB', 'TB']; let size = Math.max(0, bytes), index = 0; while (size >= 1024 && index < units.length - 1) { size /= 1024; index++; } return `${size.toFixed(index ? (size < 10 ? 1 : 0) : 0)} ${units[index]}`; },
      formatUptime(value) { let seconds = Math.max(0, Number(value) || 0); const days = Math.floor(seconds / 86400); seconds %= 86400; const hours = Math.floor(seconds / 3600); const minutes = Math.floor((seconds % 3600) / 60); return [days ? `${days} 天` : '', hours ? `${hours} 小时` : '', `${minutes} 分钟`].filter(Boolean).join(' '); },
      formatDate(value) { return value ? new Date(value).toLocaleDateString() : '-'; },
      formatDateTime(value) { return new Date(value).toLocaleString(); }
    },
    watch: {
      connection: { handler(value) { const id = value?.id || null; if (id === this.loadedDesktopConnectionId) return; this.loadedDesktopConnectionId = id; if (id) this.loadDesktopEntries().catch(error => this.toast(`桌面加载失败：${error.message}`, 'error')); else this.desktopEntries = []; }, deep: true }
    },
    mounted() {
      this.updateClock();
      this.clockTimer = setInterval(() => this.updateClock(), 1000);
      try {
         this.loadLocal(); this.loadIconPositions(); this.applyTheme(); this.windows.welcome.visible = this.settings.showWelcome;
        document.querySelector('.history-content').style.overflow = 'hidden';
        const monitor = document.querySelector('.monitor-content');
        const lower = monitor.querySelector('.monitor-lower');
        const chart = lower.querySelector('.chart-panel');
        Object.assign(monitor.style, { overflow: 'hidden', display: 'flex', flexDirection: 'column' });
        Object.assign(lower.style, { flex: '1 1 auto', minHeight: '0' });
        lower.querySelectorAll('.chart-panel, .system-facts').forEach(element => { element.style.height = '100%'; element.style.overflow = 'hidden'; });
        Object.assign(chart.style, { display: 'flex', flexDirection: 'column' });
        Object.assign(chart.querySelector('.trend-chart').style, { flex: '1 1 auto', minHeight: '0', height: 'auto' });
      } catch (e) { console.error('[webssh] init error', e); }
      try { this.initTerminal(); } catch (e) { console.error('[webssh] terminal init error', e); }
      const navigation = performance.getEntriesByType?.('navigation')?.[0];
      if (navigation?.type === 'reload' || sessionStorage.getItem(activeConnectionKey)) this.windows.welcome.visible = false;
       this.restoreConnection().then(() => this.loadScreenSessions());
       this.screenTimer = setInterval(() => this.loadScreenSessions(), 4000);
       this.normalizeLayout();
       document.addEventListener('click', (e) => {
        if (e.target.closest('#file-dialog-submit')) {
          e.preventDefault();
          this.submitDialog();
          return;
        }
         this.dismissMenus(e);
       });
       document.addEventListener('mouseover', event => { const button = event.target.closest('.file-name'); if (!button) return; const name = button.querySelector('span')?.textContent?.trim(); const item = this.fileEntries.find(entry => entry.name === name); if (item) button.title = item.type === 'directory' ? '打开目录' : this.isExecutableEntry(this.fileEntry(item)) ? '使用 screen 运行' : '打开编辑'; });
      systemTheme.addEventListener?.('change', () => { if (this.settings.theme === 'system') this.applyTheme(); });
       window.addEventListener('resize', () => this.normalizeLayout());
       window.visualViewport?.addEventListener('resize', () => this.normalizeLayout());
      window.addEventListener('pointermove', (event) => { this.moveWindow(event); this.moveIcon(event); });
      window.addEventListener('pointerup', () => { this.drag = null; });
      window.addEventListener('keydown', (event) => { if (event.key === 'Escape') this.handleEscape(); });
      window.addEventListener('beforeunload', (event) => { if (this.editorDirty) { event.preventDefault(); event.returnValue = ''; } });
      window.addEventListener('error', (e) => console.error('[webssh] global error', e.message, e.filename, e.lineno));
      window.addEventListener('unhandledrejection', (e) => console.error('[webssh] unhandled rejection', e.reason));
    },
    beforeUnmount() { clearInterval(this.clockTimer); clearInterval(this.screenTimer); this.stopMetrics(); clearTimeout(this.reconnectTimer); this.socket?.close(); Object.values(this.screenWindows).forEach(win => { win.socket?.close(); win.terminal?.dispose(); }); }
  }).mount('body');
})();
