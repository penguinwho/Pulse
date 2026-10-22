(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const safeJSON = (key, fallback) => {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
    catch { return fallback; }
  };
  const uid = () => globalThis.crypto?.randomUUID?.() || `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
  const escapeHTML = (value) => String(value).replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
  const formatTime = (value) => new Date(value).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const icon = window.PulseIcons;

  let accounts = safeJSON('pulseAccounts', []);
  const lastAccount = safeJSON('pulseAccount', null);
  const legacyName = localStorage.getItem('pulseUser');
  const tabAccountId = sessionStorage.getItem('pulseAccountId');
  const currentAccount = accounts.find((account) => account.id === tabAccountId)
    || accounts.find((account) => account.id === lastAccount?.id)
    || accounts.find((account) => account.email === lastAccount?.email)
    || accounts.find((account) => account.name === legacyName);

  if (!currentAccount) {
    window.location.replace('signin.html');
    return;
  }

  if (!currentAccount.id) {
    currentAccount.id = uid();
    localStorage.setItem('pulseAccounts', JSON.stringify(accounts));
    localStorage.setItem('pulseAccount', JSON.stringify(currentAccount));
  }
  sessionStorage.setItem('pulseAccountId', currentAccount.id);

  const PROFILE_KEY = 'pulseProfilesV5';
  const SOCIAL_KEY = 'pulseSocialV1';
  const MAX_UPLOAD = 1024 * 1024;
  const defaults = {
    name: currentAccount.name,
    pronouns: '',
    status: 'Online',
    bio: '',
    color: '#5865f2',
    avatar: null,
    banner: null,
    decoration: 'none',
    nameplate: 'none',
    effect: 'none',
    connections: {},
    activityEnabled: true,
    favoriteGame: ''
  };

  let profiles = safeJSON(PROFILE_KEY, {});
  let social = safeJSON(SOCIAL_KEY, { conversations: {}, requests: [] });
  if (!social.conversations) social.conversations = {};
  if (!social.requests) social.requests = [];
  profiles[currentAccount.id] = { ...defaults, ...(profiles[currentAccount.id] || {}) };
  profiles[currentAccount.id].connections ||= {};

  let view = { type: 'friends', filter: 'all', conversationId: null };
  let settingsDraft = null;
  let toastTimer = null;
  let muted = false;
  let chatContext = null;
  let replyTo = null;
  let attachment = null;
  let searchQuery = '';
  let showPins = false;
  const drafts = new Map();
  const modalTimers = new Map();
  let returnFocus = null;

  const saveProfiles = () => localStorage.setItem(PROFILE_KEY, JSON.stringify(profiles));
  const saveSocial = () => localStorage.setItem(SOCIAL_KEY, JSON.stringify(social));
  const profileFor = (accountId) => {
    const account = accounts.find((item) => item.id === accountId);
    return { ...defaults, name: account?.name || 'Unknown user', ...(profiles[accountId] || {}) };
  };
  const accountFor = (accountId) => accounts.find((item) => item.id === accountId);
  const currentProfile = () => profileFor(currentAccount.id);
  const otherParticipant = (conversation) => conversation.participants.find((id) => id !== currentAccount.id) || currentAccount.id;
  const mobileButton = () => `<button class="toolbar-icon mobile-sidebar-button" data-action="toggle-sidebar" aria-label="Open navigation">${icon('menu')}</button>`;

  function goHome() {
    rememberDraft();
    window.PulseServers?.goHome();
    $('#homeSidebar').classList.remove('hidden');
    $('#serverSidebar').classList.add('hidden');
    $('#activityPanel').innerHTML = '<h2>Active Now</h2><div class="activity-empty"><strong>It’s quiet for now…</strong><p>No shared activity right now.</p></div>';
    $('.brand-btn').classList.add('active');
    chatContext = null;
  }

  function rememberDraft() {
    if (chatContext && $('#messageInput')) drafts.set(chatContext.id, $('#messageInput').value);
  }

  function avatarHTML(accountId, size = '') {
    const profile = profileFor(accountId);
    const image = profile.avatar ? `style="--avatar-image:url('${profile.avatar}');--avatar-color:${profile.color}"` : `style="--avatar-color:${profile.color}"`;
    const decoration = profile.decoration !== 'none' ? `<i class="avatar-decoration ${escapeHTML(profile.decoration)}"></i>` : '';
    return `<span class="avatar ${profile.avatar ? 'has-image' : ''} ${size}" ${image}>${escapeHTML(profile.name.charAt(0).toUpperCase())}${decoration}</span>`;
  }

  function showToast(message) {
    const toast = $('#toast');
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.remove('hidden');
    toastTimer = setTimeout(() => toast.classList.add('hidden'), 2400);
  }

  function updateIdentity() {
    const profile = currentProfile();
    $('#selfName').textContent = profile.name;
    $('#selfStatus').textContent = profile.status || 'Online';
    const avatar = $('#selfAvatar');
    avatar.textContent = profile.name.charAt(0).toUpperCase();
    avatar.className = `avatar online ${profile.avatar ? 'has-image' : ''}`;
    avatar.style.setProperty('--avatar-color', profile.color);
    avatar.style.setProperty('--avatar-image', profile.avatar ? `url('${profile.avatar}')` : 'none');
  }

  function conversationEntries() {
    return Object.entries(social.conversations)
      .filter(([, conversation]) => conversation.participants.includes(currentAccount.id))
      .sort(([, a], [, b]) => (b.updatedAt || 0) - (a.updatedAt || 0));
  }

  function renderDMList(filter = '') {
    const query = filter.trim().toLowerCase();
    const entries = conversationEntries().filter(([, conversation]) => {
      const profile = profileFor(otherParticipant(conversation));
      return !query || profile.name.toLowerCase().includes(query);
    });
    $('#dmList').innerHTML = entries.length ? entries.map(([id, conversation]) => {
      const otherId = otherParticipant(conversation);
      const profile = profileFor(otherId);
      const lastMessage = conversation.messages.at(-1);
      return `<button class="dm-item ${view.conversationId === id ? 'active' : ''}" data-conversation-id="${id}">${avatarHTML(otherId)}<span><strong>${escapeHTML(profile.name)}</strong><small>${escapeHTML(lastMessage?.text || profile.status || 'Start a conversation')}</small></span></button>`;
    }).join('') : '<div class="sidebar-empty">No direct messages yet.</div>';
  }

  function setActiveNav(name) {
    $$('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.view === name));
  }

  function renderFriends(filter = 'all') {
    goHome();
    view = { type: 'friends', filter, conversationId: null };
    setActiveNav('friends');
    $('#activityPanel').classList.remove('hidden');
    const people = accounts.filter((account) => account.id !== currentAccount.id);
    const filtered = filter === 'online' ? people.filter((person) => isOnline(person.id)) : people;
    $('#toolbar').innerHTML = `${mobileButton()}<div class="toolbar-title">${icon('friends')}<strong>Friends</strong></div><div class="toolbar-divider"></div><div class="toolbar-tabs"><button data-friend-filter="online" class="${filter === 'online' ? 'active' : ''}">Online</button><button data-friend-filter="all" class="${filter === 'all' ? 'active' : ''}">All</button><button data-view="requests">Pending</button></div><button class="toolbar-icon" data-action="new-dm" title="New direct message" aria-label="New direct message">${icon('message')}</button>`;
    $('#content').innerHTML = `<div class="friends-view"><label class="friend-search"><input id="friendSearch" placeholder="Search"><span>⌕</span></label><h3>${filter === 'online' ? 'Online' : 'All Friends'} — ${filtered.length}</h3><div class="friends-list" id="friendsList"></div></div>`;
    renderFriendRows(filtered);
    renderDMList();
  }

  function renderFriendRows(people) {
    const list = $('#friendsList');
    if (!people.length) {
      list.innerHTML = '<div class="empty-state"><div class="empty-illustration">☁</div><h2>No one’s around yet</h2><p>Create another local account to test direct messaging.</p></div>';
      return;
    }
    list.innerHTML = people.map((account) => {
      const profile = profileFor(account.id);
      return `<article class="friend-row" data-person-id="${account.id}">${avatarHTML(account.id, 'large')}<div class="friend-copy"><strong>${escapeHTML(profile.name)}</strong><small>${escapeHTML(isOnline(account.id) ? profile.status || 'Online' : 'Offline')}</small></div><button data-start-dm="${account.id}" title="Message" aria-label="Message ${escapeHTML(profile.name)}">${icon('message')}</button><button data-profile-popout="${account.id}" title="Profile" aria-label="Profile of ${escapeHTML(profile.name)}">${icon('more')}</button></article>`;
    }).join('');
  }

  function renderRequests() {
    goHome();
    view = { type: 'requests', filter: null, conversationId: null };
    setActiveNav('requests');
    $('#activityPanel').classList.remove('hidden');
    const pending = social.requests.filter((request) => request.to === currentAccount.id || request.from === currentAccount.id);
    $('#toolbar').innerHTML = '<div class="toolbar-title"><span>♣</span><strong>Friends</strong></div><div class="toolbar-divider"></div><div class="toolbar-tabs"><button data-friend-filter="online">Online</button><button data-friend-filter="all">All</button><button class="active">Pending</button></div>';
    $('#content').innerHTML = `<div class="friends-view"><h3>Pending — ${pending.length}</h3><div class="friends-list">${pending.length ? pending.map((request) => {
      const otherId = request.from === currentAccount.id ? request.to : request.from;
      const profile = profileFor(otherId);
      return `<article class="friend-row">${avatarHTML(otherId, 'large')}<div class="friend-copy"><strong>${escapeHTML(profile.name)}</strong><small>${request.from === currentAccount.id ? 'Outgoing friend request' : 'Incoming friend request'}</small></div><button data-request-action="accept" data-request-id="${request.id}" title="Accept">✓</button><button data-request-action="remove" data-request-id="${request.id}" title="Remove">×</button></article>`;
    }).join('') : '<div class="empty-state"><div class="empty-illustration">✓</div><h2>You’re all caught up</h2><p>No pending friend requests.</p></div>'}</div></div>`;
    renderDMList();
  }

  function conversationFor(id) { return social.conversations[id]; }

  function renderConversation(conversationId) {
    const conversation = conversationFor(conversationId);
    if (!conversation || !conversation.participants.includes(currentAccount.id)) return renderFriends();
    view = { type: 'conversation', filter: null, conversationId };
    setActiveNav('');
    $('#activityPanel').classList.add('hidden');
    const otherId = otherParticipant(conversation);
    const otherProfile = profileFor(otherId);
    $('#toolbar').innerHTML = `${avatarHTML(otherId)}<div class="toolbar-title"><strong>${escapeHTML(otherProfile.name)}</strong><small>${escapeHTML(otherProfile.status || 'Online')}</small></div><div class="toolbar-actions"><button title="Start voice call">☎</button><button title="Start video call">▣</button><button data-profile-popout="${otherId}" title="User profile">♣</button><label class="message-search"><input id="messageSearch" placeholder="Search"><span>⌕</span></label></div>`;
    $('#content').innerHTML = `<div class="chat-view"><div class="messages" id="messages"></div><div class="typing-line" id="typingLine"></div><form class="composer" id="composer"><button type="button" data-action="attach-file" title="Upload a file">＋</button><input id="messageInput" autocomplete="off" maxlength="2000" placeholder="Message @${escapeHTML(otherProfile.name)}"><button type="button" data-action="insert-emoji" title="Emoji">☺</button><button class="send-button" aria-label="Send message">Send</button></form></div>`;
    renderMessages(conversation);
    renderDMList();
  }

  function renderMessages(conversation) {
    const otherId = otherParticipant(conversation);
    const other = profileFor(otherId);
    const target = $('#messages');
    target.innerHTML = `<div class="conversation-start">${avatarHTML(otherId, 'xlarge')}<h1>${escapeHTML(other.name)}</h1><p>This is the beginning of your direct message history with <strong>${escapeHTML(other.name)}</strong>.</p></div>${conversation.messages.map((message) => {
      const sender = profileFor(message.senderId);
      const mine = message.senderId === currentAccount.id;
      return `<article class="message ${mine ? 'mine' : ''}" data-message-id="${message.id}">${avatarHTML(message.senderId, 'large')}<div class="message-body"><header><strong>${escapeHTML(sender.name)}</strong><time>${formatTime(message.createdAt)}</time>${message.edited ? '<em>(edited)</em>' : ''}</header><p>${escapeHTML(message.text)}</p>${message.reaction ? `<button class="reaction" data-react-message="${message.id}">${escapeHTML(message.reaction)} 1</button>` : ''}</div><div class="message-actions"><button data-react-message="${message.id}" title="React">☺</button>${mine ? `<button data-edit-message="${message.id}" title="Edit">✎</button><button data-delete-message="${message.id}" title="Delete">×</button>` : ''}</div></article>`;
    }).join('')}`;
    target.scrollTop = target.scrollHeight;
  }

  function startConversation(personId) {
    if (personId === currentAccount.id) return;
    let existing = conversationEntries().find(([, conversation]) => conversation.participants.includes(personId));
    let id = existing?.[0];
    if (!id) {
      id = uid();
      social.conversations[id] = { participants: [currentAccount.id, personId], messages: [], updatedAt: Date.now() };
      saveSocial();
    }
    closeModal('#personModal');
    closeModal('#quickModal');
    renderConversation(id);
  }

  function sendMessage(text) {
    const conversation = conversationFor(view.conversationId);
    if (!conversation || !text.trim()) return;
    conversation.messages.push({ id: uid(), senderId: currentAccount.id, text: text.trim(), createdAt: Date.now(), edited: false, reaction: '' });
    conversation.updatedAt = Date.now();
    saveSocial();
    renderMessages(conversation);
    renderDMList();
  }

  function renderPersonPicker(query = '') {
    const normalized = query.toLowerCase().trim();
    const people = accounts.filter((account) => account.id !== currentAccount.id && (!normalized || account.name.toLowerCase().includes(normalized) || account.email.toLowerCase().includes(normalized)));
    $('#personPicker').innerHTML = people.length ? people.map((account) => `<button class="person-option" data-start-dm="${account.id}">${avatarHTML(account.id, 'large')}<span><strong>${escapeHTML(profileFor(account.id).name)}</strong><small>${escapeHTML(account.email)}</small></span><i>Message</i></button>`).join('') : '<div class="picker-empty">No other local accounts match.</div>';
  }

  function renderQuickResults(query = '') {
    const normalized = query.toLowerCase().trim();
    const items = [
      { label: 'Friends', detail: 'Open friends', action: 'friends' },
      { label: 'User Settings', detail: 'Edit your profile', action: 'settings' },
      ...conversationEntries().map(([id, conversation]) => ({ label: profileFor(otherParticipant(conversation)).name, detail: 'Direct message', action: `dm:${id}` }))
    ].filter((item) => !normalized || item.label.toLowerCase().includes(normalized));
    $('#quickResults').innerHTML = items.map((item, index) => `<button class="quick-result ${index === 0 ? 'selected' : ''}" data-quick-action="${item.action}"><span>${escapeHTML(item.label)}</span><small>${escapeHTML(item.detail)}</small></button>`).join('') || '<div class="picker-empty">No results.</div>';
  }

  function openModal(selector) {
    $(selector).classList.remove('hidden');
    requestAnimationFrame(() => $(selector).classList.add('open'));
  }

  function closeModal(selector) {
    const modal = $(selector);
    modal.classList.remove('open');
    setTimeout(() => modal.classList.add('hidden'), 180);
  }

  const colorOptions = ['#5865f2', '#eb459e', '#23a559', '#f0b232', '#9b59b6', '#ed4245'];
  const decorations = [['none', 'None'], ['ring', 'Orbit Ring'], ['spark', 'Sparkles'], ['crown', 'Crown']];
  const nameplates = [['none', 'None'], ['gradient', 'Gradient'], ['neon', 'Neon'], ['midnight', 'Midnight']];
  const effects = [['none', 'None'], ['starlight', 'Starlight'], ['aurora', 'Aurora'], ['glow', 'Soft Glow']];
  const services = [['Steam', '◉'], ['Spotify', '♫'], ['Twitch', '◈'], ['YouTube', '▶'], ['Xbox', '✕']];

  function openSettings(tab = 'account') {
    settingsDraft = structuredClone(currentProfile());
    populateSettings();
    switchSettingsTab(tab);
    openModal('#settingsModal');
  }

  function switchSettingsTab(name) {
    $$('.settings-nav [data-settings-tab]').forEach((button) => button.classList.toggle('active', button.dataset.settingsTab === name));
    $$('[data-tab-panel]').forEach((panel) => panel.classList.toggle('hidden', panel.dataset.tabPanel !== name));
    renderSettingsControls();
  }

  function populateSettings() {
    $('#profileName').value = settingsDraft.name;
    $('#profilePronouns').value = settingsDraft.pronouns;
    $('#profileStatus').value = settingsDraft.status;
    $('#profileBio').value = settingsDraft.bio;
    $('#profileColor').value = settingsDraft.color;
    $('#favoriteGame').value = settingsDraft.favoriteGame;
    renderSettingsControls();
  }

  function renderSettingsControls() {
    if (!settingsDraft) return;
    $('#accountName').textContent = settingsDraft.name;
    $('#accountHandle').textContent = `@${currentAccount.email.split('@')[0]}`;
    $('#accountDisplayName').textContent = settingsDraft.name;
    $('#accountEmail').textContent = currentAccount.email;
    const accountAvatar = $('#accountAvatar');
    styleAvatar(accountAvatar, settingsDraft);
    $('#accountBanner').style.backgroundImage = settingsDraft.banner ? `url('${settingsDraft.banner}')` : `linear-gradient(120deg, ${settingsDraft.color}, #9b6dff)`;

    $('#swatches').innerHTML = colorOptions.map((color) => `<button class="swatch ${settingsDraft.color === color ? 'active' : ''}" data-color="${color}" style="--swatch:${color}" aria-label="${color}"></button>`).join('');
    $('#decorOptions').innerHTML = decorations.map(([value, label]) => `<button class="appearance-option ${settingsDraft.decoration === value ? 'active' : ''}" data-option="decoration" data-value="${value}"><span>${value === 'spark' ? '✨' : value === 'crown' ? '♛' : value === 'ring' ? '◯' : '×'}</span><strong>${label}</strong></button>`).join('');
    $('#nameplateOptions').innerHTML = nameplates.map(([value, label]) => `<button class="appearance-option ${settingsDraft.nameplate === value ? 'active' : ''}" data-option="nameplate" data-value="${value}"><span class="mini-nameplate ${value}">${label}</span><strong>${label}</strong></button>`).join('');
    $('#effectOptions').innerHTML = effects.map(([value, label]) => `<button class="appearance-option ${settingsDraft.effect === value ? 'active' : ''}" data-option="effect" data-value="${value}"><span>${value === 'starlight' ? '✦' : value === 'aurora' ? '◒' : value === 'glow' ? '☼' : '×'}</span><strong>${label}</strong></button>`).join('');
    $('#connections').innerHTML = services.map(([name, icon]) => `<article class="connection-row"><span class="service-icon">${icon}</span><div><strong>${name}</strong><small>${settingsDraft.connections[name] ? 'Connected locally' : 'Not connected'}</small></div><button class="button ${settingsDraft.connections[name] ? 'success' : ''}" data-connection="${name}">${settingsDraft.connections[name] ? 'Connected' : 'Connect'}</button></article>`).join('');
    $('#activitySwitch').classList.toggle('on', settingsDraft.activityEnabled);
    renderProfilePreview();
  }

  function styleAvatar(element, profile) {
    element.textContent = profile.name.charAt(0).toUpperCase();
    element.className = `profile-avatar ${profile.avatar ? 'has-image' : ''} decoration-${profile.decoration}`;
    element.style.setProperty('--avatar-color', profile.color);
    element.style.setProperty('--avatar-image', profile.avatar ? `url('${profile.avatar}')` : 'none');
  }

  function syncDraftFromForm() {
    if (!settingsDraft) return;
    settingsDraft.name = $('#profileName').value.trim() || currentAccount.name;
    settingsDraft.pronouns = $('#profilePronouns').value.trim();
    settingsDraft.status = $('#profileStatus').value.trim();
    settingsDraft.bio = $('#profileBio').value.trim();
    settingsDraft.favoriteGame = $('#favoriteGame').value.trim();
  }

  function renderProfilePreview() {
    syncDraftFromForm();
    $('#previewName').textContent = settingsDraft.name;
    $('#previewPronouns').textContent = settingsDraft.pronouns;
    $('#previewBio').textContent = settingsDraft.bio || 'No bio yet.';
    $('#previewStatus').textContent = settingsDraft.status || 'Online';
    const nameplate = $('#previewNameplate');
    nameplate.textContent = settingsDraft.nameplate === 'none' ? '' : settingsDraft.nameplate.toUpperCase();
    nameplate.className = `nameplate ${settingsDraft.nameplate === 'none' ? 'hidden' : settingsDraft.nameplate}`;
    styleAvatar($('#previewAvatar'), settingsDraft);
    $('#profilePreview').dataset.effect = settingsDraft.effect;
    $('#previewBanner').style.backgroundImage = settingsDraft.banner ? `url('${settingsDraft.banner}')` : `linear-gradient(120deg, ${settingsDraft.color}, #9b6dff)`;
  }

  function readImage(file, key) {
    if (!file) return;
    if (!file.type.startsWith('image/')) return showToast('Choose an image or animated GIF.');
    if (file.size > MAX_UPLOAD) return showToast('Use an image smaller than 1 MB.');
    const reader = new FileReader();
    reader.onload = () => { settingsDraft[key] = reader.result; renderSettingsControls(); };
    reader.readAsDataURL(file);
  }

  function saveSettings() {
    syncDraftFromForm();
    if (!settingsDraft.name) return showToast('Display name cannot be empty.');
    profiles[currentAccount.id] = settingsDraft;
    try { saveProfiles(); }
    catch { return showToast('Those images are too large for browser storage.'); }
    updateIdentity();
    renderDMList();
    closeModal('#settingsModal');
    showToast('Settings saved.');
  }

  function handleAction(action) {
    if (action === 'show-friends') renderFriends();
    if (action === 'open-settings') openSettings();
    if (action === 'close-settings') closeModal('#settingsModal');
    if (action === 'new-dm') { renderPersonPicker(); openModal('#personModal'); setTimeout(() => $('#personSearch').focus(), 50); }
    if (action === 'close-person-modal') closeModal('#personModal');
    if (action === 'open-quick-switcher') { renderQuickResults(); openModal('#quickModal'); setTimeout(() => $('#quickSearch').focus(), 50); }
    if (action === 'toggle-mute') { muted = !muted; $('#muteButton').classList.toggle('active', muted); showToast(muted ? 'Microphone muted.' : 'Microphone unmuted.'); }
    if (action === 'insert-emoji') { const input = $('#messageInput'); if (input) { input.value += ' 🙂'; input.focus(); } }
    if (action === 'attach-file') showToast('File uploads need a storage backend.');
    if (action === 'toggle-activity') { settingsDraft.activityEnabled = !settingsDraft.activityEnabled; renderSettingsControls(); }
    if (action === 'reset-settings') { settingsDraft = structuredClone(currentProfile()); populateSettings(); showToast('Changes reset.'); }
    if (action === 'save-settings') saveSettings();
    if (action === 'logout') { localStorage.removeItem('pulseUser'); localStorage.removeItem('pulseAccount'); window.location.assign('index.html'); }
  }

  document.addEventListener('click', (event) => {
    const action = event.target.closest('[data-action]')?.dataset.action;
    if (action) handleAction(action);

    const viewName = event.target.closest('[data-view]')?.dataset.view;
    if (viewName === 'friends') renderFriends();
    if (viewName === 'requests') renderRequests();

    const filter = event.target.closest('[data-friend-filter]')?.dataset.friendFilter;
    if (filter) renderFriends(filter);

    const conversationId = event.target.closest('[data-conversation-id]')?.dataset.conversationId;
    if (conversationId) renderConversation(conversationId);

    const personId = event.target.closest('[data-start-dm]')?.dataset.startDm;
    if (personId) startConversation(personId);

    const tab = event.target.closest('[data-settings-tab]')?.dataset.settingsTab;
    if (tab) switchSettingsTab(tab);

    const swatch = event.target.closest('[data-color]');
    if (swatch && settingsDraft) { settingsDraft.color = swatch.dataset.color; $('#profileColor').value = settingsDraft.color; renderSettingsControls(); }

    const option = event.target.closest('[data-option]');
    if (option && settingsDraft) { settingsDraft[option.dataset.option] = option.dataset.value; renderSettingsControls(); }

    const connection = event.target.closest('[data-connection]')?.dataset.connection;
    if (connection && settingsDraft) { settingsDraft.connections[connection] = !settingsDraft.connections[connection]; renderSettingsControls(); }

    const quick = event.target.closest('[data-quick-action]')?.dataset.quickAction;
    if (quick === 'friends') { closeModal('#quickModal'); renderFriends(); }
    if (quick === 'settings') { closeModal('#quickModal'); openSettings(); }
    if (quick?.startsWith('dm:')) { closeModal('#quickModal'); renderConversation(quick.slice(3)); }

    const editId = event.target.closest('[data-edit-message]')?.dataset.editMessage;
    if (editId) {
      const message = conversationFor(view.conversationId).messages.find((item) => item.id === editId);
      const text = prompt('Edit message', message.text);
      if (text?.trim()) { message.text = text.trim(); message.edited = true; saveSocial(); renderMessages(conversationFor(view.conversationId)); }
    }

    const deleteId = event.target.closest('[data-delete-message]')?.dataset.deleteMessage;
    if (deleteId && confirm('Delete this message?')) {
      const conversation = conversationFor(view.conversationId);
      conversation.messages = conversation.messages.filter((item) => item.id !== deleteId);
      saveSocial(); renderMessages(conversation);
    }

    const reactId = event.target.closest('[data-react-message]')?.dataset.reactMessage;
    if (reactId) {
      const message = conversationFor(view.conversationId).messages.find((item) => item.id === reactId);
      message.reaction = message.reaction ? '' : '✦'; saveSocial(); renderMessages(conversationFor(view.conversationId));
    }

    const requestAction = event.target.closest('[data-request-action]');
    if (requestAction) {
      social.requests = social.requests.filter((request) => request.id !== requestAction.dataset.requestId);
      saveSocial(); renderRequests(); showToast(requestAction.dataset.requestAction === 'accept' ? 'Friend request accepted.' : 'Friend request removed.');
    }

    if (event.target.classList.contains('modal-backdrop')) closeModal(`#${event.target.id}`);
  });

  document.addEventListener('submit', (event) => {
    if (event.target.id === 'composer') {
      event.preventDefault();
      const input = $('#messageInput');
      sendMessage(input.value);
      input.value = '';
      $('#typingLine').textContent = '';
    }
    if (event.target.id === 'profileForm') event.preventDefault();
  });

  document.addEventListener('input', (event) => {
    if (event.target.id === 'friendSearch') {
      const query = event.target.value.toLowerCase();
      renderFriendRows(accounts.filter((account) => account.id !== currentAccount.id && profileFor(account.id).name.toLowerCase().includes(query)));
    }
    if (event.target.id === 'messageSearch') {
      const query = event.target.value.toLowerCase();
      $$('.message').forEach((message) => message.classList.toggle('hidden', query && !message.textContent.toLowerCase().includes(query)));
    }
    if (event.target.id === 'personSearch') renderPersonPicker(event.target.value);
    if (event.target.id === 'quickSearch') renderQuickResults(event.target.value);
    if (event.target.id === 'messageInput') $('#typingLine').textContent = event.target.value ? 'You are typing…' : '';
    if (['profileName', 'profilePronouns', 'profileStatus', 'profileBio', 'favoriteGame'].includes(event.target.id)) renderProfilePreview();
    if (event.target.id === 'profileColor') { settingsDraft.color = event.target.value; renderSettingsControls(); }
  });

  $('#avatarUpload').addEventListener('change', (event) => { readImage(event.target.files[0], 'avatar'); event.target.value = ''; });
  $('#bannerUpload').addEventListener('change', (event) => { readImage(event.target.files[0], 'banner'); event.target.value = ''; });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      ['#personModal', '#quickModal', '#settingsModal'].forEach((selector) => { if (!$(selector).classList.contains('hidden')) closeModal(selector); });
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault(); handleAction('open-quick-switcher');
    }
  });

  updateIdentity();
  renderFriends();
})();
